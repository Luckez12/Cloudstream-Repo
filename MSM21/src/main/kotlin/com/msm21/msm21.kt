package com.msm21
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
class msm21 : MainAPI() {
    override var mainUrl = "https://ww21.pencurimovie.sbs"
    private var directUrl: String? = null
    override var name = "MSM21 👾"
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
        val candidate = mainUrl.removeSuffix("/")
        mainUrl = try {
            getOrigin(followRedirect(candidate, maxHops = 4))
        } catch (_: Exception) {
            candidate
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
        val href = rewriteToCurrentDomain(
            fixUrl(a?.attr("href").orEmpty())
        )
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
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query
        }
        val document = app.get(
            "$mainUrl?s=$encodedQuery",
            timeout = 50L
        ).document
        return document
            .select("div.ml-item")
            .mapNotNull { it.toSearchResult() }
    }
    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()
        val pageUrl = rewriteToCurrentDomain(url)
        val document = app.get(
            pageUrl,
            headers = mapOf("Referer" to mainUrl),
            timeout = 50L
        ).document
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
        val isSeries = pageUrl.contains("/series/", ignoreCase = true) ||
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
                    val href = rewriteToCurrentDomain(
                        resolveUrl(pageUrl, rawHref)
                    )
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
            newTvSeriesLoadResponse(
                title,
                pageUrl,
                TvType.TvSeries,
                episodes
            ) {
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
            newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl
            ) {
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
        val pageUrl = rewriteToCurrentDomain(data)
        val response = app.get(
            pageUrl,
            headers = mapOf("Referer" to mainUrl),
            timeout = 50L
        )
        val document = response.document
        val embedUrls = collectEmbedUrls(document, response.text, pageUrl)
        if (embedUrls.isEmpty()) return false
        val foundStream = AtomicBoolean(false)
        coroutineScope {
            embedUrls.map { embedUrl ->
                async {
                    try {
                        val finalUrl = followRedirect(embedUrl, maxHops = 5)
                        val matched = loadExtractor(
                            finalUrl,
                            pageUrl,
                            subtitleCallback
                        ) { link ->
                            foundStream.set(true)
                            callback(link)
                        }
                        if (!matched) {
                            val nestedUrl = findNestedEmbed(
                                finalUrl,
                                pageUrl
                            )
                            if (!nestedUrl.isNullOrBlank() &&
                                nestedUrl != finalUrl
                            ) {
                                val nestedFinal = followRedirect(
                                    nestedUrl,
                                    maxHops = 4
                                )
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
                    } catch (_: Exception) {
                        // One dead server must not block the remaining servers.
                    }
                }
            }.awaitAll()
        }
        return foundStream.get()
    }
    private fun collectEmbedUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()
        fun addCandidate(rawValue: String) {
            val cleaned = cleanCandidateUrl(rawValue)
            if (cleaned.isBlank()) return
            if (cleaned.startsWith("#")) return
            if (cleaned.startsWith("javascript:", ignoreCase = true)) return
            val resolved = resolveUrl(baseUrl, cleaned)
            if (resolved.isBlank()) return
            if (resolved == baseUrl) return
            if (isNonVideoFrame(resolved)) return
            if (!isLikelyPlayerUrl(resolved)) return
            found.add(resolved)
        }
        val focusedSelectors = listOf(
            "div.movieplay iframe",
            "div.movieplay [data-src]",
            "div.movieplay [data-video]",
            "div.movieplay [data-url]",
            "div.movieplay [data-embed]",
            "div.movieplay [data-link]",
            "div#movieplay iframe",
            "div#movieplay [data-src]",
            "div#movieplay [data-video]",
            "div#movieplay [data-url]",
            "div#player iframe",
            "div#player [data-src]",
            "div#player [data-video]",
            "div#player [data-url]",
            "div.player iframe",
            "div.player [data-src]",
            "div.player [data-video]",
            "div.player [data-url]",
            "div.playbox iframe",
            "div.playbox [data-src]",
            "[id*=server] iframe",
            "[id*=server] [data-src]",
            "[id*=server] [data-video]",
            "[id*=server] [data-url]",
            "[class*=server] iframe",
            "[class*=server] [data-src]",
            "[class*=server] [data-video]",
            "[class*=server] [data-url]"
        ).joinToString(", ")
        document.select(focusedSelectors).forEach { element ->
            element.getEmbedValues().forEach(::addCandidate)
        }
        if (found.isEmpty()) {
            document.select(
                "iframe, [data-src], [data-video], [data-url], " +
                    "[data-embed], [data-link], [data-player]"
            ).forEach { element ->
                element.getEmbedValues().forEach(::addCandidate)
            }
        }
        if (found.isEmpty()) {
            document.select(
                "a[href][class*=server], " +
                    "[class*=server] a[href], " +
                    "[id*=server] a[href], " +
                    "a[href][class*=play], " +
                    "[class*=play] a[href]"
            ).forEach { element ->
                addCandidate(element.attr("href"))
            }
        }
        if (found.isEmpty()) {
            extractUrlsFromScripts(html)
                .take(24)
                .forEach(::addCandidate)
        }
        return found.toList()
    }
    private fun extractUrlsFromScripts(html: String): List<String> {
        val results = linkedSetOf<String>()
        val patterns = listOf(
            Regex(
                """(?i)(?:src|file|url|embed|video|player)["']?\s*[:=]\s*["'](https?:[^"'\s<]+)["']"""
            ),
            Regex(
                """(?i)(?:iframe|source)["']?\s*[:=]\s*["'](https?:[^"'\s<]+)["']"""
            )
        )
        patterns.forEach { regex ->
            regex.findAll(html).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(results::add)
            }
        }
        return results.toList()
    }
    private fun cleanCandidateUrl(value: String): String {
        return value
            .trim()
            .trim('"', '\'', ' ')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
    }
    private fun isLikelyPlayerUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") &&
            !lower.startsWith("https://")
        ) {
            return false
        }
        val blockedExtensions = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
            ".css", ".js", ".woff", ".woff2", ".ttf", ".ico"
        )
        if (blockedExtensions.any { ext ->
                lower.substringBefore("?").endsWith(ext)
            }
        ) {
            return false
        }
        val blockedHostsOrPaths = listOf(
            "google-analytics",
            "googletagmanager",
            "doubleclick.net",
            "facebook.com",
            "instagram.com",
            "t.me/",
            "telegram.me/",
            "twitter.com",
            "x.com/",
            "schema.org",
            "w3.org"
        )
        if (blockedHostsOrPaths.any { lower.contains(it) }) {
            return false
        }
        return true
    }
    private suspend fun findNestedEmbed(
        url: String,
        referer: String
    ): String? {
        return try {
            val response = app.get(
                url,
                headers = mapOf("Referer" to referer),
                timeout = 25L
            )
            val document = response.document
            val element = document.selectFirst(
                "iframe[data-src], iframe[src], " +
                    "[data-video], [data-url], [data-embed], [data-link]"
            )
            val direct = element
                ?.getEmbedValues()
                ?.firstOrNull { it.isNotBlank() }
                ?.let { resolveUrl(url, cleanCandidateUrl(it)) }
            if (!direct.isNullOrBlank() &&
                !isNonVideoFrame(direct)
            ) {
                direct
            } else {
                extractUrlsFromScripts(response.text)
                    .firstOrNull()
                    ?.let { resolveUrl(url, cleanCandidateUrl(it)) }
                    ?.takeIf { !isNonVideoFrame(it) }
            }
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
            Regex(
                "(?i)window\\.location(?:\\.href)?\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"
            ),
            Regex(
                "(?i)location\\.href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"
            ),
            Regex(
                "(?i)location\\.replace\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*\\)"
            )
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
        }
    }
    private fun rewriteToCurrentDomain(url: String): String {
        val target = url.trim()
        if (target.isBlank()) return target
        return try {
            val targetUri = URI(target)
            val currentUri = URI(mainUrl)
            val targetHost = targetUri.host
                ?: return target
            val isPencuriMovieHost = targetHost.contains(
                "pencurimovie",
                ignoreCase = true
            )
            if (!isPencuriMovieHost ||
                currentUri.host.isNullOrBlank()
            ) {
                
