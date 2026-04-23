package com.begugla.snipinger

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL
import android.os.Build
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.*
import kotlinx.coroutines.*

class WhitelistChecker {

    fun checkIp(
        inputIp: String,
        sni: String,
        timeout: Int = 5
    ): CheckResult {
        val ip = try {
            InetAddress.getByName(inputIp).hostAddress ?: inputIp
        } catch (e: Exception) {
            inputIp
        }

        val result = CheckResult(ip = ip, sni = sni, port = 443, timeout = timeout.toFloat())
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeout * 1000
        val shortMs = minOf(2500, timeoutMs)

        try {
            // ── Batch 1: parallel pre-checks ────────────────────────────────
            runBlocking {
                val geoJob      = async(Dispatchers.IO) { _ipGeoInfo(ip, result) }
                val domainJob   = async(Dispatchers.IO) { _sniOwnership(sni, result) }
                val dnsJob      = async(Dispatchers.IO) { _dnsCheck(sni, ip, result) }
                val pingJob     = async(Dispatchers.IO) { _approximatePing(ip, shortMs, result) }
                val portsJob    = async(Dispatchers.IO) { _tcpPorts(ip, shortMs, result) }
                val rdnsJob     = async(Dispatchers.IO) { _reverseDns(ip, result) }
                val multiDnsJob = async(Dispatchers.IO) { _multiDnsCheck(sni, result) }
                awaitAll(geoJob, domainJob, dnsJob, pingJob, portsJob, rdnsJob, multiDnsJob)
            }

            // ── TCP + TLS ────────────────────────────────────────────────────
            val socket = _connectSocket(ip, 443, timeoutMs, result)

            if (socket != null) {
                _fullTlsHandshake(socket, sni, timeoutMs, result)
                _certDerivedFields(result)

                // ── Batch 2: parallel post-TLS ──────────────────────────────
                runBlocking {
                    val tls10Job = async(Dispatchers.IO) {
                        _tlsVersionCheck(ip, sni, "TLSv1",   shortMs) { result.tls10Ok = it }
                    }
                    val tls11Job = async(Dispatchers.IO) {
                        _tlsVersionCheck(ip, sni, "TLSv1.1", shortMs) { result.tls11Ok = it }
                    }
                    val tls12Job = async(Dispatchers.IO) {
                        _tlsVersionCheck(ip, sni, "TLSv1.2", shortMs) { result.tls12Ok = it }
                    }
                    val tls13Job = async(Dispatchers.IO) {
                        _tlsVersionCheck(ip, sni, "TLSv1.3", shortMs) { result.tls13Ok = it }
                    }
                    val h2Job = async(Dispatchers.IO) {
                        if (Build.VERSION.SDK_INT >= 29) _checkH2Support(ip, sni, shortMs, result)
                    }
                    val httpJob   = async(Dispatchers.IO) { _combinedHttpCheck(ip, sni, timeoutMs, result) }
                    val dpiJob    = async(Dispatchers.IO) { _sniVsNoSniCheck(ip, shortMs, result) }
                    awaitAll(tls10Job, tls11Job, tls12Job, tls13Job, h2Job, httpJob, dpiJob)
                }
            } else {
                // TCP blocked — still probe no-SNI to detect partial DPI
                _sniVsNoSniCheck(ip, shortMs, result)
            }

        } catch (e: Exception) {
            result.errors.add("General: ${e.message}")
        }

        _makeVerdict(result)
        result.totalTime = (System.currentTimeMillis() - startTime) / 1000.0
        return result
    }

    // ── IP info ──────────────────────────────────────────────────────────────

    private fun _ipInfo(ip: String, result: CheckResult) {
        try {
            val addr = InetAddress.getByName(ip)
            result.ipVersion   = if (addr.address.size == 4) 4 else 6
            result.ipIsPrivate = addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
            result.ipIsGlobal  = !result.ipIsPrivate!!
        } catch (e: Exception) {
            result.errors.add("ip_info: ${e.message}")
        }
    }

    private fun _ipGeoInfo(ip: String, result: CheckResult) {
        _ipInfo(ip, result)
        try {
            val info = IpInfoChecker.getIpInfo(ip)
            if (info != null) result.ipGeoInfo = info
        } catch (_: Exception) {}
    }

    private fun _sniOwnership(sni: String, result: CheckResult) {
        try {
            val domainIps = IpInfoChecker.getDomainIps(sni)
            result.domainResolvedIps = domainIps
            if (domainIps.isNotEmpty()) {
                val geo = IpInfoChecker.getIpInfo(domainIps.first())
                if (geo?.org != null) result.domainOwnerOrg = geo.org
            }
        } catch (_: Exception) {}
    }

    // ── Reverse DNS ──────────────────────────────────────────────────────────

    private fun _reverseDns(ip: String, result: CheckResult) {
        try {
            val hostname = InetAddress.getByName(ip).canonicalHostName
            if (hostname != ip) result.reverseDns = hostname
        } catch (_: Exception) {}
    }

    // ── DNS ──────────────────────────────────────────────────────────────────

    private fun _dnsCheck(sni: String, ip: String, result: CheckResult) {
        val start = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName(sni)
            result.dnsResolveTime = (System.currentTimeMillis() - start) / 1000.0
            result.dnsResolvesTo  = addresses.mapNotNull { it.hostAddress }
            result.ipMatchesDns   = result.dnsResolvesTo.contains(ip)
        } catch (e: Exception) {
            result.errors.add("dns: ${e.message}")
        }
    }

    /**
     * Queries Google (8.8.8.8) and Cloudflare (1.1.1.1) via DNS-over-HTTPS
     * and compares results with the system resolver to detect DNS poisoning.
     */
    private fun _multiDnsCheck(sni: String, result: CheckResult) {
        result.dnsFromGoogle     = _queryDoH(sni, "https://dns.google/resolve?name=$sni&type=A")
        result.dnsFromCloudflare = _queryDoH(sni, "https://cloudflare-dns.com/dns-query?name=$sni&type=A")

        val sets = listOf(result.dnsResolvesTo, result.dnsFromGoogle, result.dnsFromCloudflare)
            .filter { it.isNotEmpty() }
        if (sets.size >= 2) {
            result.dnsConsistent = sets.all { it.toSet() == sets[0].toSet() }
            // Poisoning heuristic: system disagrees with BOTH external resolvers that agree with each other
            val externalAgree = result.dnsFromGoogle.isNotEmpty() &&
                    result.dnsFromCloudflare.isNotEmpty() &&
                    result.dnsFromGoogle.toSet() == result.dnsFromCloudflare.toSet()
            val systemDiffers = result.dnsResolvesTo.isNotEmpty() &&
                    result.dnsResolvesTo.toSet() != result.dnsFromGoogle.toSet()
            result.dnsPoisoned = externalAgree && systemDiffers
        }
    }

    private fun _queryDoH(domain: String, url: String): List<String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout    = 3000
            conn.setRequestProperty("Accept", "application/dns-json")
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // Extract "data":"x.x.x.x" from Answer array (A records)
            Regex("\"data\"\\s*:\\s*\"([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\"")
                .findAll(json).map { it.groupValues[1] }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── TCP ──────────────────────────────────────────────────────────────────

    private fun _approximatePing(ip: String, timeoutMs: Int, result: CheckResult) {
        try {
            val socket = Socket()
            val start  = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, 443), minOf(1500, timeoutMs))
            result.rttMs = (System.currentTimeMillis() - start).toDouble()
            socket.close()
        } catch (_: Exception) {}
    }

    private fun _tcpPorts(ip: String, timeoutMs: Int, result: CheckResult) {
        runBlocking {
            val t = minOf(1500, timeoutMs)
            val j80   = async(Dispatchers.IO) { checkPort(ip, 80,   t) { ok, ms -> result.tcp80Reachable = ok; result.tcp80ConnectTime = ms } }
            val j8443 = async(Dispatchers.IO) { checkPort(ip, 8443, t) { ok, _  -> result.tcp8443Reachable = ok } }
            val j8080 = async(Dispatchers.IO) { checkPort(ip, 8080, t) { ok, _  -> result.tcp8080Reachable = ok } }
            val j53   = async(Dispatchers.IO) { checkPort(ip, 53,   t) { ok, _  -> result.tcp53Reachable = ok } }
            val j22   = async(Dispatchers.IO) { checkPort(ip, 22,   t) { ok, _  -> result.tcp22Reachable = ok } }
            val j25   = async(Dispatchers.IO) { checkPort(ip, 25,   t) { ok, _  -> result.tcp25Reachable = ok } }
            val j465  = async(Dispatchers.IO) { checkPort(ip, 465,  t) { ok, _  -> result.tcp465Reachable = ok } }
            awaitAll(j80, j8443, j8080, j53, j22, j25, j465)
        }
    }

    private fun checkPort(ip: String, port: Int, timeoutMs: Int, callback: (Boolean, Double?) -> Unit) {
        try {
            val socket = Socket()
            val start  = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            callback(true, (System.currentTimeMillis() - start) / 1000.0)
        } catch (_: Exception) {
            callback(false, null)
        }
    }

    private fun _connectSocket(ip: String, port: Int, timeoutMs: Int, result: CheckResult): Socket? {
        val socket = Socket()
        val start  = System.currentTimeMillis()
        return try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            result.tcpConnectTime = (System.currentTimeMillis() - start) / 1000.0
            result.tcpReachable   = true
            if (result.rttMs == null) result.rttMs = result.tcpConnectTime!! * 1000.0
            socket
        } catch (e: ConnectException) {
            // ECONNREFUSED → TCP RST from peer or firewall (active block)
            result.tcpReachable = false
            result.tcpBlockType = "RST"
            result.errors.add("tcp443: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            null
        } catch (e: SocketTimeoutException) {
            // No response within timeout → packet drop (passive block)
            result.tcpReachable = false
            result.tcpBlockType = "TIMEOUT"
            result.errors.add("tcp443: timeout")
            try { socket.close() } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            result.tcpReachable = false
            result.errors.add("tcp443: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            null
        }
    }

    // ── TLS ──────────────────────────────────────────────────────────────────

    private fun _fullTlsHandshake(rawSocket: Socket, sni: String, timeoutMs: Int, result: CheckResult) {
        val start = System.currentTimeMillis()
        try {
            val sslContext = _trustAllContext("TLS")
            val sslSocket  = sslContext.socketFactory
                .createSocket(rawSocket, rawSocket.inetAddress.hostAddress, rawSocket.port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            sslSocket.sslParameters = params
            sslSocket.startHandshake()

            result.tlsTime    = (System.currentTimeMillis() - start) / 1000.0
            result.tlsOk      = true
            val session       = sslSocket.session
            result.tlsVersion = session.protocol
            result.tlsCipher  = session.cipherSuite

            val certs = session.peerCertificates
            if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                val x509 = certs[0] as X509Certificate
                result.certSubject   = x509.subjectX500Principal.name
                result.certIssuer    = x509.issuerX500Principal.name
                result.certNotBefore = x509.notBefore?.toString()
                result.certNotAfter  = x509.notAfter?.toString()
                result.certSanList   = try {
                    x509.subjectAlternativeNames
                        ?.filter { it[0] as Int == 2 }
                        ?.map { it[1] as String } ?: emptyList()
                } catch (_: Exception) { emptyList() }

                val cn = x509.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")
                result.certSniMatch = result.certSanList.any { san ->
                    sni.equals(san, ignoreCase = true) ||
                    (san.startsWith("*.") && sni.endsWith(san.substring(1)))
                } || sni.equals(cn, ignoreCase = true) ||
                    (cn.startsWith("*.") && sni.endsWith(cn.substring(1)))
            }
            sslSocket.close()
        } catch (e: Exception) {
            result.tlsOk = false
            result.errors.add("tls: ${e.message}")
        }
    }

    /** Computes derived cert fields that require the raw Date strings already stored. */
    private fun _certDerivedFields(result: CheckResult) {
        result.certNotAfter?.let { raw ->
            try {
                val fmt = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
                val exp = fmt.parse(raw)
                if (exp != null) result.certDaysToExpiry =
                    (exp.time - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)
            } catch (_: Exception) {}
        }
        if (result.certSubject != null && result.certIssuer != null) {
            result.certIsSelfSigned = result.certSubject == result.certIssuer
        }
    }

    private fun _tlsVersionCheck(
        ip: String, sni: String, protocol: String, timeoutMs: Int,
        callback: (Boolean) -> Unit
    ) {
        try {
            val sslContext = _trustAllContext("TLS")
            val sslSocket  = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            // Check device supports this protocol before attempting
            val supported = sslSocket.supportedProtocols
            if (!supported.contains(protocol)) { callback(false); sslSocket.close(); return }

            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            sslSocket.sslParameters = params
            sslSocket.enabledProtocols = arrayOf(protocol)

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(2500, timeoutMs))
            sslSocket.startHandshake()
            callback(true)
            sslSocket.close()
        } catch (_: Exception) {
            callback(false)
        }
    }

    /**
     * Connects WITHOUT sending an SNI extension.
     * If this succeeds while normal TLS (with SNI) fails, it's a strong
     * indicator that a DPI device is selectively blocking by SNI hostname.
     */
    private fun _sniVsNoSniCheck(ip: String, timeoutMs: Int, result: CheckResult) {
        // Short-circuit: if TLS with SNI already works, no DPI blocking
        if (result.tlsOk == true) {
            result.sniDpiBlocked = false
            return
        }
        // TCP must at least reach the server
        if (result.tcpReachable != true) return

        try {
            val sslContext = _trustAllContext("TLS")
            val sslSocket  = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            // Explicitly clear server names → no SNI in ClientHello
            val params = sslSocket.sslParameters
            params.serverNames = emptyList()
            sslSocket.sslParameters = params

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(3000, timeoutMs))
            sslSocket.startHandshake()
            result.tlsWithoutSniOk = true
            sslSocket.close()
        } catch (_: Exception) {
            result.tlsWithoutSniOk = false
        }

        // DPI blocks SNI: no-SNI succeeds but SNI-carrying handshake fails
        result.sniDpiBlocked = result.tlsWithoutSniOk == true && result.tlsOk != true
    }

    private fun _checkH2Support(ip: String, sni: String, timeoutMs: Int, result: CheckResult) {
        try {
            val sslContext = _trustAllContext("TLS")
            val sslSocket  = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            try { params.applicationProtocols = arrayOf("h2", "http/1.1") } catch (_: Exception) {}
            sslSocket.sslParameters = params
            sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(2500, timeoutMs))
            sslSocket.startHandshake()

            val negotiated = try { sslSocket.sslParameters.applicationProtocols?.firstOrNull() }
                             catch (_: Exception) { null }
            result.h2Supported = negotiated == "h2"
            sslSocket.close()
        } catch (_: Exception) {
            result.h2Supported = false
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun _combinedHttpCheck(ip: String, sni: String, timeoutMs: Int, result: CheckResult) {
        try {
            val sslContext = _trustAllContext("TLS")
            val sslSocket  = sslContext.socketFactory.createSocket() as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { params.applicationProtocols = arrayOf("h2", "http/1.1") } catch (_: Exception) {}
            }
            sslSocket.sslParameters = params
            sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")

            sslSocket.connect(InetSocketAddress(ip, 443), minOf(3000, timeoutMs))
            sslSocket.startHandshake()

            val out    = sslSocket.outputStream
            val inp    = sslSocket.inputStream
            val reader = inp.bufferedReader()

            val getReq = "GET / HTTP/1.1\r\n" +
                    "Host: $sni\r\n" +
                    "User-Agent: Mozilla/5.0 SNI-Pinger/1.0\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: keep-alive\r\n\r\n"
            out.write(getReq.toByteArray()); out.flush()

            val resp = _readHttpHeaders(reader)
            if (resp != null) {
                result.httpStatusLine    = resp.status
                result.httpStatusCode    = resp.code
                result.httpServerHeader  = resp.headers["server"]
                result.httpRedirectLocation = resp.headers["location"]
                result.xPoweredBy        = resp.headers["x-powered-by"]
                result.altSvc            = resp.headers["alt-svc"]
                result.xFrameOptions     = resp.headers["x-frame-options"]
                result.httpOk            = true

                // HSTS
                resp.headers["strict-transport-security"]?.let { hsts ->
                    result.hstsMaxAge = Regex("max-age=(\\d+)").find(hsts)
                        ?.groupValues?.get(1)?.toLongOrNull()
                }

                // CDN detection from response headers
                result.cdnProvider = detectCdn(resp.headers, resp.headers["server"] ?: "")

                // Drain body before HEAD
                resp.contentLength?.let { len ->
                    val buf = ByteArray(minOf(len.toInt(), 65536))
                    var read = 0
                    while (read < len) {
                        val n = inp.read(buf, read, minOf(buf.size - read, (len - read).toInt()))
                        if (n <= 0) break; read += n
                    }
                } ?: Thread.sleep(100)

                val headReq = "HEAD / HTTP/1.1\r\n" +
                        "Host: $sni\r\n" +
                        "User-Agent: Mozilla/5.0 SNI-Pinger/1.0\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(headReq.toByteArray()); out.flush()
                result.httpHeadOk = _readHttpHeaders(reader) != null
            }
            sslSocket.close()
        } catch (e: Exception) {
            result.errors.add("http: ${e.message}")
        }
    }

    private fun detectCdn(headers: Map<String, String>, serverHeader: String): String? {
        val cdnMap = linkedMapOf(
            "cf-ray"               to "Cloudflare",
            "x-amz-cf-id"         to "CloudFront",
            "x-fastly-request-id" to "Fastly",
            "x-served-by"         to "Fastly",
            "x-akamai-transformed" to "Akamai",
            "x-vercel-id"         to "Vercel",
            "fly-request-id"      to "Fly.io",
            "x-cdn"               to "CDN",
            "x-cache"             to "CDN (cached)"
        )
        for ((header, name) in cdnMap) {
            if (headers.containsKey(header)) return name
        }
        val srv = serverHeader.lowercase()
        return when {
            "cloudflare" in srv -> "Cloudflare"
            "akamai"     in srv -> "Akamai"
            "fastly"     in srv -> "Fastly"
            else                -> null
        }
    }

    private data class HttpResponse(
        val status: String,
        val code: Int?,
        val headers: Map<String, String>,
        val contentLength: Long?
    )

    private fun _readHttpHeaders(reader: java.io.BufferedReader): HttpResponse? {
        val statusLine = reader.readLine() ?: return null
        val parts      = statusLine.split(" ")
        val code       = if (parts.size >= 2) parts[1].toIntOrNull() else null

        val headers = mutableMapOf<String, String>()
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            val idx = line!!.indexOf(':')
            if (idx > 0) {
                headers[line!!.substring(0, idx).trim().lowercase()] =
                    line!!.substring(idx + 1).trim()
            }
        }
        return HttpResponse(statusLine, code, headers, headers["content-length"]?.toLongOrNull())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns an SSLContext that trusts all certificates (for diagnostics). */
    private fun _trustAllContext(protocol: String): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance(protocol)
        ctx.init(null, trustAll, java.security.SecureRandom())
        return ctx
    }

    // ── Verdict ──────────────────────────────────────────────────────────────

    private fun _makeVerdict(r: CheckResult) {
        r.verdict = when {
            r.tcpReachable == false          -> "VERDICT_BLOCKED_TCP"
            r.sniDpiBlocked == true          -> "VERDICT_UNCERTAIN_DPI"
            r.tlsOk == false                 -> "VERDICT_UNCERTAIN_DPI"
            r.tlsOk == true && r.httpStatusCode != null -> "VERDICT_OK_FULL"
            r.tlsOk == true                  -> "VERDICT_OK_TLS"
            else                             -> "VERDICT_UNCERTAIN_DATA"
        }
        r.inWhitelist = when (r.verdict) {
            "VERDICT_OK_FULL", "VERDICT_OK_TLS" -> true
            "VERDICT_BLOCKED_TCP"               -> false
            else                                -> null
        }
    }
}