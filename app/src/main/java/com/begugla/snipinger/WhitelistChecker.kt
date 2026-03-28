package com.begugla.snipinger

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.*
import kotlin.system.measureTimeMillis

class WhitelistChecker {

    private val TAG = "WhitelistChecker"

    fun checkIp(
        ip: String,
        sni: String,
        timeout: Int = 5000,
        port: Int = 443,
        checkDns: Boolean = true
    ): CheckResult {
        val result = CheckResult(ip = ip, sni = sni, port = port, timeout = timeout / 1000f)
        val startTime = System.currentTimeMillis()

        try {
            // Этап 1: IP Info
            _ipInfo(ip, result)

            // Этап 2: DNS
            if (checkDns) {
                _dnsCheck(sni, ip, result)
            }

            // Этап 3: TCP
            val socket = _tcpConnect(ip, port, timeout, result)

            if (socket != null) {
                // Этап 4: TLS
                val tlsSocket = _tlsHandshake(socket, sni, timeout, result)
                if (tlsSocket != null) {
                    // Этап 5: HTTP
                    _httpRequest(tlsSocket, sni, timeout, result)
                    try { tlsSocket.close() } catch (e: Exception) {}
                } else {
                    try { socket.close() } catch (e: Exception) {}
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

    private fun _dnsCheck(sni: String, ip: String, result: CheckResult) {
        val start = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName(sni)
            result.dnsResolveTime = (System.currentTimeMillis() - start) / 1000.0
            val resolved = addresses.map { it.hostAddress }
            result.dnsResolvesTo = resolved
            result.ipMatchesDns = resolved.contains(ip)
        } catch (e: Exception) {
            result.errors.add("dns: ${e.message}")
        }
    }

    private fun _tcpConnect(ip: String, port: Int, timeout: Int, result: CheckResult): Socket? {
        val socket = Socket()
        val start = System.currentTimeMillis()
        return try {
            socket.connect(InetSocketAddress(ip, port), timeout)
            result.tcpConnectTime = (System.currentTimeMillis() - start) / 1000.0
            result.tcpReachable = true
            result.rttMs = result.tcpConnectTime!! * 1000.0
            socket
        } catch (e: Exception) {
            result.tcpReachable = false
            result.errors.add("tcp: ${e.message}")
            try { socket.close() } catch (ex: Exception) {}
            null
        }
    }

    private fun _tlsHandshake(rawSocket: Socket, sni: String, timeout: Int, result: CheckResult): SSLSocket? {
        val start = System.currentTimeMillis()
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val factory = sslContext.socketFactory
            
            val sslSocket = factory.createSocket(rawSocket, rawSocket.inetAddress.hostAddress, rawSocket.port, true) as SSLSocket
            sslSocket.soTimeout = timeout
            
            // Set SNI
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
                
                // Simplified SNI match check
                val cn = x509.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")
                result.certSniMatch = sni.equals(cn, ignoreCase = true) || (cn.startsWith("*.") && sni.endsWith(cn.substring(1)))
            }
            
            sslSocket
        } catch (e: Exception) {
            result.tlsOk = false
            result.errors.add("tls: ${e.message}")
            null
        }
    }

    private fun _httpRequest(tlsSocket: SSLSocket, sni: String, timeout: Int, result: CheckResult) {
        try {
            val output = tlsSocket.outputStream
            val input = tlsSocket.inputStream
            
            val request = "GET / HTTP/1.1\r\n" +
                    "Host: $sni\r\n" +
                    "User-Agent: Mozilla/5.0 (compatible; whitelist-checker/1.0)\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
            
            output.write(request.toByteArray())
            output.flush()
            
            val reader = input.bufferedReader()
            val statusLine = reader.readLine()
            if (statusLine != null) {
                result.httpStatusLine = statusLine
                val parts = statusLine.split(" ")
                if (parts.size >= 2) {
                    result.httpStatusCode = parts[1].toIntOrNull()
                }
                
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val low = line!!.lowercase()
                    if (low.startsWith("server:")) {
                        result.httpServerHeader = line!!.substringAfter(":").trim()
                    } else if (low.startsWith("location:")) {
                        result.httpRedirectLocation = line!!.substringAfter(":").trim()
                    }
                }
            }
        } catch (e: Exception) {
            result.errors.add("http: ${e.message}")
        }
    }

    private fun _makeVerdict(r: CheckResult) {
        if (r.tcpReachable == false) {
            r.inWhitelist = false
            r.verdict = "❌ НЕ В БЕЛОМ СПИСКЕ — TCP заблокирован ТСПУ"
        } else if (r.tcpReachable == true && r.tlsOk == false) {
            r.inWhitelist = null
            r.verdict = "⚠️ НЕОДНОЗНАЧНО — TCP проходит, TLS не удался (порт закрыт или DPI на уровне TLS)"
        } else if (r.tlsOk == true && r.httpStatusCode != null) {
            r.inWhitelist = true
            r.verdict = "✅ В БЕЛОМ СПИСКЕ — TCP + TLS + HTTP работают"
        } else if (r.tlsOk == true) {
            r.inWhitelist = true
            r.verdict = "✅ ВЕРОЯТНО В БЕЛОМ СПИСКЕ — TCP + TLS прошли"
        } else {
            r.inWhitelist = null
            r.verdict = "⚠️ НЕОДНОЗНАЧНО — недостаточно данных"
        }
    }
}
