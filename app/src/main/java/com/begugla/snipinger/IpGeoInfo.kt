package com.begugla.snipinger

import java.net.URL

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
    val readme: String? = null,
    val rawJson: String? = null
) {
    companion object {
        fun parse(json: String, fallbackIp: String): IpGeoInfo {
            fun extractStr(key: String): String? {
                try {
                    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
                    val match = regex.find(json) ?: return null
                    return match.groupValues[1]
                } catch (_: Exception) {
                    return null
                }
            }

            fun extractBool(key: String): Boolean? {
                try {
                    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
                    val match = regex.find(json) ?: return null
                    return match.groupValues[1].toBoolean()
                } catch (_: Exception) {
                    return null
                }
            }

            return IpGeoInfo(
                ip = extractStr("ip") ?: fallbackIp,
                hostname = extractStr("hostname"),
                city = extractStr("city"),
                region = extractStr("region"),
                country = extractStr("country"),
                countryCode = extractStr("country"),
                org = extractStr("org"),
                asn = extractStr("org")?.substringBefore(' '),
                postalCode = extractStr("postal"),
                timezone = extractStr("timezone"),
                anycast = extractBool("anycast"),
                readme = extractStr("readme"),
                rawJson = json
            )
        }
    }
}

object IpInfoChecker {
    fun getIpInfo(ip: String): IpGeoInfo? {
        return try {
            val json = URL("https://ipinfo.io/$ip/json").readText()
            IpGeoInfo.parse(json, ip)
        } catch (e: Exception) {
            null
        }
    }
    
    fun getDomainIps(domain: String): List<String> {
        return try {
            java.net.InetAddress.getAllByName(domain).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
