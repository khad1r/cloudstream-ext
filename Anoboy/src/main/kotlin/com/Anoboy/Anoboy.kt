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
        val items = document.select("a[rel=bookmark]:has(div.amv), a[rel=bookmark]:has(div#amv)").mapNotNull { it.toSearchResult() }
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
        return document.select("a[rel=bookmark]:has(div.amv), a[rel=bookmark]:has(div#amv)").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.pagetitle h1")?.text()
            ?.replace("Subtitle Indonesia", "")
            ?.replace("Episode\\s+\\d+".toRegex(), "")
            ?.trim()
            ?: document.selectFirst("h2.entry-title")?.text()
                ?.replace("Subtitle Indonesia", "")
                ?.replace("Episode\\s+\\d+".toRegex(), "")
                ?.trim()
            ?: return null

        val poster = fixUrlNull(document.selectFirst("div.sisi.entry-content img")?.attr("src"))
        val tags = document.selectFirst("td#genre")?.text()?.split(",")?.map { it.trim() } ?: emptyList()
        val type = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        val year = Regex("/(\\d{4})/").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val status = if (document.selectFirst("a[href*='ongoing']") != null) ShowStatus.Ongoing else ShowStatus.Completed
        val description = document.selectFirst(".entry-content[itemprop=description]")?.text()
            ?: document.selectFirst("div.contentdeks")?.text()
            ?: ""

        // Episodes extraction
        val episodesList = document.select("ul.lcp_catlist li a")
        val episodes = if (episodesList.isNotEmpty()) {
            episodesList.map { parseEpisode(it) }
        } else {
            val mainSeriesUrl = document.selectFirst("th:contains(Semua Episode) + td a")?.attr("href")
            if (mainSeriesUrl != null) {
                val mainDoc = app.get(fixUrl(mainSeriesUrl)).document
                mainDoc.select("ul.lcp_catlist li a").map { parseEpisode(it) }
            } else {
                val episodeNumber = title.replace(Regex(".*Episode\\s*(\\d+).*"), "$1").toIntOrNull()
                listOf(newEpisode(url) {
                    this.data = url
                    this.name = "Episode ${episodeNumber ?: 1}"
                    this.episode = episodeNumber ?: 1
                })
            }
        }.reversed()

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

    private fun parseEpisode(element: Element): Episode {
        val link = fixUrl(element.attr("href"))
        val titleText = element.text().trim()
        val episodeNumber = titleText.replace(Regex(".*Episode\\s*(\\d+).*"), "$1").toIntOrNull()
        return newEpisode(link) {
            this.data = link
            this.name = "Episode ${episodeNumber ?: 0}"
            this.episode = episodeNumber
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
