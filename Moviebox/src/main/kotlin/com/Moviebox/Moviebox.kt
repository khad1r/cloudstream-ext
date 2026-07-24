package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Moviebox : MainAPI() {

    override var mainUrl = "https://api6.aoneroom.com"
    override val instantLinkLoading = true
    override var name = "Moviebox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val hostPool = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api.inmoviebox.com"
    )
    private var activeHostIdx = 0

    private val userAgent = "com.community.oneroom/50020045 (Linux; U; Android 11; en_US; Redmi; Build/RP1A.200720.011; Cronet/135.0.7012.3)"
    private val clientInfo = """{"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,"os":"android","os_version":"11","install_ch":"ps","device_id":"8a9f3b2c1d4e5f6a7b8c9d0e1f2a3b4c","install_store":"ps","gaid":"12345678-1234-1234-1234-123456789abc","brand":"Redmi","model":"2201117TY","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":"40401","X-Play-Mode":"2"}"""
    private val spoofedIp = "103.241.12.34"
    private var authToken: String? = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjU0MDk1MjU2NTYxMTM4OTM4NTYsImV4cCI6MTc5MjY2MzA3NSwiaWF0IjoxNzg0ODg2Nzc1fQ.4LehhcAAVBWbBhOp6Ywc6dqhVAN5BAIIlpfiH7ETIdE"
    private var secretKey = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"

    private fun md5Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun base64Decode(valStr: String): ByteArray {
        var padded = valStr
        val padding = (4 - padded.length % 4) % 4
        if (padding > 0) padded += "=".repeat(padding)
        return padded.decodeBase64()?.toByteArray() ?: byteArrayOf()
    }

    private fun base64Encode(data: ByteArray): String {
        return data.toByteString().base64()
    }

    private fun generateXClientToken(ts: Long): String {
        val tsStr = ts.toString()
        val reversedTs = tsStr.reversed()
        val hashVal = md5Hex(reversedTs.toByteArray(Charsets.UTF_8))
        return "$tsStr,$hashVal"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String = "application/json",
        contentType: String = "application/json",
        fullUrl: String,
        body: String?,
        timestampMs: Long
    ): String {
        val uri = URI(fullUrl)
        val path = uri.path ?: ""
        val query = uri.rawQuery
        val sortedQuery = if (query.isNullOrEmpty()) "" else {
            query.split("&")
                .map { it.split("=", limit = 2) }
                .sortedBy { it[0] }
                .joinToString("&") { if (it.size > 1) "${it[0]}=${it[1]}" else it[0] }
        }
        val canonicalUrl = if (sortedQuery.isEmpty()) path else "$path?$sortedQuery"

        val (bodyHash, bodyLength) = if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val truncated = if (bytes.size > 102400) bytes.copyOf(102400) else bytes
            Pair(md5Hex(truncated), bytes.size.toString())
        } else {
            Pair("", "")
        }

        val canonicalString = listOf(
            method.uppercase(),
            accept,
            contentType,
            bodyLength,
            timestampMs.toString(),
            bodyHash,
            canonicalUrl
        ).joinToString("\n")

        val secretBytes = base64Decode(secretKey)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val sigBytes = mac.doFinal(canonicalString.toByteArray(Charsets.UTF_8))
        val sigB64 = base64Encode(sigBytes)

        return "$timestampMs|2|$sigB64"
    }

    private fun getHeaders(method: String, fullUrl: String, body: String? = null): Map<String, String> {
        val ts = System.currentTimeMillis()
        val clientToken = generateXClientToken(ts)
        val signature = generateXTrSignature(method, "application/json", "application/json", fullUrl, body, ts)

        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Connection" to "keep-alive",
            "X-Client-Token" to clientToken,
            "x-tr-signature" to signature,
            "X-Client-Info" to clientInfo,
            "X-Client-Status" to "0",
            "X-Forwarded-For" to spoofedIp
        )

        authToken?.let {
            headers["Authorization"] = "Bearer $it"
        }

        return headers
    }

    private suspend fun fetchTokenForHost(host: String): String? {
        return try {
            val fullUrl = "$host/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version="
            val headers = getHeaders("GET", fullUrl, null)
            val res = app.get(fullUrl, headers = headers)
            res.headers["x-user"]?.let { xUser ->
                parseJson<Map<String, Any>>(xUser)["token"] as? String
            }
        } catch (_: Exception) { null }
    }

    private suspend fun makeApiRequest(method: String, pathAndQuery: String, bodyJson: String? = null): NiceResponse {
        val startIdx = activeHostIdx
        for (i in hostPool.indices) {
            val idx = (startIdx + i) % hostPool.size
            val host = hostPool[idx]

            if (authToken.isNullOrEmpty()) {
                val newToken = fetchTokenForHost(host)
                if (!newToken.isNullOrEmpty()) {
                    authToken = newToken
                }
            }

            val fullUrl = "$host$pathAndQuery"
            var headers = getHeaders(method, fullUrl, bodyJson)

            try {
                var response = if (method.equals("POST", ignoreCase = true)) {
                    val requestBody = (bodyJson ?: "").toRequestBody("application/json".toMediaTypeOrNull())
                    app.post(fullUrl, headers = headers, requestBody = requestBody)
                } else {
                    app.get(fullUrl, headers = headers)
                }

                response.headers["x-user"]?.let { xUser ->
                    try {
                        val token = parseJson<Map<String, Any>>(xUser)["token"] as? String
                        if (!token.isNullOrEmpty()) {
                            authToken = token
                        }
                    } catch (_: Exception) {}
                }

                if (response.code == 441 || response.text.contains("miss token", ignoreCase = true)) {
                    val newToken = fetchTokenForHost(host)
                    if (!newToken.isNullOrEmpty()) {
                        authToken = newToken
                        headers = getHeaders(method, fullUrl, bodyJson)
                        response = if (method.equals("POST", ignoreCase = true)) {
                            val requestBody = (bodyJson ?: "").toRequestBody("application/json".toMediaTypeOrNull())
                            app.post(fullUrl, headers = headers, requestBody = requestBody)
                        } else {
                            app.get(fullUrl, headers = headers)
                        }
                    }
                }

                if (response.isSuccessful && !response.text.contains("miss token", ignoreCase = true)) {
                    activeHostIdx = idx
                    return response
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw ErrorLoadingException("MovieBox API host pool exhausted")
    }

    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending Now",
        "997144265920760504" to "Popular Movie",
        "5283462032510044280" to "Drama Indonesia Terkini",
        "6528093688173053896" to "Trending Indonesian Movies",
        "4380734070238626200" to "K-Drama",
        "7736026911486755336" to "Western TV",
        "8624142774394406504" to "Most Popular C-Drama",
        "5404290953194750296" to "Trending Anime",
        "5848753831881965888" to "Indonesian Horror Stories",
        "1164329479448281992" to "Thai-Drama",
        "7132534597631837112" to "Animated Film",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = "/wefeed-mobile-bff/tab-operating?page=$page&tabId=${request.data}&version="
        val items = mutableListOf<SearchResponse>()
        try {
            val res = makeApiRequest("GET", path)
            val dataObj = res.parsedSafe<MediaData>()?.data
            dataObj?.subjectList?.mapNotNullTo(items) { it.toSearchResponse(this) }
            dataObj?.items?.forEach { item ->
                item.toSearchResponse(this)?.let { items.add(it) }
                item.subjects?.mapNotNullTo(items) { it.toSearchResponse(this) }
                item.banner?.banners?.mapNotNullTo(items) { it.subject?.toSearchResponse(this) }
            }
        } catch (_: Exception) {}

        if (items.isEmpty()) {
            try {
                val webUrl = "https://moviebox.ph/wefeed-h5api-bff/ranking-list/content?id=${request.data}&page=$page&perPage=12"
                val res = app.get(webUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json",
                    "Referer" to "https://moviebox.ph/"
                ))
                val dataObj = res.parsedSafe<MediaData>()?.data
                dataObj?.subjectList?.mapNotNullTo(items) { it.toSearchResponse(this) }
                dataObj?.items?.mapNotNullTo(items) { it.toSearchResponse(this) }
            } catch (_: Exception) {}
        }

        if (items.isEmpty()) throw ErrorLoadingException("No Data Found")
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val body = mapOf(
            "keyword" to query,
            "page" to 1,
            "perPage" to 20,
            "subjectType" to "All",
            "tabId" to "All"
        ).toJson()
        val res = makeApiRequest("POST", "/wefeed-mobile-bff/subject-api/search/v2", body)
        val dataObj = res.parsedSafe<SearchDataContainer>()?.data
        val items = mutableListOf<SearchResponse>()
        dataObj?.results?.forEach { resItem ->
            resItem.subjects?.mapNotNullTo(items) { it.toSearchResponse(this) }
        }
        dataObj?.items?.mapNotNullTo(items) { it.toSearchResponse(this) }
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").substringAfter("id=")
        val res = makeApiRequest("GET", "/wefeed-mobile-bff/subject-api/get?subjectId=$id")
        val doc = res.parsedSafe<MediaDetailData>()?.data
        val subject = doc?.subject ?: doc?.item ?: doc
        val title = subject?.title ?: ""
        val poster = subject?.cover?.url ?: subject?.coverUrl
        val tags = subject?.genre?.split(",")?.map { it.trim() }
        val year = subject?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val stype = subject?.subjectType ?: subject?.stype ?: 1
        val tvType = if (stype == 2) TvType.TvSeries else TvType.Movie
        val description = subject?.description
        val trailer = subject?.trailer?.videoAddress?.url
        val score = Score.from10(subject?.imdbRatingValue)
        val actors = doc?.stars?.mapNotNull { cast ->
            ActorData(Actor(cast.name ?: return@mapNotNull null, cast.avatarUrl), roleString = cast.character)
        }?.distinctBy { it.actor }

        return if (tvType == TvType.TvSeries) {
            val seasonRes = try {
                makeApiRequest("GET", "/wefeed-mobile-bff/subject-api/season-info?subjectId=$id")
            } catch (_: Exception) { null }
            val seasonsList = seasonRes?.parsedSafe<SeasonInfoData>()?.data?.seasons
                ?: doc?.resource?.seasons

            val episodes = if (!seasonsList.isNullOrEmpty()) {
                seasonsList.map { season ->
                    val seNum = season.se ?: 1
                    val epList = if (!season.allEp.isNullOrEmpty()) {
                        season.allEp.split(",").mapNotNull { it.trim().toIntOrNull() }
                    } else if (season.maxEp != null && season.maxEp > 0) {
                        (1..season.maxEp).toList()
                    } else {
                        listOf(1)
                    }
                    epList.map { epNum ->
                        newEpisode(LoadData(id, seNum, epNum).toJson()) {
                            this.season = seNum
                            this.episode = epNum
                        }
                    }
                }.flatten()
            } else {
                listOf(newEpisode(LoadData(id, 1, 1).toJson()) {
                    this.season = 1
                    this.episode = 1
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.actors = actors
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LoadData(id).toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.actors = actors
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = parseJson<LoadData>(data)
        val id = media.id ?: return false
        val season = media.season ?: 0
        val episode = media.episode ?: 0

        val path = if (season == 0 && episode == 0) {
            "/wefeed-mobile-bff/subject-api/resource?subjectId=$id&page=1&perPage=20"
        } else {
            "/wefeed-mobile-bff/subject-api/resource?subjectId=$id&se=$season&ep=$episode&page=1&perPage=20"
        }

        val resourceLinks = mutableListOf<ResourceItem>()
        try {
            val response = makeApiRequest("GET", path)
            val items = response.parsedSafe<ResourceData>()?.data?.list
                ?: response.parsedSafe<ResourceData>()?.list
            items?.let { resourceLinks.addAll(it) }
        } catch (_: Exception) {}

        val uniqueStreams = resourceLinks.distinctBy { it.resourceLink ?: it.url }
        var firstResourceId: String? = null

        for (item in uniqueStreams) {
            val link = item.resourceLink ?: item.url ?: continue
            if (firstResourceId == null) {
                firstResourceId = item.id ?: item.resourceId
            }
            val qualityStr = item.resolution?.toString() ?: item.quality ?: "720"
            callback(newExtractorLink(this.name, this.name, link, INFER_TYPE) {
                this.quality = getQualityFromName(qualityStr)
            })
        }

        firstResourceId?.let { resId ->
            try {
                val capPath = "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$id&resourceId=$resId"
                val capRes = makeApiRequest("GET", capPath)
                val captions = capRes.parsedSafe<CaptionData>()?.data
                    ?: capRes.parsedSafe<List<CaptionItem>>()
                captions?.forEach { cap ->
                    val name = cap.lanName ?: cap.language ?: cap.lan ?: "Subtitle"
                    val subUrl = cap.url ?: cap.link ?: return@forEach
                    subtitleCallback(newSubtitleFile(name, subUrl))
                }
            } catch (_: Exception) {}
        }

        return uniqueStreams.isNotEmpty()
    }

    data class LoadData(val id: String? = null, val season: Int? = null, val episode: Int? = null)

    data class SearchDataContainer(
        @JsonProperty("data") val data: SearchResultContent? = null
    ) {
        data class SearchResultContent(
            @JsonProperty("results") val results: List<SearchResultGroup>? = null,
            @JsonProperty("items") val items: List<Items>? = null
        )
        data class SearchResultGroup(
            @JsonProperty("subjects") val subjects: List<Items>? = null
        )
    }

    data class MediaData(@JsonProperty("data") val data: DataContent? = null) {
        data class DataContent(
            @JsonProperty("subjectList") val subjectList: List<Items>? = null,
            @JsonProperty("items") val items: List<Items>? = null
        )
    }

    data class MediaDetailData(@JsonProperty("data") val data: Items? = null)

    data class SeasonInfoData(@JsonProperty("data") val data: SeasonContent? = null) {
        data class SeasonContent(
            @JsonProperty("seasons") val seasons: List<Seasons>? = null
        )
    }

    data class Seasons(
        @JsonProperty("se") val se: Int? = null,
        @JsonProperty("maxEp") val maxEp: Int? = null,
        @JsonProperty("allEp") val allEp: String? = null
    )

    data class ResourceData(
        @JsonProperty("data") val data: ResourceList? = null,
        @JsonProperty("list") val list: List<ResourceItem>? = null
    ) {
        data class ResourceList(@JsonProperty("list") val list: List<ResourceItem>? = null)
    }

    data class ResourceItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("resourceId") val resourceId: String? = null,
        @JsonProperty("resourceLink") val resourceLink: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Any? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class CaptionData(
        @JsonProperty("data") val data: List<CaptionItem>? = null
    )

    data class CaptionItem(
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lan") val lan: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("link") val link: String? = null
    )

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("stype") val stype: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("coverUrl") val coverUrl: String? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("subject") val subject: Items? = null,
        @JsonProperty("item") val item: Items? = null,
        @JsonProperty("subjects") val subjects: List<Items>? = null,
        @JsonProperty("banner") val banner: BannerContainer? = null,
        @JsonProperty("stars") val stars: List<Stars>? = null,
        @JsonProperty("resource") val resource: ResourceContainer? = null
    ) {
        fun toSearchResponse(provider: Moviebox): SearchResponse? {
            val sid = subjectId ?: id ?: return null
            val t = title ?: ""
            if (t.isEmpty()) return null
            val type = if ((subjectType ?: stype ?: 1) == 2) TvType.TvSeries else TvType.Movie
            val poster = cover?.url ?: coverUrl
            val itemUrl = "${provider.mainUrl}/detail/$sid"
            return provider.newMovieSearchResponse(t, itemUrl, type, false) {
                this.posterUrl = poster
            }
        }

        data class Cover(@JsonProperty("url") val url: String? = null)
        data class Trailer(@JsonProperty("videoAddress") val videoAddress: VideoAddress? = null) {
            data class VideoAddress(@JsonProperty("url") val url: String? = null)
        }
        data class BannerContainer(@JsonProperty("banners") val banners: List<BannerItem>? = null) {
            data class BannerItem(@JsonProperty("subject") val subject: Items? = null)
        }
        data class Stars(@JsonProperty("name") val name: String? = null, @JsonProperty("character") val character: String? = null, @JsonProperty("avatarUrl") val avatarUrl: String? = null)
        data class ResourceContainer(@JsonProperty("seasons") val seasons: List<Seasons>? = null)
    }
}
