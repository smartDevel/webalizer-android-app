package com.smartdev.webalizer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class IpDetails(
    val address: String,
    val addressType: String?,
    val continent: String?,
    val country: String?,
    val countryCode: String?,
    val region: String?,
    val city: String?,
    val postalCode: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val asn: Long?,
    val organization: String?,
    val isp: String?,
    val domain: String?,
    val networkName: String?,
    val networkHandle: String?,
    val networkStart: String?,
    val networkEnd: String?,
    val networkType: String?,
    val networkCountry: String?,
    val registrant: String?,
    val abuseContact: String?,
    val registryServer: String?,
    val unavailableSources: List<String>
)

data class IpOrigin(
    val city: String?,
    val region: String?,
    val country: String?,
    val organization: String?,
    val isLocal: Boolean = false
)

sealed class IpLookupResult {
    data class Public(val details: IpDetails) : IpLookupResult()
    data class Local(val address: String, val description: String) : IpLookupResult()
}

object IpLookupService {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    fun isNumericAddress(address: String): Boolean {
        val value = address.trim()
        if (value.contains(':')) {
            return runCatching { InetAddress.getByName(value).address.size == 16 }.getOrDefault(false)
        }

        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
    }

    fun isPublicAddress(address: String): Boolean {
        if (!isNumericAddress(address)) return false
        val inetAddress = InetAddress.getByName(address)
        val bytes = inetAddress.address
        val isUniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        val isReserved = when (bytes.size) {
            4 -> isReservedIpv4(bytes)
            16 -> isReservedIpv6(bytes)
            else -> true
        }

        return !inetAddress.isAnyLocalAddress &&
            !inetAddress.isLoopbackAddress &&
            !inetAddress.isLinkLocalAddress &&
            !inetAddress.isSiteLocalAddress &&
            !inetAddress.isMulticastAddress &&
            !isUniqueLocalIpv6 &&
            !isReserved
    }

    fun lookup(address: String): IpLookupResult {
        val normalizedAddress = address.trim()
        require(isNumericAddress(normalizedAddress)) { "Keine gültige IP-Adresse" }

        if (!isPublicAddress(normalizedAddress)) {
            return IpLookupResult.Local(
                normalizedAddress,
                "Private, lokale oder reservierte Adresse"
            )
        }

        val encodedAddress = URLEncoder.encode(normalizedAddress, StandardCharsets.UTF_8.name())
        val unavailableSources = mutableListOf<String>()

        val geo = runCatching { fetchGeo(encodedAddress) }
            .onFailure { unavailableSources += "Region/ASN" }
            .getOrNull()

        val rdap = runCatching {
            fetchJson("https://rdap.org/ip/$encodedAddress", "application/rdap+json")
        }.onFailure { unavailableSources += "RDAP/WHOIS" }.getOrNull()

        if (geo == null && rdap == null) {
            error("Die IP-Details konnten derzeit nicht abgerufen werden")
        }

        val connection = geo?.optJSONObject("connection")
        val timezone = geo?.optJSONObject("timezone")
        val contacts = rdap?.let(::extractContacts).orEmpty()

        return IpLookupResult.Public(
            IpDetails(
                address = normalizedAddress,
                addressType = geo?.stringOrNull("type"),
                continent = geo?.stringOrNull("continent"),
                country = geo?.stringOrNull("country"),
                countryCode = geo?.stringOrNull("country_code"),
                region = geo?.stringOrNull("region"),
                city = geo?.stringOrNull("city"),
                postalCode = geo?.stringOrNull("postal"),
                latitude = geo?.numberOrNull("latitude"),
                longitude = geo?.numberOrNull("longitude"),
                timezone = timezone?.stringOrNull("id"),
                asn = connection?.longOrNull("asn"),
                organization = connection?.stringOrNull("org"),
                isp = connection?.stringOrNull("isp"),
                domain = connection?.stringOrNull("domain"),
                networkName = rdap?.stringOrNull("name"),
                networkHandle = rdap?.stringOrNull("handle"),
                networkStart = rdap?.stringOrNull("startAddress"),
                networkEnd = rdap?.stringOrNull("endAddress"),
                networkType = rdap?.stringOrNull("type"),
                networkCountry = rdap?.stringOrNull("country"),
                registrant = contacts["registrant"],
                abuseContact = contacts["abuse"],
                registryServer = rdap?.stringOrNull("port43"),
                unavailableSources = unavailableSources
            )
        )
    }

    fun lookupOrigin(address: String): IpOrigin {
        val normalizedAddress = address.trim()
        require(isNumericAddress(normalizedAddress)) { "Keine gültige IP-Adresse" }
        if (!isPublicAddress(normalizedAddress)) {
            return IpOrigin(
                city = null,
                region = null,
                country = null,
                organization = null,
                isLocal = true
            )
        }

        val encodedAddress = URLEncoder.encode(normalizedAddress, StandardCharsets.UTF_8.name())
        val geo = fetchGeo(encodedAddress)
        return IpOrigin(
            city = geo.stringOrNull("city"),
            region = geo.stringOrNull("region"),
            country = geo.stringOrNull("country"),
            organization = geo.optJSONObject("connection")?.stringOrNull("org")
        )
    }

    private fun fetchGeo(encodedAddress: String): JSONObject {
        return fetchJson("https://ipwho.is/$encodedAddress", "application/json")
            .also { response ->
                if (!response.optBoolean("success", false)) {
                    error(response.stringOrNull("message") ?: "Geo-Abfrage fehlgeschlagen")
                }
            }
    }

    private fun fetchJson(url: String, accept: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "AccessLog-Insight-Android/1.0")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("HTTP $status")
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun isReservedIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return first == 0 ||
            (first == 100 && second in 64..127) ||
            (first == 192 && second == 0 && third in setOf(0, 2)) ||
            (first == 192 && second == 88 && third == 99) ||
            (first == 198 && second in 18..19) ||
            (first == 198 && second == 51 && third == 100) ||
            (first == 203 && second == 0 && third == 113) ||
            first >= 240
    }

    private fun isReservedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        return first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8
    }

    private fun extractContacts(rdap: JSONObject): Map<String, String> {
        val contacts = linkedMapOf<String, String>()

        fun visit(entities: JSONArray?) {
            if (entities == null) return
            for (index in 0 until entities.length()) {
                val entity = entities.optJSONObject(index) ?: continue
                val roles = entity.optJSONArray("roles")
                val contact = formatVcard(entity.optJSONArray("vcardArray"))
                if (contact != null && roles != null) {
                    for (roleIndex in 0 until roles.length()) {
                        val role = roles.optString(roleIndex).lowercase()
                        if (role == "registrant" || role == "abuse") {
                            contacts.putIfAbsent(role, contact)
                        }
                    }
                }
                visit(entity.optJSONArray("entities"))
            }
        }

        visit(rdap.optJSONArray("entities"))
        return contacts
    }

    private fun formatVcard(vcardArray: JSONArray?): String? {
        val properties = vcardArray?.optJSONArray(1) ?: return null
        val values = linkedMapOf<String, String>()
        for (index in 0 until properties.length()) {
            val property = properties.optJSONArray(index) ?: continue
            val name = property.optString(0)
            val rawValue = property.opt(3)
            val value = when (rawValue) {
                is JSONArray -> (0 until rawValue.length())
                    .mapNotNull { rawValue.optString(it).takeIf(String::isNotBlank) }
                    .joinToString(" · ")
                else -> rawValue?.toString()?.trim().orEmpty()
            }
            if (name in setOf("fn", "org", "email") && value.isNotEmpty()) {
                values.putIfAbsent(name, value.removePrefix("mailto:"))
            }
        }
        return listOfNotNull(values["fn"], values["org"], values["email"])
            .distinct()
            .joinToString(" · ")
            .takeIf(String::isNotEmpty)
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        return optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.numberOrNull(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name) else null
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        return if (has(name) && !isNull(name)) optLong(name) else null
    }
}
