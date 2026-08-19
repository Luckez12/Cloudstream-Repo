package com.pencurimovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class Pencurimovie : MainAPI() {

    // Current working fallback. Website.json is still checked first so domain changes
    // can continue to be handled without rebuilding the plugin.
    override var mainUrl = "https://ww21.pencurimovie.sbs"
    private val mainUrlJson =
        "https://raw.githubusercontent.com/Asm0d3usX/CloudX/builds/Website.json"
    private var directUrl: String? = null

    override var name = "PencuriMovie"
    override val hasMainPage = true
    override var lang = "ms"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "series" to "TV Series",
        "most-rating" to "Most Rating Movies",
        "top-imdb" to "Top IMDB Movies",
        "country/malaysia" to "Malaysia Movies",
        "country/indonesia" to "Indonesia Movies",
        "country/india" to "India Movies",
        "country/japan" to "Japan Movies",
        "country/thailand" to "Thailand Movies",
        "country/china" to "China Movies",
    )

    private suspend fun loadMainUrlIfNeeded() {
        if (directUrl != null) return

        var candidate = mainUrl

        try {
            val response = app.get(mainUrlJson, timeout = 20L).text
            val json = JSONObject(response)
            val array = json.optJSONArray("pencurimovie")
            val jsonUrl = array?.optString(0)?.trim()?.removeSuffix("/")

            if (!jsonUrl.isNullOrBlank()) {
                candidate = jsonUrl
            }
        } catch (_: Exception) {
            // Keep the known working fallback above.
        }

        // Website.json may point to an older wwXX domain. Resolve its HTTP/meta
        // redirect and store only the origin as the provider base URL.
        mainUrl = try {
            getOrigin(followRedirect(candidate, maxHops = 4))
        } catch (_: Exception) {
            candidate.removeSuffix("/")
        }

        directUrl = mainUrl
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        loadMainUrlIfNeeded()

        val document = app.get(
            "$mainUrl/${request.data}/page/$page",
            timeout = 50L
        ).document

        val home = document
            .select("div.ml-item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val a = selectFirst("a")
        val title = a?.attr("oldtitle")
            ?.substringBefore("(")
            ?.trim()
            ?.ifEmpty { selectFirst("h2")?.text()?.trim() }
            ?: selectFirst("h2")?.text()?.trim().orEmpty()

        val href = fixUrl(a?.attr("href").orEmpty())
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.getImageAttr())

        val quality = selectFirst("span.mli-quality, div.jtip-quality")
            ?.text()
            ?.trim()
            ?.replace("-", "")
            .orEmpty()

        val epsCount = selectFirst("span.mli-eps i")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val isSeries = epsCount != null || selectFirst("span.mli-eps") != null

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(quality)
                if (epsCount != null) addSub(epsCount)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadMainUrlIfNeeded()

        val document = app.get(
            "$mainUrl?s=$query",
            timeout = 50L
        ).document

        return document
            .select("div.ml-item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()

        val document = app.get(url, timeout = 50L).document

        val title = document.selectFirst("div.mvic-desc h3")
            ?.text()
            ?.trim()
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()

        val poster = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            .orEmpty()

        val description = document
            .selectFirst("div.desc p.f-desc")
            ?.text()
            ?.trim()

        val isSeries = url.contains("/series/", ignoreCase = true) ||
            document.select("div.tvseason").isNotEmpty()

        val trailer = document
            .selectFirst("meta[itemprop=embedUrl]")
            ?.attr("content")
            .orEmpty()

        val genre = document
            .select("div.mvic-info p:contains(Genre) a")
            .map { it.text() }

        val rating = document
            .selectFirst("span.imdb-r[itemprop=ratingValue]")
            ?.text()
            ?.toDoubleOrNull()

        val duration = document
            .selectFirst("span[itemprop=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val actors = document
            .select("div.mvic-info p:contains(Actors) a")
            .map { it.text() }

        val year = document
            .select("div.mvic-info p:contains(Release) a")
            .text()
            .toIntOrNull()

        val recommendation = document
            .select("div.mlw-related div.ml-item")
            .mapNotNull { it.toSearchResult() }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()

            document.select("div.tvseason").forEach { info ->
                val season = info
                    .selectFirst("strong")
                    ?.text()
                    ?.substringAfter("Season", "")
                    ?.trim()
                    ?.toIntOrNull()

                info.select("div.les-content a").forEach { episodeElement ->
                    val episodeText = episodeElement.text().trim()
                    val episodeName = episodeText
                        .substringAfter("-", "")
                        .trim()
                        .ifBlank { episodeText }

                    val rawHref = episodeElement.attr("href")
                    val href = resolveUrl(url, rawHref)

                    val episodeNumber = episodeText
                        .substringAfter("Episode", "")
                        .substringBefore("-")
                        .trim()
                        .toIntOrNull()

                    if (href.isNotBlank()) {
                        episodes.add(
                            newEpisode(href) {
                                this.episode = episodeNumber
                                this.name = episodeName
                                this.season = season
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadMainUrlIfNeeded()

        val document = app.get(
            data,
            headers = mapOf("Referer" to mainUrl),
            timeout = 50L
        ).document

        // The site has used data-src for lazy-loaded players, but some servers use
        // src or other data attributes. Scan only player-related containers first.
        val playerElements = document.select(
            "div.movieplay iframe, " +
                "div.movieplay [data-src], " +
                "div.movieplay [data-video], " +
                "div.movieplay [data-url], " +
                "div#movieplay iframe, " +
                "div#player iframe, " +
                "div#player [data-src], " +
                "div.player iframe, " +
                "div.player [data-src], " +
                "div.playbox iframe, " +
                "div.playbox [data-src]"
        )

        val embedUrls = playerElements
            .mapNotNull { element ->
                element.getEmbedAttr()
                    .takeIf { it.isNotBlank() }
                    ?.let { resolveUrl(data, it) }
            }
            .filterNot { isNonVideoFrame(it) }
            .distinct()
            .toMutableList()

        // Fallback for pages where the player wrapper class changed. This is kept
        // separate so normal pages do not accidentally scan unrelated page embeds.
        if (embedUrls.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val raw = iframe.getEmbedAttr()
                if (raw.isNotBlank()) {
                    val resolved = resolveUrl(data, raw)
                    if (!isNonVideoFrame(resolved) && resolved !in embedUrls) {
                        embedUrls.add(resolved)
                    }
                }
            }
        }

        if (embedUrls.isEmpty()) return false

        val foundStream = AtomicBoolean(false)

        // Resolve all discovered servers concurrently without the deprecated apmap.
        coroutineScope {
            embedUrls.map { embedUrl ->
                async {
                    val finalUrl = followRedirect(embedUrl, maxHops = 5)

                    val matched = loadExtractor(
                        finalUrl,
                        data,
                        subtitleCallback
                    ) { link ->
                        foundStream.set(true)
                        callback(link)
                    }

                    // Some PencuriMovie entries point to an intermediate page instead
                    // of a registered extractor. If so, inspect one nested iframe and
                    // try again.
                    if (!matched) {
                        val nestedUrl = findNestedEmbed(finalUrl, data)
                        if (!nestedUrl.isNullOrBlank() && nestedUrl != finalUrl) {
                            val nestedFinal = followRedirect(nestedUrl, maxHops = 4)
                            loadExtractor(
                                nestedFinal,
                                finalUrl,
                                subtitleCallback
                            ) { link ->
                                foundStream.set(true)
                                callback(link)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return foundStream.get()
    }

    private suspend fun findNestedEmbed(
        url: String,
        referer: String
    ): String? {
        return try {
            val document = app.get(
                url,
                headers = mapOf("Referer" to referer),
                timeout = 25L
            ).document

            val iframe = document.selectFirst("iframe[data-src], iframe[src]")
                ?: return null

            val raw = iframe.getEmbedAttr()
            if (raw.isBlank()) null else resolveUrl(url, raw)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun followRedirect(
        url: String,
        maxHops: Int = 5
    ): String {
        var current = url.trim()
        if (current.isBlank()) return current

        repeat(maxHops) {
            val next = try {
                val response = app.get(
                    current,
                    allowRedirects = false,
                    timeout = 25L
                )

                val location = response.headers["Location"]
                    ?: response.headers["location"]

                if (!location.isNullOrBlank()) {
                    resolveUrl(current, location)
                } else {
                    val metaRefresh = response.document
                        .selectFirst("meta[http-equiv~=(?i)refresh]")
                        ?.attr("content")
                        ?.let { extractMetaRefreshUrl(it) }

                    if (!metaRefresh.isNullOrBlank()) {
                        resolveUrl(current, metaRefresh)
                    } else {
                        extractJavascriptRedirect(response.text)
                            ?.let { resolveUrl(current, it) }
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (next.isNullOrBlank() || next == current) {
                return current
            }

            current = next
        }

        return current
    }

    private fun extractMetaRefreshUrl(content: String): String? {
        val match = Regex(
            pattern = "(?i)url\\s*=\\s*['\\\"]?([^'\\\";]+)"
        ).find(content)

        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractJavascriptRedirect(html: String): String? {
        val patterns = listOf(
            Regex("(?i)window\\.location(?:\\.href)?\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"),
            Regex("(?i)location\\.href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"),
            Regex("(?i)location\\.replace\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*\\)")
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.trim()
        }
    }

    private fun resolveUrl(base: String, value: String): String {
        val target = value.trim()
        if (target.isBlank()) return ""

        return try {
            URI(base).resolve(target).toString()
        } catch (_: Exception) {
            target
        }
    }

    private fun getOrigin(url: String): String {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return url.removeSuffix("/")
            val host = uri.host ?: return url.removeSuffix("/")
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            url.removeSuffix("/")
        }
    }

    private fun isNonVideoFrame(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.contains("google.com/recaptcha") ||
            lower.contains("doubleclick.net")
    }

    private fun Element.getEmbedAttr(): String {
        return attr("data-src").trim()
            .ifBlank { attr("src").trim() }
            .ifBlank { attr("data-video").trim() }
            .ifBlank { attr("data-url").trim() }
            .ifBlank { attr("data-embed").trim() }
    }

    private fun Element.getImageAttr(): String {
        val srcAttr = attr("src").trim()
        val dataOriginal = attr("data-original").trim()
        val dataSrc = attr("data-src").trim()
        val dataLazySrc = attr("data-lazy-src").trim()

        return when {
            srcAttr.isNotBlank() && !srcAttr.startsWith("data:image") -> srcAttr
            dataOriginal.isNotBlank() && !dataOriginal.startsWith("data:image") -> dataOriginal
            dataSrc.isNotBlank() && !dataSrc.startsWith("data:image") -> dataSrc
            dataLazySrc.isNotBlank() && !dataLazySrc.startsWith("data:image") -> dataLazySrc
            else -> srcAttr
        }
    }
}
