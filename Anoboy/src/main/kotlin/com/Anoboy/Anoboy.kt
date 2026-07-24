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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("a[rel=bookmark]:has(div.amv)").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3.ibox1, h3.ibox")?.text()?.trim() ?: this.attr("title") ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val statusText = this.selectFirst("div.jamup")?.text()?.trim() ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(statusText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("a[rel=bookmark]:has(div.amv)").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val mainSeriesUrl = document.selectFirst("th:contains(Semua Episode) + td a")?.attr("href")
        val mainDoc = if (mainSeriesUrl != null) app.get(fixUrl(mainSeriesUrl)).document else null
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

        val poster = fixUrlNull(document.selectFirst("div.sisi.entry-content img")?.attr("src"))
            ?: fixUrlNull(statusDoc.selectFirst("div.sisi.entry-content img")?.attr("src"))
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
        val document = app.get(data).document

        // 1. Load Gofile links
        val gofileUrls = document.select("a[href*='gofile.io']").mapNotNull { it.attr("href") }.distinct()
        for (url in gofileUrls) {
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }

        // 2. Load player iframe / mirrors (Btube, etc.)
        val players = document.select("div.vmiror a").mapNotNull { it.attr("data-video") }.map { fixUrl(it) }
        for (player in players) {
            loadExtractor(player, mainUrl, subtitleCallback, callback)
        }

        return gofileUrls.isNotEmpty() || players.isNotEmpty()
    }
}
