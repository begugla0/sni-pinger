package com.begugla.snipinger

object CsvExporter {

    private val columns = listOf(
        "ip", "sni", "port", "timeout",
        "ipVersion", "ipIsPrivate", "ipIsGlobal",
        "geo_source", "geo_org", "geo_asn", "geo_city", "geo_region",
        "geo_country", "geo_postal", "geo_timezone", "geo_anycast", "geo_hostname",
        "domainOwnerOrg", "domainResolvedIps",
        "dnsResolvesTo", "dnsResolveTime", "ipMatchesDns",
        "dnsFromGoogle", "dnsFromCloudflare", "dnsConsistent", "dnsPoisoned",
        "reverseDns",
        "tcpReachable", "tcpConnectTime", "tcpBlockType", "rttMs",
        "tcp80", "tcp8443", "tcp53", "tcp8080", "tcp22", "tcp25", "tcp465",
        "tlsOk", "tlsTime", "tlsVersion", "tlsCipher",
        "certSubject", "certIssuer", "certSniMatch",
        "certNotBefore", "certNotAfter", "certSanList",
        "certDaysToExpiry", "certIsSelfSigned",
        "tls10Ok", "tls11Ok", "tls12Ok", "tls13Ok", "h2Supported",
        "tlsWithoutSniOk", "sniDpiBlocked",
        "httpStatusCode", "httpStatusLine", "httpServerHeader",
        "httpRedirectLocation", "httpTime", "httpOk", "httpHeadOk",
        "hstsMaxAge", "xFrameOptions", "altSvc", "xPoweredBy", "cdnProvider",
        "icmpPing", "icmpLoss",
        "inWhitelist", "verdict", "errors", "totalTime"
    )

    fun toCsv(results: List<CheckResult>): String {
        val sb = StringBuilder()
        sb.append(columns.joinToString(","))
        sb.append("\n")
        results.forEach { r ->
            sb.append(row(r))
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun row(r: CheckResult): String {
        val geo = r.ipGeoInfo
        val cells = listOf(
            r.ip, r.sni, r.port, r.timeout,
            r.ipVersion, r.ipIsPrivate, r.ipIsGlobal,
            geo?.source, geo?.org, geo?.asn, geo?.city, geo?.region,
            geo?.country, geo?.postalCode, geo?.timezone, geo?.anycast, geo?.hostname,
            r.domainOwnerOrg, r.domainResolvedIps.joinToString("|"),
            r.dnsResolvesTo.joinToString("|"), r.dnsResolveTime, r.ipMatchesDns,
            r.dnsFromGoogle.joinToString("|"), r.dnsFromCloudflare.joinToString("|"),
            r.dnsConsistent, r.dnsPoisoned,
            r.reverseDns,
            r.tcpReachable, r.tcpConnectTime, r.tcpBlockType, r.rttMs,
            r.tcp80Reachable, r.tcp8443Reachable, r.tcp53Reachable,
            r.tcp8080Reachable, r.tcp22Reachable, r.tcp25Reachable, r.tcp465Reachable,
            r.tlsOk, r.tlsTime, r.tlsVersion, r.tlsCipher,
            r.certSubject, r.certIssuer, r.certSniMatch,
            r.certNotBefore, r.certNotAfter, r.certSanList.joinToString("|"),
            r.certDaysToExpiry, r.certIsSelfSigned,
            r.tls10Ok, r.tls11Ok, r.tls12Ok, r.tls13Ok, r.h2Supported,
            r.tlsWithoutSniOk, r.sniDpiBlocked,
            r.httpStatusCode, r.httpStatusLine, r.httpServerHeader,
            r.httpRedirectLocation, r.httpTime, r.httpOk, r.httpHeadOk,
            r.hstsMaxAge, r.xFrameOptions, r.altSvc, r.xPoweredBy, r.cdnProvider,
            r.icmpPing, r.icmpLoss,
            r.inWhitelist, r.verdict, r.errors.joinToString("|"),
            r.totalTime
        )
        return cells.joinToString(",") { escape(it) }
    }

    private fun escape(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }
}
