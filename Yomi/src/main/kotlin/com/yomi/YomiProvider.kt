package com.yomi

import android.util.Log
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.ExtractorLink

class YomiProvider : MainAPI() {
    override var mainUrl = "https://yomi.to"
    override var name = "Yomi"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val usesWebView = true
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

    private fun catalogueCard(node: com.fasterxml.jackson.databind.JsonNode): SearchResponse? {
        val id = node.path("id").asInt().takeIf { it > 0 } ?: return null
        val title = YomiCatalogue.title(node).takeIf { it.isNotBlank() } ?: return null
        val type = when (node.path("format").asText()) { "MOVIE" -> TvType.AnimeMovie; "OVA" -> TvType.OVA; else -> TvType.Anime }
        return newAnimeSearchResponse(title, "$mainUrl/anime/anime-$id", type) {
            posterUrl = node.path("coverImage").path("extraLarge").asText("")
                .ifBlank { node.path("coverImage").path("large").asText("") }.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(mainPage.any { it.data == request.data })
        return try {
            homepage(page, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "YOMI_HOME_ROW_FAILED section=${request.name.replace(' ', '_')} reason=${e.javaClass.simpleName}")
            // Do not report an unavailable catalogue as a successful, empty category.
            throw e
        }
    }

    private suspend fun homepage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination is not exposed as a verified server-rendered URL yet.
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val url = "$mainUrl${request.data}"
        val section = request.name.replace(' ', '_')
        fun parse(body: String, source: String): List<SearchResponse> {
            val document = Jsoup.parse(body, mainUrl)
            // Keep the verified card class but allow it outside the main wrapper.
            val cards = document.select("a.anime-card[href^=/anime/]")
            val decoded = YomiCatalogue.decode(document, body)
            YomiCatalogue.remember(decoded.items)
            val items = cards.mapNotNull { card(it) }.ifEmpty { decoded.items.mapNotNull { catalogueCard(it) } }
                .distinctBy { it.url }
            Log.i(name, "YOMI_HOME_PARSE section=$section source=$source body_length=${body.length} matched=${cards.size} chunks=${decoded.chunks} decode_errors=${decoded.errors} arrays=${decoded.arrays} embedded_items=${decoded.items.size} items=${items.size}")
            return items
        }
        var items: List<SearchResponse> = emptyList()
        for (fresh in listOf(false, true)) {
            val source = if (fresh) "http_revalidate" else "http"
            try {
                val response = withTimeoutOrNull(10_000L) {
                    app.get(url, referer = "$mainUrl/", headers = if (fresh)
                        mapOf("Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache") else emptyMap())
                }
                Log.i(name, "YOMI_HOME_HTTP section=$section source=$source status=${response?.code}")
                if (response != null && response.code in 200..299) items = parse(response.text, source)
                if (items.isNotEmpty()) break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(name, "YOMI_HOME_HTTP_FAILED section=$section source=$source reason=${e.javaClass.simpleName}") }
        }
        if (items.isEmpty()) {
            Log.i(name, "YOMI_HOME_RENDER section=$section action=start")
            // Two rendering slots serve four rows; include queue time for the second pair.
            val html = withTimeoutOrNull(28_000L) { YomiWeb.catalogue(url, "$mainUrl/") }.orEmpty()
            items = parse(html, "webview")
        }
        if (items.isEmpty()) throw ErrorLoadingException("Yomi: catalogue unavailable for ${request.name}. Please retry or export the diagnostic log.")
        Log.i(name, "YOMI_HOME section=$section items=${items.size} pagination=disabled")
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}", referer = "$mainUrl/")
        if (response.code !in 200..299) throw ErrorLoadingException("Yomi search HTTP ${response.code}")
        val embedded = YomiCatalogue.initial(response.document)
        YomiCatalogue.remember(embedded)
        return response.document.select("main a.anime-card[href^=/anime/]").mapNotNull { card(it) }.distinctBy { it.url }
            .ifEmpty { embedded.mapNotNull { catalogueCard(it) } }
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw ErrorLoadingException("Yomi: invalid anime URL")
        if (uri.host != URI(mainUrl).host) throw ErrorLoadingException("Yomi: invalid anime host")
        val slug = Regex("/(?:anime|watch)/([a-z0-9-]+-[0-9]+)").find(uri.path)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Yomi: invalid anime path")
        val id = slug.substringAfterLast('-').toIntOrNull() ?: throw ErrorLoadingException("Yomi: invalid anime ID")
        val detailUrl = "$mainUrl/anime/$slug"
        val catalogue = YomiCatalogue.cached(id)
        val response = if (catalogue != null) null else try {
            withTimeoutOrNull(8_000L) { app.get(detailUrl, referer = "$mainUrl/") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(name, "YOMI_DETAIL_METADATA_FAILED reason=${e.javaClass.simpleName}")
            null
        }
        val doc = response?.document ?: Jsoup.parse("")
        val metadata = doc.select("script[type=application/ld+json]").mapNotNull {
            runCatching { mapper.readTree(it.data()) }.getOrNull()
        }.firstOrNull { it.path("@type").asText() in setOf("TVSeries", "Movie") }
        val knownCount = catalogue?.let { YomiCatalogue.count(it) }
        val watch = if (knownCount == null) withTimeoutOrNull(12_000L) {
            YomiWeb.watch("$mainUrl/watch/$slug/1", collectServers = false)
        } else null
        val watchDoc = Jsoup.parse(watch?.html.orEmpty(), mainUrl)
        val episodes = if (knownCount != null) (1..knownCount).map { number ->
            newEpisode("$mainUrl/watch/$slug/$number") { episode = number; name = "Episode $number" }
        } else watchDoc.select("a[href^=/watch/]").mapNotNull { link ->
            val path = runCatching { URI(mainUrl).resolve(link.attr("href")).path }.getOrNull() ?: return@mapNotNull null
            val match = Regex("/watch/([a-z0-9-]+-$id)/([0-9]+)/?").matchEntire(path) ?: return@mapNotNull null
            val number = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            newEpisode("$mainUrl$path") {
                episode = number
                name = "Episode $number"
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }
        if (episodes.isEmpty() && knownCount != 0) throw ErrorLoadingException("Yomi: episode list unavailable. Please export the Yomi diagnostic log.")
        val title = catalogue?.let { YomiCatalogue.title(it) }.orEmpty().ifBlank { metadata?.path("name")?.asText("").orEmpty() }.ifBlank {
            watchDoc.title().substringBefore(" — Episode").removeSuffix(" | Yomi")
        }.ifBlank { doc.title().removeSuffix(" | Yomi") }
        val image = catalogue?.path("coverImage")?.path("extraLarge")?.asText("").orEmpty().ifBlank { metadata?.path("image")?.asText("").orEmpty() }.ifBlank {
            doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }
        val type = if (catalogue?.path("format")?.asText() == "MOVIE" || metadata?.path("@type")?.asText() == "Movie") TvType.AnimeMovie else TvType.Anime
        Log.i(name, "YOMI_DETAIL id=$id status=${response?.code} episodes=${episodes.size} source=${if (knownCount != null) "catalogue" else "watch"} status_hint=${catalogue?.path("status")?.asText()}")
        return newAnimeLoadResponse(title, detailUrl, type) {
            posterUrl = image.takeIf { it.isNotBlank() }
            plot = (catalogue?.path("description")?.asText() ?: metadata?.path("description")?.asText())?.let { Jsoup.parse(it).text() }
            tags = (catalogue?.path("genres") ?: metadata?.path("genre"))?.takeIf { it.isArray }?.map { it.asText() }
            year = catalogue?.path("seasonYear")?.asInt()?.takeIf { it > 0 }
            showStatus = when (catalogue?.path("status")?.asText()) { "FINISHED" -> ShowStatus.Completed; "RELEASING" -> ShowStatus.Ongoing; else -> null }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
        YomiPlayer(mainUrl).load(data, subtitleCallback, callback)
}
