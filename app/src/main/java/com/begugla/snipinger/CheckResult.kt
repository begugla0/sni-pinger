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

    // DNS
    var dnsResolvesTo: List<String> = emptyList(),
    var dnsResolveTime: Double? = null,
    var ipMatchesDns: Boolean? = null,

    // TCP
    var tcpReachable: Boolean? = null,
    var tcpConnectTime: Double? = null,
    var rttMs: Double? = null,

    // TLS
    var tlsOk: Boolean? = null,
    var tlsTime: Double? = null,
    var tlsVersion: String? = null,
    var tlsCipher: String? = null,
    var certSubject: String? = null,
    var certIssuer: String? = null,
    var certSniMatch: Boolean? = null,

    // HTTP
    var httpStatusCode: Int? = null,
    var httpStatusLine: String? = null,
    var httpServerHeader: String? = null,
    var httpRedirectLocation: String? = null,

    // Итог
    var inWhitelist: Boolean? = null, // True / False / null
    var verdict: String = "",
    val errors: MutableList<String> = mutableListOf(),
    var totalTime: Double? = null
)
