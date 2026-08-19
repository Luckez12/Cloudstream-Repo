package com.fourkhdhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class FourKHDHubProvider : MainAPI() {

    companion object {
        // Shared across provider instances for the lifetime of the app process.
        // CloudflareKiller clears WebView cookies when constructed, so creating
        // one per provider instance causes unnecessary re-challenges.
        private val sharedCloudflareKiller by lazy { CloudflareKiller() }
        private val sharedCloudflareMutex = Mutex()
        private val cloudflareStatusCodes = setOf(403, 503)
    }

    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/movies/" to "Movies",
        "category/series/" to "Series",
        "category/korean-series/" to "Korean Series",
        "category/english-series/" to "English Series",
        "category/hindi-series/" to "Hindi Series",
        "category/drama-series/" to "Drama Series",
        "category/netflix/" to "Netflix",
        "category/amazon_prime_video/" to "Amazon Prime Video",
        "category/jiohotstar/" to "JioHotstar",
        "category/disney/" to "Disney+",
        "category/apple_tv/" to "Apple TV+",
        "category/hbo_max/" to "HBO Max",
        "category/hulu/" to "Hulu",
        "category/crave/" to "Crave",
    )

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val detailPathRegex =
        Regex("^/[^/]+-(movie|series)-\\d+/?$", RegexOption.IGNORE_CASE)

    private val seasonEpisodeRegex =
        Regex("\\bS(\\d{1,2})E(\\d{1,3})\\b", RegexOption.IGNORE_CASE)

    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")

    private fun log(message: String) {
        println("[4KHDHub] $message")
    }

    /**
     * Fetch a 4KHDHub HTML page while reusing Cloudflare clearance across
     * provider instances. Once a host has a saved clearance cookie, go through
     * the interceptor immediately instead of intentionally taking another 403.
     */
    private suspend fun fetchSiteDocument(
        url: String,
        referer: String = "$mainUrl/",
    ): Document {
        val host = hostOf(url)

        suspend fun requestWithCloudflare(): Document {
            val response = app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 18L,
                interceptor = sharedCloudflareKiller,
            )

            log("cloudflare request host=$host code=${response.code}")

            if (!response.isSuccessful) {
                val status = response.code
                response.okhttpResponse.close()
                throw IllegalStateException("HTTP $status from $host")
            }

            return response.document
        }

        // CloudflareKiller exposes its per-host cookie cache. Reuse it directly
        // so later home/search/detail requests avoid a guaranteed 403 first.
        if (sharedCloudflareKiller.savedCookies.containsKey(host)) {
            try {
                return requestWithCloudflare()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log("cached Cloudflare request failed host=$host: ${error.message}")
                // Cookie may have expired. Remove it so the next intercepted
                // request is allowed to solve the challenge again.
                sharedCloudflareKiller.savedCookies.remove(host)
            }
        }

        val first = app.get(
            url,
            headers = headers,
            referer = referer,
            timeout = 18L,
        )

        log("request host=$host code=${first.code}")

        if (first.code !in cloudflareStatusCodes) {
            if (!first.isSuccessful) {
                val status = first.code
                first.okhttpResponse.close()
                throw IllegalStateException("HTTP $status from $host")
            }
            return first.document
        }

        val blockedStatus = first.code
        val server = first.headers["Server"].orEmpty().ifBlank { "?" }
        first.okhttpResponse.close()

        log(
            "blocked host=$host code=$blockedStatus server=$server; " +
                "trying Cloudflare fallback",
        )

        return sharedCloudflareMutex.withLock {
            // Another concurrent request may have solved the challenge while
            // this coroutine was waiting for the mutex.
            if (sharedCloudflareKiller.savedCookies.containsKey(host)) {
                return@withLock requestWithCloudflare()
            }

            requestWithCloudflare()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = try {
            withTimeoutOrNull(45_000L) { fetchSiteDocument(url) }
                ?: throw IllegalStateException("4KHDHub page timeout after 45s")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("home failed page=$page name=${request.name}: ${error.message}")
            return newHomePageResponse(
                HomePageList(
                    request.name,
                    emptyList(),
                    isHorizontalImages = false,
                ),
                hasNext = false,
            )
        }

        val items = parseCards(document)
        val hasNext = hasNextPage(document, page)

        log(
            "home page=$page name=${request.name} items=${items.size} " +
                "hasNext=$hasNext",
        )

        return newHomePageResponse(
            HomePageList(
                request.name,
                items,
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }

    override suspend fun quickSearch(
        query: String,
    ): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())

        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
        )

        for (url in urls) {
            val results = try {
                val document = withTimeoutOrNull(45_000L) { fetchSiteDocument(url) }
                    ?: throw IllegalStateException("4KHDHub search timeout after 45s")

                parseCards(document)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log("search request failed url=$url: ${error.message}")
                emptyList()
            }

            if (results.isNotEmpty()) {
                log("search '$cleanQuery' results=${results.size}")
                return results
            }
        }

        log("search '$cleanQuery' returned 0 results")
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = normalizeDetailUrl(url)

        val document = try {
            withTimeoutOrNull(45_000L) { fetchSiteDocument(cleanUrl) }
                ?: throw IllegalStateException("4KHDHub detail timeout after 45s")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "4KHDHub detail request failed: ${error.message}",
            )
        }

        val isSeries = isSeriesUrl(cleanUrl)

        val rawTitle = document.selectFirst("h1")
            ?.text()
            ?.cleanSpaces()
            ?.ifBlank { null }
            ?: titleFromUrl(cleanUrl)

        val title = cleanDetailTitle(rawTitle)

        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")
                ?.attr("content"),
            document.selectFirst("article img, main img, .entry-content img")
                ?.getImageUrl(),
        )
            .filterNotNull()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.let { fixUrlNull(it) }

        val plot = parsePlot(document)
        val year = parseYear(document)
        val rating = parseRating(document)
        val actors = parseActors(document)

        log(
            "load type=${if (isSeries) "series" else "movie"} " +
                "title=$title year=${year ?: "?"}",
        )

        return if (isSeries) {
            val episodes = parseEpisodes(document, poster)

            if (episodes.isEmpty()) {
                log("series '$title' returned no episode labels")
            }

            newTvSeriesLoadResponse(
                title,
                cleanUrl,
                TvType.TvSeries,
                episodes,
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year

                if (actors.isNotEmpty()) {
                    addActors(actors)
                }

                if (rating != null) {
                    addScore(rating.toString(), 10)
                }
            }
        } else {
            val movieLinks = parseMovieLinks(document)
            log("movie '$title' playLinks=${movieLinks.size}")

            newMovieLoadResponse(
                title,
                cleanUrl,
                TvType.Movie,
                movieLinks.toJson(),
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year

                if (actors.isNotEmpty()) {
                    addActors(actors)
                }

                if (rating != null) {
                    addScore(rating.toString(), 10)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val rawLinks = try {
            parseJson<List<String>>(data)
        } catch (_: Throwable) {
            emptyList()
        }
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
            .toList()

        if (rawLinks.isEmpty()) {
            log("loadLinks received no server links")
            return false
        }

        val resolveSemaphore = Semaphore(4)
        val extractSemaphore = Semaphore(4)
        val retrySemaphore = Semaphore(2)

        data class ServerTarget(
            val original: String,
            val resolved: String,
        )

        // Resolve redirect wrappers first, then dedupe the actual targets.
        // Different 4KHDHub buttons can lead to the same HubCloud page.
        val resolvedTargets = Collections.synchronizedList(mutableListOf<ServerTarget>())

        rawLinks.amap { rawLink ->
            resolveSemaphore.withPermit {
                val resolvedResult = withTimeoutOrNull(8_000L) {
                    try {
                        if (rawLink.contains("id=", ignoreCase = true)) {
                            resolveFourKRedirect(rawLink)
                        } else {
                            rawLink
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log("redirect failed host=${hostOf(rawLink)}: ${error.message}")
                        rawLink
                    }
                }

                val resolved = if (resolvedResult == null) {
                    log("redirect timeout host=${hostOf(rawLink)} after=8s; using original")
                    rawLink
                } else {
                    resolvedResult
                }.trim()

                if (resolved.isNotBlank()) {
                    resolvedTargets.add(ServerTarget(rawLink, resolved))
                }
            }
        }

        val targets = resolvedTargets
            .distinctBy { canonicalServerKey(it.resolved) }

        log(
            "loadLinks raw=${rawLinks.size} resolved=${resolvedTargets.size} " +
                "uniqueTargets=${targets.size}",
        )

        if (targets.isEmpty()) return false

        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val firstPassLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val retryCandidates = Collections.synchronizedList(mutableListOf<ServerTarget>())
        val emitLock = Any()

        val hubCloud = HubCloudExtractor()
        val hubDrive = HubDriveExtractor()

        suspend fun extractOne(target: ServerTarget, retry: Boolean): List<ExtractorLink> {
            val local = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val localUrls = ConcurrentHashMap.newKeySet<String>()
            val localCallback: (ExtractorLink) -> Unit = { link ->
                if (localUrls.add(link.url)) local.add(link)
            }

            val resolved = target.resolved
            val host = hostOf(resolved)

            try {
                when {
                    resolved.contains("hubcloud", ignoreCase = true) -> {
                        hubCloud.getUrl(
                            resolved,
                            mainUrl,
                            subtitleCallback,
                            localCallback,
                        )
                    }

                    resolved.contains("hubdrive", ignoreCase = true) -> {
                        hubDrive.getUrl(
                            resolved,
                            mainUrl,
                            subtitleCallback,
                            localCallback,
                        )
                    }

                    else -> {
                        loadExtractor(
                            resolved,
                            mainUrl,
                            subtitleCallback,
                            localCallback,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log("extract failed host=$host retry=$retry: ${error.message}")
            }

            // If the resolved target produced nothing, try the original wrapper.
            if (retry && local.isEmpty() && resolved != target.original) {
                try {
                    loadExtractor(
                        target.original,
                        mainUrl,
                        subtitleCallback,
                        localCallback,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log(
                        "raw fallback failed host=${hostOf(target.original)} " +
                            "retry=$retry: ${error.message}",
                    )
                }
            }

            return local.toList()
        }

        fun emitOrdered(source: List<ExtractorLink>): Int {
            return synchronized(emitLock) {
                var emitted = 0
                source
                    .distinctBy { it.url }
                    .sortedBy { streamPriority(it) }
                    .forEach { link ->
                        if (emittedUrls.add(link.url)) {
                            callback(link)
                            emitted++
                        }
                    }
                emitted
            }
        }

        targets.amap { target ->
            extractSemaphore.withPermit {
                val foundResult = withTimeoutOrNull(18_000L) {
                    extractOne(target, retry = false)
                }
                if (foundResult == null) {
                    log("extract timeout host=${hostOf(target.resolved)} retry=false after=18s")
                }
                val found = foundResult.orEmpty()
                if (found.isEmpty()) {
                    retryCandidates.add(target)
                } else {
                    firstPassLinks.addAll(found)
                }
            }
        }

        val firstEmitted = emitOrdered(firstPassLinks.toList())

        val recoveredLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        retryCandidates
            .distinctBy { canonicalServerKey(it.resolved) }
            .amap { target ->
                retrySemaphore.withPermit {
                    val recoveredResult = withTimeoutOrNull(8_000L) {
                        extractOne(target, retry = true)
                    }
                    if (recoveredResult == null) {
                        log("extract timeout host=${hostOf(target.resolved)} retry=true after=8s")
                    }
                    val recovered = recoveredResult.orEmpty()
                    if (recovered.isNotEmpty()) recoveredLinks.addAll(recovered)
                }
            }

        val recoveredEmitted = emitOrdered(recoveredLinks.toList())
        val orderedNames = (firstPassLinks.toList() + recoveredLinks.toList())
            .distinctBy { it.url }
            .sortedBy { streamPriority(it) }
            .joinToString(" | ") { it.name }

        log(
            "loadLinks targets=${targets.size} first=$firstEmitted " +
                "retry=${retryCandidates.distinctBy { canonicalServerKey(it.resolved) }.size} " +
                "recovered=$recoveredEmitted unique=${emittedUrls.size} " +
                "order=$orderedNames",
        )

        return emittedUrls.isNotEmpty()
    }

    private fun canonicalServerKey(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.trimEnd('/')
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        val path = uri.path.orEmpty().trimEnd('/')

        if (path.endsWith("/hubcloud.php", ignoreCase = true)) {
            val params = uri.rawQuery
                .orEmpty()
                .split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=', "").lowercase()
                    val value = part.substringAfter('=', "")
                    if (key.isBlank()) null else key to value
                }
                .toMap()

            val id = params["id"].orEmpty()
            val sourceHost = params["host"].orEmpty()
            if (id.isNotBlank()) {
                return "$host$path?host=$sourceHost&id=$id"
            }
        }

        return "$host$path?${uri.rawQuery.orEmpty()}"
    }

    private fun streamPriority(link: ExtractorLink): Int {
        val value = "${link.name} ${link.url}".lowercase()

        return when {
            Regex("(?:\\bavc\\b|\\bx264\\b|\\bh[\\s._-]?264\\b)")
                .containsMatchIn(value) -> 0

            Regex("(?:\\bhevc\\b|\\bx265\\b|\\bh[\\s._-]?265\\b)")
                .containsMatchIn(value) -> 3

            value.contains("10gbps") -> 2
            else -> 1
        }
    }

    private fun parseMovieLinks(document: Document): List<String> {
        val primary = document
            .select("div.download-item a[href]")
            .mapNotNull { anchor -> anchor.playHref() }
            .distinct()

        if (primary.isNotEmpty()) {
            return primary
        }

        return document
            .select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.playHref() ?: return@mapNotNull null
                href.takeIf {
                    it.contains("hubcloud", ignoreCase = true) ||
                        it.contains("hubdrive", ignoreCase = true)
                }
            }
            .distinct()
    }

    private fun Element.playHref(): String? {
        return absUrl("href")
            .ifBlank { attr("href") }
            .trim()
            .takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun hostOf(url: String): String {
        return runCatching {
            URI(url).host
                ?.removePrefix("www.")
                ?.lowercase()
        }.getOrNull().orEmpty().ifBlank { "unknown" }
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val cleanData = data.trim('/')

        val base = if (cleanData.isBlank()) {
            "$mainUrl/"
        } else {
            "$mainUrl/$cleanData/"
        }

        return if (page <= 1) {
            base
        } else {
            "${base}page/$page/"
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val directNext = document.selectFirst(
            "a[rel=next], a.next, .next a, .nav-links a.next, " +
                ".pagination a.next, a:matchesOwn((?i)^Next$)",
        )

        if (directNext != null) {
            return true
        }

        val expectedNext = "/page/${page + 1}/"

        return document.select("a[href]").any { anchor ->
            val href = anchor.attr("href")
            href.contains(expectedNext)
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val grouped = linkedMapOf<String, MutableList<Element>>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.absUrl("href")
                .ifBlank { fixUrl(anchor.attr("href")) }
                .let { normalizeDetailUrl(it) }

            if (!isDetailUrl(href)) {
                return@forEach
            }

            grouped.getOrPut(href) { mutableListOf() }.add(anchor)
        }

        return grouped.mapNotNull { (href, anchors) ->
            toSearchResult(href, anchors)
        }
    }

    private fun toSearchResult(
        href: String,
        anchors: List<Element>,
    ): SearchResponse? {
        if (anchors.isEmpty()) return null

        val image = anchors.asSequence()
            .mapNotNull { it.selectFirst("img") }
            .firstOrNull()
            ?: anchors.asSequence()
                .mapNotNull { anchor ->
                    anchor.parent()?.selectFirst("img")
                }
                .firstOrNull()

        val poster = image
            ?.getImageUrl()
            ?.let { fixUrlNull(it) }

        val title = firstUsefulTitle(anchors, image)
            ?: titleFromUrl(href)

        if (title.isBlank()) return null

        return if (isSeriesUrl(href)) {
            newAnimeSearchResponse(
                title,
                href,
                TvType.TvSeries,
            ) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie,
            ) {
                posterUrl = poster
            }
        }
    }

    private fun firstUsefulTitle(
        anchors: List<Element>,
        image: Element?,
    ): String? {
        val imageAlt = image
            ?.attr("alt")
            ?.cleanSpaces()
            ?.removePrefix("Poster Image of ")
            ?.removePrefix("Poster image of ")
            ?.ifBlank { null }

        if (imageAlt != null) {
            return cleanCardTitle(imageAlt)
        }

        anchors.asSequence()
            .map { it.attr("title").cleanSpaces() }
            .firstOrNull { it.isNotBlank() }
            ?.let { return cleanCardTitle(it) }

        anchors.asSequence()
            .map { it.text().cleanSpaces() }
            .firstOrNull { text ->
                text.isNotBlank() &&
                    text.length <= 160 &&
                    !text.startsWith("Download ", ignoreCase = true) &&
                    !text.equals("Watch Online Trailer", ignoreCase = true)
            }
            ?.let { return cleanCardTitle(it) }

        return null
    }

    private fun cleanCardTitle(value: String): String {
        return value
            .cleanSpaces()
            .replace(
                Regex("\\s+•\\s+S\\d+.*$", RegexOption.IGNORE_CASE),
                "",
            )
            .replace(Regex("\\s+\\((19|20)\\d{2}\\)$"), "")
            .trim()
    }

    private fun cleanDetailTitle(value: String): String {
        return value
            .cleanSpaces()
            .replace(Regex("\\s+\\((19|20)\\d{2}\\)$"), "")
            .trim()
    }

    private fun titleFromUrl(url: String): String {
        val path = runCatching { URI(url).path }
            .getOrNull()
            .orEmpty()
            .trim('/')

        val slug = path
            .substringAfterLast('/')
            .replace(
                Regex("-(movie|series)-\\d+$", RegexOption.IGNORE_CASE),
                "",
            )

        return slug
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase()
                    } else {
                        char.toString()
                    }
                }
            }
            .trim()
    }

    private fun normalizeDetailUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url

        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return url.substringBefore('#').substringBefore('?')
        }

        return "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
    }

    private fun isDetailUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false

        val host = uri.host
            ?.removePrefix("www.")
            ?.lowercase()
            ?: return false

        val expectedHost = URI(mainUrl).host
            ?.removePrefix("www.")
            ?.lowercase()
            ?: return false

        return host == expectedHost &&
            detailPathRegex.matches(uri.path.orEmpty())
    }

    private fun isSeriesUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }
            .getOrNull()
            .orEmpty()

        return Regex("-series-\\d+/?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(path)
    }

    private fun parsePlot(document: Document): String? {
        val blocked = listOf(
            "Director:",
            "Stars:",
            "Release:",
            "Print:",
            "Prints:",
            "Audios:",
            "Audio:",
            "Seasons:",
            "Uploader Notes",
            "Download Links",
            "Download Complete Season",
            "Download Individual Episodes",
            "How to Download",
            "Episodes ",
        )

        val selectors =
            "main p, article p, .entry-content p, .post-content p, .content p"

        val paragraph = document.select(selectors)
            .asSequence()
            .map { it.text().cleanSpaces() }
            .firstOrNull { text ->
                text.length >= 55 &&
                    blocked.none { prefix ->
                        text.startsWith(prefix, ignoreCase = true)
                    } &&
                    !text.contains("4KHDHub.Com", ignoreCase = true) &&
                    !text.contains(
                        "click on the download",
                        ignoreCase = true,
                    )
            }

        if (!paragraph.isNullOrBlank()) {
            return paragraph
        }

        return document.selectFirst("meta[name=description]")
            ?.attr("content")
            ?.cleanSpaces()
            ?.takeIf { it.length >= 40 }
    }

    private fun parseYear(document: Document): Int? {
        val preferred = listOf(
            document.title(),
            document.selectFirst("h1")?.text().orEmpty(),
        )

        preferred.forEach { text ->
            yearRegex.find(text)
                ?.value
                ?.toIntOrNull()
                ?.let { return it }
        }

        val body = document.body()?.text().orEmpty()

        Regex(
            "Release:\\s*((19|20)\\d{2})",
            RegexOption.IGNORE_CASE,
        )
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        return null
    }

    private fun parseRating(document: Document): Double? {
        val body = document.body()?.text().orEmpty()

        return Regex(
            "\\bIMDb\\s+([0-9](?:\\.[0-9])?)\\b",
            RegexOption.IGNORE_CASE,
        )
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun parseActors(document: Document): List<String> {
        val body = document.body()?.text().orEmpty()

        val starsText = Regex(
            "Stars:\\s*(.+?)(?=\\s+(?:Release:|Last Air:|Print:|Prints:|Audios:|Seasons:|Uploader Notes|Download))",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL,
            ),
        )
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanSpaces()
            ?: return emptyList()

        return starsText
            .split(',')
            .map { actor ->
                actor.replace(Regex("\\s*\\([^)]*\\)\\s*"), "")
                    .cleanSpaces()
            }
            .filter { it.isNotBlank() && it.length <= 80 }
            .distinct()
    }

    private fun parseEpisodes(
        document: Document,
        poster: String?,
    ): List<Episode> {
        val episodeLinks =
            linkedMapOf<Pair<Int, Int>, MutableList<String>>()

        /*
         * Current 4KHDHub layout.
         * Each episode-download-item contains the visible Episode-XX badge
         * and its own server buttons. Repeated quality blocks are merged
         * under the same season + episode key.
         */
        document
            .select("div.episodes-list div.season-item")
            .forEach { seasonItem ->
                val seasonText = seasonItem
                    .select("div.episode-number")
                    .text()

                val season = Regex(
                    "S?([1-9][0-9]*)",
                    RegexOption.IGNORE_CASE,
                )
                    .find(seasonText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@forEach

                seasonItem
                    .select("div.episode-download-item")
                    .forEach { episodeItem ->
                        val episodeText = episodeItem
                            .select(
                                "div.episode-file-info span.badge-psa",
                            )
                            .text()

                        val episode = Regex(
                            "Episode-0*([1-9][0-9]*)",
                            RegexOption.IGNORE_CASE,
                        )
                            .find(episodeText)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: return@forEach

                        val links = episodeItem
                            .select("a[href]")
                            .mapNotNull { it.playHref() }

                        if (links.isNotEmpty()) {
                            episodeLinks
                                .getOrPut(season to episode) {
                                    mutableListOf()
                                }
                                .addAll(links)
                        }
                    }
            }

        /*
         * Small fallback for older layouts that label the filename itself
         * with SxxExx.
         */
        if (episodeLinks.isEmpty()) {
            document.select("div.download-item").forEach { block ->
                val match = seasonEpisodeRegex.find(block.text())
                    ?: return@forEach

                val season = match.groupValues[1].toIntOrNull()
                    ?: return@forEach
                val episode = match.groupValues[2].toIntOrNull()
                    ?: return@forEach

                val links = block
                    .select("a[href]")
                    .mapNotNull { it.playHref() }

                if (links.isNotEmpty()) {
                    episodeLinks
                        .getOrPut(season to episode) {
                            mutableListOf()
                        }
                        .addAll(links)
                }
            }
        }

        val episodes = episodeLinks
            .entries
            .sortedWith(
                compareBy<Map.Entry<Pair<Int, Int>, MutableList<String>>> {
                    it.key.first
                }.thenBy {
                    it.key.second
                },
            )
            .map { (key, rawLinks) ->
                val season = key.first
                val episode = key.second
                val links = rawLinks.distinct()

                newEpisode(links.toJson()) {
                    this.season = season
                    this.episode = episode
                    this.name =
                        "Episode ${episode.toString().padStart(2, '0')}"
                    posterUrl = poster
                }
            }

        log(
            "episodes=${episodes.size} " +
                "serverLinks=${episodeLinks.values.sumOf { it.distinct().size }}",
        )

        return episodes
    }

    private fun Element.getImageUrl(): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("src"),
        )

        return candidates.firstOrNull { value ->
            value.isNotBlank() &&
                !value.startsWith("data:image", ignoreCase = true)
        }
    }

    private fun String.cleanSpaces(): String =
        replace(Regex("\\s+"), " ").trim()
}
