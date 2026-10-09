package com.animextv

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import org.jsoup.Jsoup

class AnimeXTVProvider : MainAPI() {
    override var mainUrl = "https://anime.streamxtv.tech"
    override var name = "AnimeXTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "trending" to "Trending", "popular" to "Popular", "airing" to "Top Airing",
        "completed" to "Recently Completed", "movies" to "Movies"
    )
    private val catalog by lazy { AnimeXTVCatalog(mainUrl) }

    private fun type(format: String) = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA" -> TvType.OVA
        else -> TvType.Anime
    }
    private fun title(node: JsonNode) = node.path("title").path("english").asText("").ifBlank { node.path("title").path("romaji").asText("") }
    private fun poster(node: JsonNode) = node.path("coverImage").path("extraLarge").asText("").ifBlank { node.path("coverImage").path("large").asText("") }.takeIf { it.isNotBlank() }

    private fun contentUrl(node: JsonNode): String {
        val mal = node.path("idMal").asInt()
        return "$mainUrl/anime/${node.path("id").asInt()}" + if (mal > 0) "?mal=$mal" else ""
    }
    internal fun cards(nodes: List<JsonNode>): List<SearchResponse> = nodes.mapNotNull { node ->
        if (node.path("id").asInt() <= 0 || title(node).isBlank()) return@mapNotNull null
        newAnimeSearchResponse(title(node), contentUrl(node), type(node.path("format").asText())) {
            posterUrl = poster(node)
            addDubStatus(DubStatus.Subbed)
        }
    }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(mainPage.any { it.data == request.data })
        val (media, next) = catalog.listing(request.data, page.coerceAtLeast(1))
        val items = cards(media)
        Log.i(name, "ANIMEXTV_HOME section=${request.data} page=$page items=${items.size}")
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), next)
    }
    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.trim().length < 2) return emptyList<SearchResponse>().toNewSearchResponseList(false)
        val (media, next) = catalog.listing("search", page.coerceAtLeast(1), query.trim())
        return cards(media).toNewSearchResponseList(next)
    }

    internal fun parseUrl(raw: String): Pair<Int, Int?>? = runCatching {
        val uri = URI(raw)
        if (!uri.host.equals(URI(mainUrl).host, true) || uri.scheme !in setOf("https", "http")) return@runCatching null
        val id = Regex("^/anime/([0-9]+)/?$").find(uri.path)?.groupValues?.get(1)?.toIntOrNull() ?: return@runCatching null
        val mal = Regex("(?:^|&)mal=([0-9]+)(?:&|$)").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        id to mal
    }.getOrNull()

    internal fun episodeCount(node: JsonNode): Int {
        if (node.path("status").asText() == "NOT_YET_RELEASED") return 0
        val total = node.path("episodes").asInt()
        val released = (node.path("nextAiringEpisode").path("episode").asInt() - 1).coerceAtLeast(0)
        return when {
            released > 0 && total > 0 -> minOf(released, total)
            released > 0 -> released
            total > 0 -> total
            node.path("format").asText() == "MOVIE" -> 1
            else -> 0
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val (id, mal) = parseUrl(url) ?: throw ErrorLoadingException("AnimeXTV: URL anime tidak sah.")
        val node = catalog.detail(id, mal)
        val count = episodeCount(node)
        if (count == 0 && node.path("status").asText() != "NOT_YET_RELEASED") throw ErrorLoadingException("AnimeXTV: jumlah episod belum tersedia daripada API.")
        val episodes = (1..count).map { number ->
            val data = mapper.createObjectNode().put("ani", id).put("mal", node.path("idMal").asInt()).put("episode", number).put("audio", "sub")
            newEpisode(data.toString()) {
                episode = number
                name = "Episode $number"
                posterUrl = poster(node)
            }
        }
        Log.i(name, "ANIMEXTV_DETAIL id=$id episodes=${episodes.size} audio=sub")
        return newAnimeLoadResponse(title(node), contentUrl(node), type(node.path("format").asText())) {
            posterUrl = poster(node)
            plot = Jsoup.parse(node.path("description").asText("")).text().takeIf { it.isNotBlank() }
            year = node.path("seasonYear").asInt().takeIf { it > 0 }
            tags = node.path("genres").map { it.asText() }
            showStatus = when (node.path("status").asText()) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
        AnimeXTVPlayer(mainUrl).load(data, subtitleCallback, callback)
}
