package com.begugla.snipinger

data class CheckResult(
    val ip: String,
    val sni: String,
    val port: Int,
    val timeout: Float,

    // IP info
    var ipVersion: Int? = null,
    var ipIsPrivate: Boolean? = null,
    var ipIsGlobal: Boolean? = null,

    // Geo IP (ipinfo.io)
    var ipGeoInfo: IpGeoInfo? = null,

    // SNI ownership / domain
    var domainOwnerOrg: String? = null,
    var domainResolvedIps: List<String> = emptyList(),

    // DNS — system resolver
    var dnsResolvesTo: List<String> = emptyList(),
    var dnsResolveTime: Double? = null,
    var ipMatchesDns: Boolean? = null,

    // DNS — multi-resolver comparison
    var dnsFromGoogle: List<String> = emptyList(),      // via DoH 8.8.8.8
    var dnsFromCloudflare: List<String> = emptyList(),  // via DoH 1.1.1.1
    var dnsConsistent: Boolean? = null,                 // all resolvers agree
    var dnsPoisoned: Boolean? = null,                   // suspicious disagreement

    // Reverse DNS (PTR)
    var reverseDns: String? = null,

    // TCP
    var tcpReachable: Boolean? = null,
    var tcpConnectTime: Double? = null,
    var tcpBlockType: String? = null,   // "RST" | "TIMEOUT" | null
    var rttMs: Double? = null,

    // Additional TCP ports
    var tcp80Reachable: Boolean? = null,
    var tcp80ConnectTime: Double? = null,
    var tcp8443Reachable: Boolean? = null,
    var tcp53Reachable: Boolean? = null,
    var tcp8080Reachable: Boolean? = null,
    var tcp22Reachable: Boolean? = null,
    var tcp25Reachable: Boolean? = null,
    var tcp465Reachable: Boolean? = null,

    // TLS
    var tlsOk: Boolean? = null,
    var tlsTime: Double? = null,
    var tlsVersion: String? = null,
    var tlsCipher: String? = null,
    var certSubject: String? = null,
    var certIssuer: String? = null,
    var certSniMatch: Boolean? = null,
    var certNotBefore: String? = null,
    var certNotAfter: String? = null,
    var certSanList: List<String> = emptyList(),
    var certDaysToExpiry: Long? = null,
    var certIsSelfSigned: Boolean? = null,

    // TLS version support
    var tls10Ok: Boolean? = null,
    var tls11Ok: Boolean? = null,
    var tls12Ok: Boolean? = null,
    var tls13Ok: Boolean? = null,
    var h2Supported: Boolean? = null,

    // SNI vs No-SNI (DPI detection)
    var tlsWithoutSniOk: Boolean? = null,
    var sniDpiBlocked: Boolean? = null,

    // HTTP
    var httpStatusCode: Int? = null,
    var httpStatusLine: String? = null,
    var httpServerHeader: String? = null,
    var httpRedirectLocation: String? = null,
    var httpTime: Double? = null,
    var httpOk: Boolean? = null,
    var httpHeadOk: Boolean? = null,
    var httpRobotsTxt: String? = null,

    // Security headers
    var hstsMaxAge: Long? = null,
    var xFrameOptions: String? = null,
    var altSvc: String? = null,
    var xPoweredBy: String? = null,
    var cdnProvider: String? = null,

    // ICMP
    var icmpPing: Double? = null,
    var icmpLoss: Double? = null,

    // Verdict
    var inWhitelist: Boolean? = null,
    var verdict: String = "",
    val errors: MutableList<String> = mutableListOf(),
    var totalTime: Double? = null
)