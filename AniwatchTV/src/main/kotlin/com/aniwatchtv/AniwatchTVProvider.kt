package com.aniwatchtv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AniwatchTVProvider : MainAPI() {
    override var name = "AniwatchTV.ro"
    override var mainUrl = "https://aniwatchtv.ro"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "/recently-updated/" to "Latest Episodes",
        "/most-popular/" to "Most Popular",
        "/type/movie/" to "Movies"
    )
    internal fun siteUrl(raw: String): String? = runCatching { URI(mainUrl).resolve(raw) }.getOrNull()
        ?.takeIf { it.scheme in setOf("http", "https") && it.host == URI(mainUrl).host }?.toString()

    private suspend fun document(url: String): Document {
        val response = withTimeout(15_000L) { app.get(url, referer = "$mainUrl/") }
        Log.i(name, "ANIWATCH_HTTP path=${URI(url).path} status=${response.code}")
        if (response.code !in 200..299) throw ErrorLoadingException("AniwatchTV: HTTP ${response.code}")
        return response.document
    }

    private fun card(el: Element): SearchResponse? {
        if (el.selectFirst(".tick-dub") != null && el.selectFirst(".tick-sub") == null) return null
        val link = el.selectFirst(".film-name a[href]") ?: return null
        val url = siteUrl(link.attr("href")) ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim().takeIf { it.isNotBlank() } ?: return null
        val image = el.selectFirst("img.film-poster-img")
        val poster = image?.attr("data-src")?.ifBlank { image.attr("src") }
        val type = when (el.selectFirst(".fdi-item")?.text()?.trim()) {
            "Movie" -> TvType.AnimeMovie; "OVA" -> TvType.OVA; else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, url, type) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(page > 0 && mainPage.any { it.data == request.data })
        val url = "$mainUrl${request.data}" + if (page > 1) "page/$page/" else ""
        val doc = document(url)
        val items = doc.select(".film_list .flw-item").mapNotNull(::card).distinctBy { it.url }
        Log.i(name, "ANIWATCH_HOME section=${request.name.replace(' ', '_')} page=$page items=${items.size}")
        if (items.isEmpty() && page == 1) throw ErrorLoadingException("AniwatchTV: catalogue unavailable")
        val next = doc.selectFirst("link[rel=next]")?.attr("href")
            ?: doc.selectFirst(".pagination .next a, .pagination a.next")?.attr("href")
        return newHomePageResponse(HomePageList(request.name, items), !next.isNullOrBlank())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = document("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}")
        return doc.select(".film_list .flw-item").mapNotNull(::card).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var target = siteUrl(url) ?: throw ErrorLoadingException("AniwatchTV: invalid anime URL")
        var doc = document(target)
        if (!URI(target).path.startsWith("/anime/")) {
            target = doc.selectFirst(".anisc-detail h2.film-name a[href]")?.attr("href")?.let(::siteUrl)
                ?: throw ErrorLoadingException("AniwatchTV: anime detail link unavailable")
            doc = document(target)
        }
        val title = doc.selectFirst(".anisc-detail h2.film-name")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("AniwatchTV: title unavailable")
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val id = doc.selectFirst("meta[name=hi-anime-id]")?.attr("content")?.toIntOrNull()
            ?: Regex("\"anime_id\"\\s*:\\s*\"?([0-9]+)").find(scripts)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("AniwatchTV: anime ID unavailable")
        val response = withTimeout(15_000L) { app.get("$mainUrl/wp-json/v1/otakuthemes/episode/list/$id", referer = target) }
        if (response.code !in 200..299) throw ErrorLoadingException("AniwatchTV: episode HTTP ${response.code}")
        val root = mapper.readTree(response.text)
        if (!root.path("status").asBoolean()) throw ErrorLoadingException("AniwatchTV: episode list unavailable")
        val episodes = Jsoup.parse(root.path("html").asText()).select("a.ep-item[data-id][data-number][href]").mapNotNull { el ->
            val episodeId = el.attr("data-id").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val number = el.attr("data-number").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val link = siteUrl(el.attr("href")) ?: return@mapNotNull null
            newEpisode(mapper.writeValueAsString(EpisodeData(episodeId, link))) {
                episode = number; name = el.selectFirst(".ep-name")?.text()?.ifBlank { "Episode $number" } ?: "Episode $number"
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }
        if (episodes.isEmpty()) throw ErrorLoadingException("AniwatchTV: no published episodes")
        fun info(label: String): String = doc.select(".anisc-info .item").firstOrNull {
            it.selectFirst(".item-head")?.text()?.trim()?.removeSuffix(":") == label
        }?.selectFirst(".name")?.text().orEmpty()
        val type = when (doc.selectFirst(".anisc-detail .film-stats .item")?.text()) {
            "Movie" -> TvType.AnimeMovie; "OVA" -> TvType.OVA; else -> TvType.Anime
        }
        Log.i(name, "ANIWATCH_DETAIL id=$id episodes=${episodes.size} audio=sub")
        return newAnimeLoadResponse(title, target, type) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst(".anisc-info .item-title .text")?.text()
                ?: doc.selectFirst(".anisc-detail .film-description")?.text()
            tags = doc.select(".anisc-info a[href*=/genre/]").map { it.text().trim() }.distinct()
            year = Regex("\\b(19|20)[0-9]{2}\\b").find(info("Aired"))?.value?.toIntOrNull()
            showStatus = when (info("Status")) {
                "Currently Airing" -> ShowStatus.Ongoing; "Finished Airing" -> ShowStatus.Completed; else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    internal data class EpisodeData(val id: Int = 0, val url: String = "")
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit): Boolean =
        AniwatchTVPlayer(mainUrl).load(data, subtitleCallback, callback)
}
