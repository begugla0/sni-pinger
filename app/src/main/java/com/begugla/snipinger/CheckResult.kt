package com.begugla.snipinger

data class CheckResult(
    val ip: String,
    val sni: String,
    val port: Int,
    val timeout: Float,

    // IP-info
    var ipVersion: Int? = null,
    var ipIsPrivate: Boolean? = null,
    var ipIsGlobal: Boolean? = null,

    // Geo IP (ipinfo.io)
    var ipGeoInfo: IpGeoInfo? = null,

    // SNI ownership
    var domainOwnerOrg: String? = null,
    var domainResolvedIps: List<String> = emptyList(),

    // DNS
    var dnsResolvesTo: List<String> = emptyList(),
    var dnsResolveTime: Double? = null,
    var ipMatchesDns: Boolean? = null,

    // TCP
    var tcpReachable: Boolean? = null,
    var tcpConnectTime: Double? = null,
    var rttMs: Double? = null,

    // Additional TCP checks
    var tcp80Reachable: Boolean? = null,
    var tcp80ConnectTime: Double? = null,
    var tcp53Reachable: Boolean? = null,
    var tcp8080Reachable: Boolean? = null,

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

    // Additional TLS checks
    var tls12Ok: Boolean? = null,
    var tls13Ok: Boolean? = null,
    var h2Supported: Boolean? = null,

    // HTTP
    var httpStatusCode: Int? = null,
    var httpStatusLine: String? = null,
    var httpServerHeader: String? = null,
    var httpRedirectLocation: String? = null,
    var httpTime: Double? = null,

    // Additional HTTP checks
    var httpOk: Boolean? = null,
    var httpHeadOk: Boolean? = null,
    var httpRobotsTxt: String? = null,

    // ICMP / Ping
    var icmpPing: Double? = null,
    var icmpLoss: Double? = null,

    // Итог
    var inWhitelist: Boolean? = null, // True / False / null
    var verdict: String = "",
    val errors: MutableList<String> = mutableListOf(),
    var totalTime: Double? = null
)
