package com.anoboy

import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.xyz"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "page/%d/" to "Latest Release",
        "anime/ongoing/page/%d/" to "Ongoing Anime",
        "anime-movie/page/%d/" to "Movie",
        "live-action-movie/page/%d/" to "Live-Action",
        "tokusatsu/page/%d/" to "Tokusatsu",
        "action/page/%d/" to "Action",
        "adventure/page/%d/" to "Adventure",
        "romance/page/%d/" to "Romance",
    )

    private val cfSolver = CloudflareSolver()

    // User-Agent is derived from the same real-device UA CloudflareSolver uses to solve the
    // challenge (see CloudflareSolver.realUserAgent), instead of a separately hardcoded desktop
    // string, so this plain OkHttp request and the WebView agree on what device/browser they claim.
    private val headers by lazy {
        mapOf(
            "User-Agent" to cfSolver.realUserAgent(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    private fun isChallengePage(code: Int, body: String): Boolean {
        return code == 403 || code == 503 ||
            body.contains("Just a moment", ignoreCase = true) ||
            body.contains("cf-chl", ignoreCase = true) ||
            body.contains("challenge-platform", ignoreCase = true)
    }

    // Cloudflare is checked by response content, not just status code, since a still-blocked
    // request can come back as a 200 with an interstitial body instead of a 403. When blocked,
    // the page HTML is pulled straight out of a WebView (see CloudflareSolver) rather than
    // retried through OkHttp with a replayed cookie, which looked like it worked but still
    // returned interstitial content in practice.
    private suspend fun fetchDoc(url: String): org.jsoup.nodes.Document {
        val response = app.get(url, headers = headers)
        val blocked = isChallengePage(response.code, response.text)
        android.util.Log.d("AnoboyDebug", "fetchDoc code=${response.code} blocked=$blocked bodyLen=${response.text.length} url=$url")
        if (!blocked) return response.document

        val html = cfSolver.fetchHtml(url)
        android.util.Log.d("AnoboyDebug", "fetchDoc cfSolver htmlLen=${html?.length ?: -1} url=$url")
        if (html == null) return response.document
        return org.jsoup.Jsoup.parse(html, url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDoc("$mainUrl/${request.data.format(page)}")
        val rawMatches = document.select("a[rel=bookmark]:has(div.amv)")
        val items = rawMatches.mapNotNull { it.toSearchResult() }
        android.util.Log.d("AnoboyDebug", "getMainPage ${request.name} rawMatches=${rawMatches.size} items=${items.size} title=${document.title()}")
        return newHomePageResponse(request.name, items,isHorizontalImages = true)
    }

    // Images are lazy-loaded: <img class="lazy" data-src="/real.jpg" src="data:image/svg+xml,...">
    private fun Element.lazyImgUrl(): String? {
        return fixUrlNull(this.attr("data-src").takeIf { it.isNotBlank() } ?: this.attr("src"))
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3.ibox1, h3.ibox")?.text()?.trim() ?: this.attr("title") ?: return null
        val posterUrl = this.selectFirst("img")?.lazyImgUrl()
        val statusText = this.selectFirst("div.jamup")?.text()?.trim() ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(statusText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = fetchDoc("$mainUrl/?s=$query")
        return document.select("a[rel=bookmark]:has(div.amv)").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDoc(url)
        val mainSeriesUrl = document.selectFirst("th:contains(Semua Episode) + td a")?.attr("href")
        val mainDoc = if (mainSeriesUrl != null) fetchDoc(fixUrl(mainSeriesUrl)) else null
        val statusDoc = mainDoc ?: document

        val rawTitle = statusDoc.selectFirst("div.pagetitle h1")?.text()
            ?: statusDoc.selectFirst("h2.entry-title")?.text()
            ?: document.selectFirst("div.pagetitle h1")?.text()
            ?: document.selectFirst("h2.entry-title")?.text()
            ?: return null

        val title = rawTitle
            .replace("Subtitle Indonesia", "")
            .replace(Regex("Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s*Season\\s+[\\d\\+\\s-]+.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("div.sisi.entry-content img")?.lazyImgUrl()
            ?: statusDoc.selectFirst("div.sisi.entry-content img")?.lazyImgUrl()
        val tags = document.selectFirst("td#genre")?.text()?.split(",")?.map { it.trim() }
            ?: statusDoc.selectFirst("td#genre")?.text()?.split(",")?.map { it.trim() }
            ?: emptyList()
        val type = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        val year = Regex("/(\\d{4})/").find(url)?.groupValues?.get(1)?.toIntOrNull()

        val status = if (statusDoc.selectFirst("a[href*='ongoing']") != null) ShowStatus.Ongoing else ShowStatus.Completed
        val description = document.selectFirst("div.contentdeks")?.text()
            ?: document.selectFirst(".entry-content[itemprop=description]")?.text()
            ?: statusDoc.selectFirst("div.contentdeks")?.text()
            ?: statusDoc.selectFirst(".entry-content[itemprop=description]")?.text()
            ?: ""

        // Episodes extraction with season support
        val hqElements = statusDoc.select("div.hq")
        val episodes = mutableListOf<Episode>()

        if (hqElements.isNotEmpty()) {
            val seasonsMap = LinkedHashMap<Int, MutableList<Episode>>()
            var autoSeasonCounter = 1

            for (hq in hqElements) {
                val hqText = hq.text().trim()
                val extractedSeason = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(hqText)?.groupValues?.get(1)?.toIntOrNull()

                val seasonNum = extractedSeason ?: autoSeasonCounter
                if (extractedSeason != null) {
                    autoSeasonCounter = maxOf(autoSeasonCounter, extractedSeason + 1)
                } else {
                    autoSeasonCounter++
                }

                val singleLink = hq.nextElementSibling()?.takeIf { it.hasClass("singlelink") }
                    ?: hq.nextElementSiblings().firstOrNull { it.hasClass("singlelink") }

                val links = singleLink?.select("ul.lcp_catlist li a") ?: emptyList()
                val seasonEpisodes = links.mapNotNull { parseEpisode(it, seasonNum) }.reversed()

                if (seasonEpisodes.isNotEmpty()) {
                    seasonsMap.getOrPut(seasonNum) { mutableListOf() }.addAll(seasonEpisodes)
                }
            }

            // Order seasons in descending order (latest season first) so CloudStream defaults to opening the latest season
            val sortedSeasonKeys = seasonsMap.keys.sortedDescending()
            for (sKey in sortedSeasonKeys) {
                seasonsMap[sKey]?.let { episodes.addAll(it) }
            }
        }

        if (episodes.isEmpty()) {
            val episodesList = statusDoc.select("ul.lcp_catlist li a")
            if (episodesList.isNotEmpty()) {
                episodes.addAll(episodesList.mapNotNull { parseEpisode(it, 1) }.reversed())
            } else {
                val episodeNumber = rawTitle.replace(Regex(".*Episode\\s*(\\d+).*", RegexOption.IGNORE_CASE), "$1").toIntOrNull()
                episodes.add(newEpisode(url) {
                    this.data = url
                    this.name = "Episode ${episodeNumber ?: 1}"
                    this.episode = episodeNumber ?: 1
                    this.season = 1
                })
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    private fun parseEpisode(element: Element, seasonNum: Int = 1): Episode? {
        val link = fixUrl(element.attr("href"))
        val titleText = element.text().trim()
        if (titleText.contains("Download", ignoreCase = true)) {
            return null
        }

        val episodeNumber = Regex("(?:Episode|Ep)\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(titleText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(\\d+)\\b").find(titleText)?.groupValues?.get(1)?.toIntOrNull()

        val nameText = when {
            titleText.contains("OVA", ignoreCase = true) -> titleText
            titleText.contains("Part", ignoreCase = true) -> {
                val partMatch = Regex("(Part\\s*\\d+\\s*Episode\\s*\\d+)", RegexOption.IGNORE_CASE)
                    .find(titleText)?.groupValues?.get(1)
                partMatch ?: if (episodeNumber != null) "Part Episode $episodeNumber" else titleText
            }
            episodeNumber != null -> "Episode $episodeNumber"
            else -> titleText
        }

        return newEpisode(link) {
            this.data = link
            this.name = nameText
            this.episode = episodeNumber
            this.season = seasonNum
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDoc(data)
        val rawHtmlLen = document.outerHtml().length
        android.util.Log.d("AnoboyDebug", "loadLinks data=$data docLen=$rawHtmlLen containsGofile=${document.outerHtml().contains("gofile", ignoreCase = true)}")

        // 1. Load Gofile links
        val gofileUrls = document.select("a[href*='gofile.io']").mapNotNull { it.attr("href") }.distinct()
        android.util.Log.d("AnoboyDebug", "loadLinks gofileUrls=${gofileUrls.size}")
        for (url in gofileUrls) {
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }

        // 2. Load player iframe / mirrors (Btube, etc.)
        val players = document.select("div.vmiror a").mapNotNull { it.attr("data-video") }.map { fixUrl(it) }
        android.util.Log.d("AnoboyDebug", "loadLinks players=${players.size}")
        for (player in players) {
            loadExtractor(player, mainUrl, subtitleCallback, callback)
        }

        return gofileUrls.isNotEmpty() || players.isNotEmpty()
    }
}
