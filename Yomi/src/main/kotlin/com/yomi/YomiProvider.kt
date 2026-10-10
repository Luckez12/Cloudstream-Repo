package com.yomi

import android.util.Log
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URI

class YomiProvider : MainAPI() {
    override var mainUrl = "https://yomi.to"
    override var name = "Yomi"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "/browse?sort=TRENDING_DESC" to "Trending",
        "/browse?sort=SCORE_DESC" to "Top Rated",
        "/browse?sort=START_DATE_DESC" to "New Releases",
        "/browse?format=MOVIE" to "Movies"
    )

    private fun card(element: Element): SearchResponse? {
        val uri = runCatching { URI(mainUrl).resolve(element.attr("href")) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("https", "http") || uri.host != URI(mainUrl).host ||
            !uri.path.matches(Regex("/anime/[a-z0-9-]+-[0-9]+/?"))) return null
        val image = element.selectFirst("img")
        val title = element.select("h3").lastOrNull()?.text().orEmpty()
            .ifBlank { image?.attr("alt").orEmpty() }
        if (title.isBlank()) return null
        val format = when {
            Regex("\\bMOVIE\\b").containsMatchIn(element.text()) -> TvType.AnimeMovie
            Regex("\\bOVA\\b").containsMatchIn(element.text()) -> TvType.OVA
            else -> TvType.Anime
        }
        val poster = image?.attr("src")?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { URI(mainUrl).resolve(raw) }.getOrNull()
                ?.takeIf { it.scheme in setOf("https", "http") }?.toString()
        }
        return newAnimeSearchResponse(title, uri.toString(), format) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(mainPage.any { it.data == request.data })
        return try {
            homepage(page, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed row must not cancel the other concurrently requested rows.
            Log.w(name, "YOMI_HOME_ROW_FAILED section=${request.name.replace(' ', '_')} reason=${e.javaClass.simpleName}")
            newHomePageResponse(HomePageList(request.name, emptyList()), false)
        }
    }

    private suspend fun homepage(page: Int, request: MainPageRequest): HomePageResponse {
        // Stage 1 only fetches the first server-rendered catalogue page.
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val response = withTimeoutOrNull(15_000L) {
            app.get("$mainUrl${request.data}", referer = "$mainUrl/")
        } ?: throw ErrorLoadingException("Yomi: catalogue request timed out.")
        Log.i(name, "YOMI_HOME section=${request.name.replace(' ', '_')} status=${response.code}")
        if (response.code !in 200..299) throw ErrorLoadingException("Yomi: catalogue HTTP ${response.code}.")
        // Verified from Yomi's live Browse cards, not inferred from another provider.
        val document = response.document
        val cards = document.select("main a.anime-card[href^=/anime/]")
        val items = cards.mapNotNull { card(it) }.distinctBy { it.url }
        val embeddedCatalogue = document.select("script").any { it.data().contains("initialAnime") }
        Log.i(name, "YOMI_HOME section=${request.name.replace(' ', '_')} matched=${cards.size} items=${items.size} embedded_catalogue=$embeddedCatalogue pagination=stage1_disabled")
        if (items.isEmpty()) Log.w(name, "YOMI_HOME_ROW_EMPTY section=${request.name.replace(' ', '_')} matched=${cards.size} embedded_catalogue=$embeddedCatalogue")
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), false)
    }

    override suspend fun load(url: String): LoadResponse =
        throw ErrorLoadingException("Yomi stage 1: homepage test only. Details and playback are not implemented yet.")
}
