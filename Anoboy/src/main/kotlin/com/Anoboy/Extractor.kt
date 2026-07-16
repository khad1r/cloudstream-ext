package com.anoboy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val contentId = url.substringBefore("?").removeSuffix("/").substringAfterLast("/")
        if (contentId.isEmpty()) return emptyList()

        // 1. Create a guest account to get the token
        val accountRes = app.post("https://api.gofile.io/accounts").parsedSafe<GofileAccountResponse>()
        val token = accountRes?.data?.token ?: return emptyList()

        // 2. Generate the X-Website-Token
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val language = "en-US"
        val wt = generateWT(token, userAgent, language)

        // 3. Request the content metadata
        val contentUrl = "https://api.gofile.io/contents/$contentId?contentFilter=&page=1&pageSize=1000&sortField=name&sortDirection=1"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Authorization" to "Bearer $token",
            "X-BL" to language,
            "X-Website-Token" to wt,
            "Referer" to "https://gofile.io/"
        )

        val contentRes = app.get(contentUrl, headers = headers).parsedSafe<GofileContentResponse>()
        val children = contentRes?.data?.children?.values ?: return emptyList()

        val sources = mutableListOf<ExtractorLink>()
        for (child in children) {
            if (child.type == "file" && !child.link.isNullOrEmpty()) {
                sources.add(
                    newExtractorLink(
                        name,
                        name,
                        child.link,
                    ) {
                        this.referer = "https://gofile.io/"
                        this.quality = getQualityFromName(child.name)
                        this.headers = mapOf(
                            "Cookie" to "accountToken=$token",
                            "User-Agent" to userAgent
                        )
                    }
                )
            }
        }
        return sources
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateWT(token: String, userAgent: String, language: String): String {
        val timeSlot = (System.currentTimeMillis() / 1000 / 3600 / 4).toString()
        val raw = "$userAgent::$language::$token::$timeSlot::9844d94d963d30"
        return sha256(raw)
    }

    private fun getQualityFromName(name: String?): Int {
        if (name == null) return Qualities.Unknown.value
        return when {
            name.contains("1080") || name.contains("1K", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720") -> Qualities.P720.value
            name.contains("480") -> Qualities.P480.value
            name.contains("360") -> Qualities.P360.value
            name.contains("240") -> Qualities.P240.value
            name.contains("144") -> Qualities.P144.value
            name.contains("HD", ignoreCase = true) -> Qualities.P1080.value
            name.contains("SD", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    data class GofileAccountResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("data") val data: GofileAccountData? = null
    )

    data class GofileAccountData(
        @JsonProperty("token") val token: String? = null
    )

    data class GofileContentResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("data") val data: GofileData? = null
    )

    data class GofileData(
        @JsonProperty("children") val children: Map<String, GofileChild>? = null
    )

    data class GofileChild(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("link") val link: String? = null
    )
}