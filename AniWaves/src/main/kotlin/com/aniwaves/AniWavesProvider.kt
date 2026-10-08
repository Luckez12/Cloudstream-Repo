package com.aniwaves

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AniWavesProvider : MainAPI() {
    override var mainUrl = "https://aniwaves.ru"
    override var name = "AniWaves"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "updated" to "Latest Episodes",
        "trending" to "Trending",
        "newest" to "New Release",
        "added" to "Recently Added",
        "ongoing" to "Ongoing",
        "type/movies" to "Movies"
    )

    internal fun watchUrl(raw: String): String? = runCatching {
        val uri = URI("$mainUrl/").resolve(raw)
        if (!uri.host.equals(URI(mainUrl).host, true) || uri.scheme !in listOf("http", "https")) return@runCatching null
        val path = Regex("^/watch/([^/]+)").find(uri.path)?.value ?: return@runCatching null
        "$mainUrl$path"
    }.getOrNull()

    private fun type(text: String): TvType = when {
        text.contains("movie", true) -> TvType.AnimeMovie
        text.contains("OVA", true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun image(element: Element?): String? {
        val raw = element?.attr("data-src")?.ifBlank { element.attr("src") }.orEmpty()
        return raw.takeIf { it.isNotBlank() }?.let { URI("$mainUrl/").resolve(it).toString() }
    }

    internal fun cards(document: Document): List<SearchResponse> =
        document.select("aside.main .ani.items > .item").mapNotNull { card ->
            val anchor = card.selectFirst(".info a.name[href]") ?: return@mapNotNull null
            val url = watchUrl(anchor.attr("href")) ?: return@mapNotNull null
            val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newAnimeSearchResponse(title, url, type(card.selectFirst(".poster .right")?.text().orEmpty())) {
                posterUrl = image(card.selectFirst(".poster img"))
                posterHeaders = mapOf("Referer" to "$mainUrl/")
                val sub = card.selectFirst(".ep-status.sub")?.text()?.trim()?.toIntOrNull()
                val dub = card.selectFirst(".ep-status.dub")?.text()?.trim()?.toIntOrNull()
                if (sub != null) addDubStatus(DubStatus.Subbed, sub)
                if (dub != null) addDubStatus(DubStatus.Dubbed, dub)
            }
        }.distinctBy { it.url }

    internal fun hasNext(document: Document, page: Int): Boolean = document.select(".pagination a[href]").any {
        val href = it.attr("href")
        val number = Regex("(?:[?&]page=|/page/)([0-9]+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
        number != null && number > page
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(mainPage.any { it.data == request.data })
        val route = "$mainUrl/${request.data}" + if (page > 1) "/page/$page" else ""
        val response = app.get(route)
        if (response.code !in 200..299) throw ErrorLoadingException("AniWaves: katalog HTTP ${response.code}")
        val items = cards(response.document)
        if (items.isEmpty() && page == 1) throw ErrorLoadingException("AniWaves: katalog tidak ditemui.")
        Log.i(name, "ANIWAVES_HOME section=${request.data} page=$page items=${items.size}")
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext(response.document, page))
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList(false)
        val document = app.get("$mainUrl/filter?keyword=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page").document
        return cards(document).toNewSearchResponseList(hasNext(document, page))
    }

    internal suspend fun episodes(html: String, showUrl: String, poster: String?): Map<DubStatus, List<Episode>> {
        val rows = Jsoup.parse(html).select(".episodes a[data-ids][data-num]")
        return listOf(DubStatus.Subbed, DubStatus.Dubbed).associateWith { status ->
            val attr = if (status == DubStatus.Dubbed) "data-dub" else "data-sub"
            rows.filter { it.attr(attr) == "1" }.map { row ->
                val number = row.attr("data-num")
                val payload = mapper.createObjectNode().put("showUrl", showUrl)
                    .put("servers", row.attr("data-ids"))
                    .put("dub", status == DubStatus.Dubbed).toString()
                newEpisode(payload) {
                    // Fractional specials keep their real number in the title.
                    episode = number.toIntOrNull()
                    name = "Episode $number" + if (row.attr("data-filler") == "1") " [Filler]" else ""
                    posterUrl = poster
                }
            }.distinctBy { it.data }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val showUrl = watchUrl(url) ?: throw ErrorLoadingException("AniWaves: URL anime tidak sah.")
        val document = app.get(showUrl).document
        val id = document.selectFirst("#watch-main[data-id]")?.attr("data-id")
            ?.takeIf { it.matches(Regex("[0-9]+")) } ?: throw ErrorLoadingException("AniWaves: ID anime tidak ditemui.")
        val title = document.selectFirst("#w-info h1.title")?.text()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("AniWaves: tajuk tidak ditemui.")
        val poster = image(document.selectFirst("#w-info .poster img"))
        val result = mapper.readTree(app.get("$mainUrl/ajax/episode/list/$id", referer = showUrl, headers = ajaxHeaders).text)
        if (result.path("status").asInt() != 200) throw ErrorLoadingException("AniWaves: senarai episod gagal dimuatkan.")
        val groups = episodes(result.path("result").asText(), showUrl, poster)
        val meta = document.select("#w-info .bmeta .meta > div")
        fun field(label: String) = meta.firstOrNull { it.text().startsWith("$label:") }?.text()?.substringAfter(':')?.trim().orEmpty()
        Log.i(name, "ANIWAVES_DETAIL id=$id sub=${groups[DubStatus.Subbed]?.size} dub=${groups[DubStatus.Dubbed]?.size}")
        return newAnimeLoadResponse(title, showUrl, type(field("Type"))) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            plot = document.selectFirst("#w-info .synopsis .content")?.text()
            year = Regex("\\b(?:19|20)[0-9]{2}\\b").find(field("Premiered"))?.value?.toIntOrNull()
            tags = document.select("#w-info .bmeta a[href^=/genre/]").map { it.text() }
            showStatus = when (field("Status").lowercase()) {
                "airing" -> ShowStatus.Ongoing
                "completed", "finished airing" -> ShowStatus.Completed
                else -> null
            }
            groups.forEach { (status, list) -> addEpisodes(status, list) }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
        AniWavesPlayer(mainUrl).load(data, subtitleCallback, callback)

    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
}
