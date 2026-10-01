package com.pencurimovie
import com.lagradost.cloudstream3.*
import android.util.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
class Pencurimovie : MainAPI() {
    override var mainUrl = "https://ww44.pencurimovie.baby"
    private var directUrl: String? = null
    override var name = "PencuriMovie 👾"
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
        } catch (e: CancellationException) {
            throw e
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
    private data class PlayerOption(val label: String, val url: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = try {
            withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                loadMainUrlIfNeeded()
                app.get(
                    rewriteToCurrentDomain(data),
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT),
                    timeout = 15L
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PencuriMovie", "PM_V5_PAGE_FAILED ${e.javaClass.simpleName}")
            null
        } ?: return false

        val pageUrl = response.url
        val players = collectPlayerOptions(response.document, pageUrl)
        Log.i("PencuriMovie", "PM_V5_DISCOVERY candidates=${players.size}")
        if (players.isEmpty()) return false

        val foundStream = AtomicBoolean(false)
        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val emittedSubtitles = ConcurrentHashMap.newKeySet<String>()
        val semaphore = Semaphore(MAX_EMBED_CONCURRENCY)
        val subtitles: (SubtitleFile) -> Unit = { subtitle ->
            if (emittedSubtitles.add(subtitle.url)) subtitleCallback(subtitle)
        }

        // One lane per host keeps duplicate wrappers from occupying every permit.
        // Emit every available quality. A failing server does not suppress others.
        withTimeoutOrNull(ALL_PLAYERS_TIMEOUT_MS) {
            coroutineScope {
                players.groupBy { URI(it.url).host.orEmpty().lowercase() }.values.map { serverPlayers ->
                    async {
                        semaphore.withPermit {
                            for (player in serverPlayers) {
                                val produced = AtomicBoolean(false)
                                try {
                                    val attempts = mutableSetOf<String>()
                                    val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                                    withTimeoutOrNull(EMBED_PIPELINE_TIMEOUT_MS) {
                                        resolvePlayer(player.url, pageUrl, 0, attempts, subtitles) { link -> links.add(link) }
                                    }
                                    // A local timeout may happen after an extractor already emitted links.
                                    // Preserve those partial results, as in Anichin's per-server pipeline.
                                    for (link in links.toList()) {
                                        if (link.url.isNotBlank() && emittedUrls.add(link.url)) {
                                            val namedLink = withServerName(link, player)
                                            callback(namedLink)
                                            produced.set(true)
                                            foundStream.set(true)
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("PencuriMovie", "PM_V5_SERVER_FAILED label=${player.label} error=${e.javaClass.simpleName}")
                                } finally {
                                    Log.i("PencuriMovie", "PM_V5_SERVER label=${player.label} success=${produced.get()}")
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        Log.i("PencuriMovie", "PM_V5_DONE emitted=${emittedUrls.size} success=${foundStream.get()}")
        return foundStream.get()
    }

    private suspend fun withServerName(link: ExtractorLink, player: PlayerOption): ExtractorLink {
        val serverName = player.label.ifBlank { URI(player.url).host.orEmpty() }
        val displayName = listOf(serverName, link.name).filter { it.isNotBlank() }.distinct().joinToString(" • ")
        return newExtractorLink(source = displayName, name = displayName, url = link.url, type = link.type) {
            this.referer = link.referer
            this.headers = link.headers
            this.quality = link.quality
            this.extractorData = link.extractorData
            this.audioTracks = link.audioTracks
        }
    }

    private fun collectPlayerOptions(document: Document, baseUrl: String): List<PlayerOption> {
        val found = linkedMapOf<String, PlayerOption>()
        fun add(raw: String, label: String) {
            val url = candidateUrl(baseUrl, raw) ?: return
            found.putIfAbsent(url, PlayerOption(label, url))
        }

        // MovieMo keeps all servers in hidden #tabN blocks. No AJAX click is needed.
        document.select(".player_nav .idTabs a[href]").forEach { anchor ->
            val tabId = anchor.attr("href").substringAfter('#', "")
            if (tabId.isBlank()) return@forEach
            val tab = document.getElementById(tabId) ?: return@forEach
            val label = anchor.parents().firstOrNull { it.tagName() == "li" }
                ?.selectFirst(".les-title strong")?.text()?.trim().orEmpty()
                .ifBlank { "Server" }
            collectEmbedUrls(tab, baseUrl).forEach { add(it, label) }
        }

        val playerArea = document.selectFirst("#player2, #content-embed, .content-embed, #movieplay, .movieplay")
        collectEmbedUrls(playerArea ?: document, baseUrl).forEach { add(it, "") }
        return found.values.take(MAX_TOP_LEVEL_PLAYERS)
    }

    private fun collectEmbedUrls(root: Element, baseUrl: String): List<String> {
        val found = linkedSetOf<String>()
        fun add(raw: String) { candidateUrl(baseUrl, raw)?.let(found::add) }
        root.select(
            "iframe, video[src], source[src], [data-src], [data-video], [data-url], " +
                "[data-embed], [data-link], [data-player], [data-iframe]"
        ).forEach { element -> element.getEmbedValues().forEach(::add) }
        root.select("script, textarea").forEach { script ->
            extractUrlsFromScripts(script.data().ifBlank { script.html() }).forEach(::add)
        }
        return found.toList()
    }

    private fun extractUrlsFromScripts(html: String): List<String> {
        val text = cleanCandidateUrl(html)
        return Regex(
            """(?i)["']?(?:src|file|source|url|embed|video|player)["']?\s*[:=]\s*["']([^"'\s<>]+)["']"""
        ).findAll(text).map { it.groupValues[1] }.distinct().take(MAX_NESTED_PLAYERS).toList()
    }

    private fun cleanCandidateUrl(value: String): String = value.trim().trim('"', '\'', ' ')
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")

    private fun candidateUrl(baseUrl: String, raw: String): String? {
        val clean = cleanCandidateUrl(raw)
        if (clean.isBlank() || clean.startsWith('#') ||
            clean.startsWith("javascript:", true) || clean.startsWith("data:", true)
        ) return null
        val resolved = resolveUrl(baseUrl, clean)
        if (resolved.substringBefore('#') == baseUrl.substringBefore('#') ||
            isNonVideoFrame(resolved) || !isLikelyPlayerUrl(resolved)
        ) return null
        return resolved
    }

    private fun isLikelyPlayerUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        val path = uri.path.orEmpty().lowercase()
        if (listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".css", ".js", ".ico", ".woff", ".woff2", ".ttf")
                .any { path.endsWith(it) }) return false
        val host = uri.host.lowercase()
        return listOf("google-analytics", "googletagmanager", "doubleclick.net", "facebook.com", "instagram.com",
            "t.me", "telegram.me", "twitter.com", "x.com", "schema.org", "w3.org")
            .none { host == it || host.endsWith(".$it") || host.contains("google-analytics") }
    }

    private fun directMediaType(url: String): ExtractorLinkType? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private suspend fun tryExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = directMediaType(url)
        if (type != null) {
            callback(newExtractorLink(source = name, name = name, url = url, type = type) {
                this.referer = referer
                this.headers = mapOf("User-Agent" to USER_AGENT)
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        val produced = AtomicBoolean(false)
        try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    if (link.url.isNotBlank()) {
                        produced.set(true)
                        callback(link)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PencuriMovie", "PM_V5_EXTRACTOR_FAILED host=${URI(url).host} error=${e.javaClass.simpleName}")
        }
        return produced.get()
    }

    private suspend fun resolvePlayer(
        url: String,
        referer: String,
        depth: Int,
        attempts: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!attempts.add("$url\u0000$referer")) return false
        if (tryExtractor(url, referer, subtitleCallback, callback)) return true

        // Keep redirects as fallback: known host extractors should get the original URL first.
        val response = try {
            withTimeoutOrNull(PLAYER_REQUEST_TIMEOUT_MS) {
                app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 8L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null } ?: return false
        val current = response.url
        val redirect = response.document.selectFirst("meta[http-equiv~=(?i)refresh]")
            ?.attr("content")?.let(::extractMetaRefreshUrl)
            ?: extractJavascriptRedirect(response.text)
        val targets = linkedSetOf<String>()
        if (current != url) targets.add(current)
        redirect?.let { candidateUrl(current, it) }?.let(targets::add)
        if (depth < MAX_NESTED_DEPTH) targets.addAll(collectEmbedUrls(response.document, current))
        if (depth >= MAX_NESTED_DEPTH) {
            // Even at the depth limit, an HTTP/meta redirect can reach an extractor.
            return targets.take(MAX_NESTED_PLAYERS).any { target ->
                val targetReferer = if (target == current) referer else current
                attempts.add("$target\u0000$targetReferer") && tryExtractor(target, targetReferer, subtitleCallback, callback)
            }
        }
        var produced = false
        for (target in targets.take(MAX_NESTED_PLAYERS)) {
            if (target == url || target == current && current == url) continue
            val targetReferer = if (target == current) referer else current
            if (resolvePlayer(target, targetReferer, depth + 1, attempts, subtitleCallback, callback)) produced = true
        }
        return produced
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
            } catch (e: CancellationException) {
                throw e
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
                target
            } else {
                URI(
                    currentUri.scheme ?: targetUri.scheme,
                    targetUri.userInfo,
                    currentUri.host,
                    currentUri.port,
                    targetUri.path,
                    targetUri.query,
                    targetUri.fragment
                ).toString()
            }
        } catch (_: Exception) {
            target
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
            val scheme = uri.scheme
                ?: return url.removeSuffix("/")
            val host = uri.host
                ?: return url.removeSuffix("/")
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
    private fun Element.getEmbedValues(): List<String> {
        return listOf(
            attr("data-src"),
            attr("src"),
            attr("data-video"),
            attr("data-url"),
            attr("data-embed"),
            attr("data-link"),
            attr("data-player"),
            attr("data-iframe"),
            attr("href")
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
    private fun Element.getImageAttr(): String {
        val srcAttr = attr("src").trim()
        val dataOriginal = attr("data-original").trim()
        val dataSrc = attr("data-src").trim()
        val dataLazySrc = attr("data-lazy-src").trim()
        return when {
            srcAttr.isNotBlank() &&
                !srcAttr.startsWith("data:image") -> srcAttr
            dataOriginal.isNotBlank() &&
                !dataOriginal.startsWith("data:image") -> dataOriginal
            dataSrc.isNotBlank() &&
                !dataSrc.startsWith("data:image") -> dataSrc
            dataLazySrc.isNotBlank() &&
                !dataLazySrc.startsWith("data:image") -> dataLazySrc
            else -> srcAttr
        }
    }
    private companion object {
        const val MAX_EMBED_CONCURRENCY = 4
        const val EMBED_PIPELINE_TIMEOUT_MS = 35_000L
        const val EXTRACTOR_TIMEOUT_MS = 26_000L
        const val PAGE_TIMEOUT_MS = 20_000L
        const val PLAYER_REQUEST_TIMEOUT_MS = 8_000L
        const val ALL_PLAYERS_TIMEOUT_MS = 70_000L
        const val MAX_NESTED_DEPTH = 2
        const val MAX_NESTED_PLAYERS = 8
        const val MAX_TOP_LEVEL_PLAYERS = 24
    }

}
