package com.begugla.snipinger

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Модель GeoIP данных
 */
data class IpGeoInfo(
    val ip: String = "",
    val hostname: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val org: String? = null,
    val asn: String? = null,
    val postalCode: String? = null,
    val timezone: String? = null,
    val anycast: Boolean? = null,
    val lat: String? = null,
    val lon: String? = null,
    val source: String = "unknown",
    val rawJson: String? = null
) {
    companion object {
        /**
         * Универсальный парсер. Понимает форматы: ipinfo, ip-api, ipapi.co, ipwhois, freeipapi.
         */
        fun parse(json: String, fallbackIp: String, sourceName: String = "unknown"): IpGeoInfo {
            fun extractStr(key: String): String? {
                try {
                    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
                    val match = regex.find(json) ?: return null
                    return match.groupValues[1]
                } catch (_: Exception) { return null }
            }

            fun extractBool(key: String): Boolean? {
                try {
                    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
                    val match = regex.find(json) ?: return null
                    return match.groupValues[1].toBoolean()
                } catch (_: Exception) { return null }
            }

            return when (sourceName) {
                "ipinfo" -> IpGeoInfo(
                    ip = extractStr("ip") ?: fallbackIp, hostname = extractStr("hostname"),
                    city = extractStr("city"), region = extractStr("region"),
                    country = extractStr("country"), countryCode = extractStr("country"),
                    org = extractStr("org"), asn = extractStr("org")?.substringBefore(' '),
                    postalCode = extractStr("postal"), timezone = extractStr("timezone"),
                    anycast = extractBool("anycast"), source = "ipinfo.io", rawJson = json
                )
                "ip-api" -> IpGeoInfo(
                    ip = extractStr("query") ?: fallbackIp, hostname = extractStr("reverse"),
                    city = extractStr("city"), region = extractStr("regionName"),
                    country = extractStr("country"), countryCode = extractStr("countryCode"),
                    org = extractStr("org"), asn = extractStr("as"),
                    postalCode = extractStr("zip"), timezone = extractStr("timezone"),
                    lat = extractStr("lat"), lon = extractStr("lon"),
                    source = "ip-api.com", rawJson = json
                )
                "ipapi.co" -> IpGeoInfo(
                    ip = extractStr("ip") ?: fallbackIp, hostname = extractStr("hostname"),
                    city = extractStr("city"), region = extractStr("region"),
                    country = extractStr("country_name"), countryCode = extractStr("country_code"),
                    org = extractStr("org"), asn = extractStr("asn"),
                    postalCode = extractStr("postal"), timezone = extractStr("timezone"),
                    lat = extractStr("latitude"), lon = extractStr("longitude"),
                    source = "ipapi.co", rawJson = json
                )
                "ipwhois" -> IpGeoInfo(
                    ip = extractStr("ip") ?: fallbackIp,
                    city = extractStr("city"), region = extractStr("region"),
                    country = extractStr("country"), countryCode = extractStr("country_code"),
                    org = extractStr("organisation") ?: extractStr("isp"),
                    asn = extractStr("asn"), postalCode = extractStr("postal_code"),
                    timezone = extractStr("timezone"),
                    lat = extractStr("latitude"), lon = extractStr("longitude"),
                    source = "ipwhois.app", rawJson = json
                )
                "freeipapi" -> IpGeoInfo(
                    ip = extractStr("ipAddress") ?: fallbackIp,
                    city = extractStr("cityName"), region = extractStr("regionName"),
                    country = extractStr("countryName"), countryCode = extractStr("countryCode"),
                    postalCode = extractStr("zipCode"),
                    lat = extractStr("latitude"), lon = extractStr("longitude"),
                    source = "freeipapi.com", rawJson = json
                )
                else -> IpGeoInfo(
                    ip = extractStr("ip") ?: extractStr("query") ?: extractStr("ipAddress") ?: fallbackIp,
                    hostname = extractStr("hostname") ?: extractStr("reverse"),
                    city = extractStr("city") ?: extractStr("cityName"),
                    region = extractStr("region") ?: extractStr("regionName"),
                    country = extractStr("country") ?: extractStr("countryName"),
                    countryCode = extractStr("countryCode") ?: extractStr("cc"),
                    org = extractStr("org") ?: extractStr("organisation") ?: extractStr("isp"),
                    asn = extractStr("asn") ?: extractStr("as"),
                    postalCode = extractStr("postal") ?: extractStr("postalCode") ?: extractStr("zip") ?: extractStr("zipCode"),
                    timezone = extractStr("timezone") ?: extractStr("time_zone"),
                    anycast = extractBool("anycast"),
                    lat = extractStr("latitude") ?: extractStr("lat"),
                    lon = extractStr("longitude") ?: extractStr("lon"),
                    source = sourceName, rawJson = json
                )
            }
        }
    }
}

/**
 * Менеджер GeoIP с кешированием и fallback-цепочкой.
 */
object IpInfoChecker {
    private const val TAG = "SNIpinger_Geo"
    private val cache = ConcurrentHashMap<String, IpGeoInfo?>()

    data class GeoProvider(val name: String, val urlTemplate: String, val useHttp: Boolean = false)

    private val providers = listOf(
        GeoProvider("ipinfo",    "https://ipinfo.io/%s/json"),
        GeoProvider("ipapi.co",  "https://ipapi.co/%s/json/"),
        GeoProvider("ipwhois",   "https://ipwhois.app/json/%s"),
        GeoProvider("freeipapi", "https://freeipapi.com/api/json/%s"),
        GeoProvider("ip-api",    "http://ip-api.com/json/%s", useHttp = true)
    )

    fun getIpInfo(ip: String): IpGeoInfo? {
        if (cache.containsKey(ip)) return cache[ip]

        for (provider in providers) {
            try {
                val info = tryFetch(provider, ip)
                if (info != null) {
                    Log.d(TAG, "✅ Geo lookup succeeded via ${provider.name} for $ip")
                    cache[ip] = info
                    return info
                }
            } catch (_: Exception) {
                // Ignored
            }
        }
        
        Log.e(TAG, "❌ All GeoIP providers failed for $ip")
        cache[ip] = null
        return null
    }

    private fun tryFetch(provider: GeoProvider, ip: String): IpGeoInfo? {
        var connection: HttpURLConnection? = null
        try {
            val urlStr = String.format(provider.urlTemplate, ip)
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "SNI-Pinger-Android/1.0")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == 429) {
                Log.w(TAG, "⚠️ ${provider.name} RATE LIMITED (429)")
                return null
            }

            if (connection.responseCode == 200) {
                val json = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                if (json.isBlank() || json == "null" || json == "{}") return null
                if (provider.name == "ip-api" && json.contains("\"status\":\"fail\"")) return null
                return IpGeoInfo.parse(json, ip, provider.name)
            }
        } finally {
            connection?.disconnect()
        }
        return null
    }

    fun getDomainIps(domain: String): List<String> {
        return try {
            InetAddress.getAllByName(domain).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
