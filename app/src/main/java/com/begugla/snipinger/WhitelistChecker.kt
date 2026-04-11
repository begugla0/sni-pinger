package com.begugla.snipinger

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import android.os.Build
import java.security.cert.X509Certificate
import javax.net.ssl.*
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.*

class WhitelistChecker {

    fun checkIp(
        inputIp: String,
        sni: String,
        timeout: Int = 5
    ): CheckResult {
        // Point 2: Resolve hostname to IP if needed
        val ip = try {
            InetAddress.getByName(inputIp).hostAddress ?: inputIp
        } catch (e: Exception) {
            inputIp
        }

        val result = CheckResult(ip = ip, sni = sni, port = 443, timeout = timeout.toFloat())
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeout * 1000
        val secondaryTimeoutMs = minOf(3000, timeoutMs)

        try {
            runBlocking {
                val geoJob = async(Dispatchers.IO) { _ipGeoInfo(ip, result) }
                val domainJob = async(Dispatchers.IO) { _sniOwnership(sni, result) }
                val dnsJob = async(Dispatchers.IO) { _dnsCheck(sni, ip, result) }
                val pingJob = async(Dispatchers.IO) { _approximatePing(ip, secondaryTimeoutMs, result) }
                val portsJob = async(Dispatchers.IO) { _tcpPorts(ip, secondaryTimeoutMs, result) }

                awaitAll(geoJob, domainJob, dnsJob, pingJob, portsJob)
            }

            val socket = _connectSocket(ip, 443, timeoutMs, result)

            if (socket != null) {
                _fullTlsHandshake(socket, sni, timeoutMs, result)

                runBlocking {
                    val tls12Job = async(Dispatchers.IO) {
                        _tlsVersionCheck(ip, sni, "TLSv1.2", secondaryTimeoutMs) { ok ->
                            result.tls12Ok = ok
                        }
                    }
                    val tls13Job = async(Dispatchers.IO) {
                        _tlsVersionCheck(ip, sni, "TLSv1.3", secondaryTimeoutMs) { ok ->
                            result.tls13Ok = ok
                        }
                    }
                    val h2Job = async(Dispatchers.IO) {
                        if (Build.VERSION.SDK_INT >= 29) {
                            _checkH2Support(ip, sni, secondaryTimeoutMs, result)
                        }
                    }
                    val httpJob = async(Dispatchers.IO) {
                        _combinedHttpCheck(ip, sni, timeoutMs, result)
                    }

                    awaitAll(tls12Job, tls13Job, h2Job, httpJob)
                }
            }
        } catch (e: Exception) {
            result.errors.add("General error: ${e.message}")
        }

        _makeVerdict(result)
        result.totalTime = (System.currentTimeMillis() - startTime) / 1000.0
        return result
    }

    private fun _ipInfo(ip: String, result: CheckResult) {
        try {
            val addr = InetAddress.getByName(ip)
            result.ipVersion = if (addr.address.size == 4) 4 else 6
            result.ipIsPrivate = addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
            result.ipIsGlobal = !result.ipIsPrivate!!
        } catch (e: Exception) {
            result.errors.add("ip_info: ${e.message}")
        }
    }

    private fun _ipGeoInfo(ip: String, result: CheckResult) {
        _ipInfo(ip, result)
        try {
            val info = IpInfoChecker.getIpInfo(ip)
            if (info != null) result.ipGeoInfo = info
        } catch (e: Exception) { /* non-critical */ }
    }

    private fun _sniOwnership(sni: String, result: CheckResult) {
        try {
            val domainIps = IpInfoChecker.getDomainIps(sni)
            result.domainResolvedIps = domainIps
            if (domainIps.isNotEmpty()) {
                val domainGeo = IpInfoChecker.getIpInfo(domainIps.first())
                if (domainGeo?.org != null) result.domainOwnerOrg = domainGeo.org
            }
        } catch (e: Exception) { /* non-critical */ }
    }

    private fun _dnsCheck(sni: String, ip: String, result: CheckResult) {
        val start = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName(sni)
            result.dnsResolveTime = (System.currentTimeMillis() - start) / 1000.0
            result.dnsResolvesTo = addresses.mapNotNull { it.hostAddress }
            result.ipMatchesDns = result.dnsResolvesTo.contains(ip)
        } catch (e: Exception) {
            result.errors.add("dns: ${e.message}")
        }
    }

    private fun _approximatePing(ip: String, timeoutMs: Int, result: CheckResult) {
        try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, 443), minOf(1500, timeoutMs))
            result.rttMs = (System.currentTimeMillis() - start).toDouble()
            socket.close()
        } catch (e: Exception) { /* handled elsewhere */ }
    }

    private fun _tcpPorts(ip: String, timeoutMs: Int, result: CheckResult) {
        runBlocking {
            val job80 = async(Dispatchers.IO) {
                checkPort(ip, 80, minOf(1500, timeoutMs)) { reachable, time ->
                    result.tcp80Reachable = reachable
                    result.tcp80ConnectTime = time
                }
            }
            val job53 = async(Dispatchers.IO) {
                checkPort(ip, 53, minOf(1500, timeoutMs)) { reachable, _ ->
                    result.tcp53Reachable = reachable
                }
            }
            val job8080 = async(Dispatchers.IO) {
                checkPort(ip, 8080, minOf(1500, timeoutMs)) { reachable, _ ->
                    result.tcp8080Reachable = reachable
                }
            }
            awaitAll(job80, job53, job8080)
        }
    }

    private fun checkPort(ip: String, port: Int, timeoutMs: Int, callback: (Boolean, Double?) -> Unit) {
        try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            callback(true, (System.currentTimeMillis() - start) / 1000.0)
        } catch (e: Exception) {
            callback(false, null)
        }
    }

    private fun _connectSocket(ip: String, port: Int, timeoutMs: Int, result: CheckResult): Socket? {
        val socket = Socket()
        val start = System.currentTimeMillis()
        return try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            result.tcpConnectTime = (System.currentTimeMillis() - start) / 1000.0
            result.tcpReachable = true
            if (result.rttMs == null) result.rttMs = result.tcpConnectTime!! * 1000.0
            socket
        } catch (e: Exception) {
            result.tcpReachable = false
            result.errors.add("tcp 443: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            null
        }
    }

    private fun _fullTlsHandshake(rawSocket: Socket, sni: String, timeoutMs: Int, result: CheckResult) {
        val start = System.currentTimeMillis()
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val factory = sslContext.socketFactory

            val sslSocket = factory.createSocket(rawSocket, rawSocket.inetAddress.hostAddress, rawSocket.port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(SNIHostName(sni))
            sslSocket.sslParameters = sslParams

            sslSocket.startHandshake()

            result.tlsTime = (System.currentTimeMillis() - start) / 1000.0
            result.tlsOk = true
            val session = sslSocket.session
            result.tlsVersion = session.protocol
            result.tlsCipher = session.cipherSuite

            val certs = session.peerCertificates
            if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                val x509 = certs[0] as X509Certificate
                result.certSubject = x509.subjectX500Principal.name
                result.certIssuer = x509.issuerX500Principal.name
                result.certNotBefore = x509.notBefore?.toString()
                result.certNotAfter = x509.notAfter?.toString()

                result.certSanList = try {
                    x509.subjectAlternativeNames?.filter { it[0] as Int == 2 }?.map { it[1] as String } ?: emptyList()
                } catch (_: Exception) { emptyList() }

                val cn = x509.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")
                val sanMatch = result.certSanList.any { san ->
                    sni.equals(san, ignoreCase = true) || (san.startsWith("*.") && sni.endsWith(san.substring(1)))
                }
                result.certSniMatch = sanMatch || sni.equals(cn, ignoreCase = true) || (cn.startsWith("*.") && sni.endsWith(cn.substring(1)))
            }

            sslSocket.close()
        } catch (e: Exception) {
            result.tlsOk = false
            result.errors.add("tls: ${e.message}")
        }
    }

    private fun _tlsVersionCheck(ip: String, sni: String, protocol: String, timeoutMs: Int, callback: (Boolean) -> Unit) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance(protocol)
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(SNIHostName(sni))
            sslSocket.sslParameters = sslParams
            sslSocket.enabledProtocols = arrayOf(protocol)

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(2500, timeoutMs))
            sslSocket.startHandshake()
            callback(true)
            sslSocket.close()
        } catch (e: Exception) {
            callback(false)
        }
    }

    private fun _checkH2Support(ip: String, sni: String, timeoutMs: Int, result: CheckResult) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(SNIHostName(sni))

            try { sslParams.applicationProtocols = arrayOf("h2", "http/1.1") } catch (e: Exception) {}

            sslSocket.sslParameters = sslParams
            sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(2500, timeoutMs))
            sslSocket.startHandshake()

            val negotiated = try { sslParams.applicationProtocols?.firstOrNull() } catch (e: Exception) { null }
            result.h2Supported = negotiated == "h2"
            sslSocket.close()
        } catch (e: Exception) {
            result.h2Supported = false
        }
    }

    private fun _combinedHttpCheck(ip: String, sni: String, timeoutMs: Int, result: CheckResult) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(SNIHostName(sni))
            sslSocket.sslParameters = sslParams
            sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { sslParams.applicationProtocols = arrayOf("h2", "http/1.1") } catch (_: Exception) {}
            }

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(3000, timeoutMs))
            sslSocket.startHandshake()

            val out = sslSocket.outputStream
            val inp = sslSocket.inputStream

            val getRequest = "GET / HTTP/1.1\r\n" +
                    "Host: $sni\r\n" +
                    "User-Agent: Mozilla/5.0 SNI-Pinger/1.0\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: keep-alive\r\n\r\n"

            out.write(getRequest.toByteArray())
            out.flush()

            val reader = inp.bufferedReader()
            val getResponse = _readHttpHeaders(reader)
            if (getResponse != null) {
                result.httpStatusLine = getResponse.status
                result.httpStatusCode = getResponse.code
                result.httpServerHeader = getResponse.headers["server"]
                result.httpRedirectLocation = getResponse.headers["location"]
                result.httpOk = true

                getResponse.contentLength?.let { length ->
                    val buf = ByteArray(minOf(length.toInt(), 65536))
                    var totalRead = 0
                    while (totalRead < length) {
                        val read = inp.read(buf, totalRead, minOf(buf.size - totalRead, (length - totalRead).toInt()))
                        if (read <= 0) break
                        totalRead += read
                    }
                } ?: run {
                    Thread.sleep(100)
                }

                val headRequest = "HEAD / HTTP/1.1\r\n" +
                        "Host: $sni\r\n" +
                        "User-Agent: Mozilla/5.0 SNI-Pinger/1.0\r\n" +
                        "Connection: close\r\n\r\n"

                out.write(headRequest.toByteArray())
                out.flush()

                val headResponse = _readHttpHeaders(reader)
                result.httpHeadOk = headResponse != null
            }

            sslSocket.close()
        } catch (e: Exception) {
            result.errors.add("http: ${e.message}")
        }
    }

    private data class HttpResponse(val status: String, val code: Int?, val headers: Map<String, String>, val contentLength: Long?)

    private fun _readHttpHeaders(reader: java.io.BufferedReader): HttpResponse? {
        val statusLine = reader.readLine() ?: return null
        val parts = statusLine.split(" ")
        val code = if (parts.size >= 2) parts[1].toIntOrNull() else null

        val headers = mutableMapOf<String, String>()
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            val colonIdx = line!!.indexOf(':')
            if (colonIdx > 0) {
                val key = line!!.substring(0, colonIdx).trim().lowercase()
                val value = line!!.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        val contentLength = headers["content-length"]?.toLongOrNull()
        return HttpResponse(statusLine, code, headers, contentLength)
    }

    private fun _makeVerdict(r: CheckResult) {
        if (r.tcpReachable == false) {
            r.inWhitelist = false
            r.verdict = "VERDICT_BLOCKED_TCP"
        } else if (r.tcpReachable == true && r.tlsOk == false) {
            r.inWhitelist = null
            r.verdict = "VERDICT_UNCERTAIN_DPI"
        } else if (r.tlsOk == true && r.httpStatusCode != null) {
            r.inWhitelist = true
            r.verdict = "VERDICT_OK_FULL"
        } else if (r.tlsOk == true) {
            r.inWhitelist = true
            r.verdict = "VERDICT_OK_TLS"
        } else {
            r.inWhitelist = null
            r.verdict = "VERDICT_UNCERTAIN_DATA"
        }
    }
}
