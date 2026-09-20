package com.anichin

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AnichinProvider : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Release",
        "anime/?type=movie&order=update" to "Movie",
        "anime/?order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterInFlight: MutableSet<String> =
        ConcurrentHashMap.newKeySet()
    private val posterSemaphore = Semaphore(POSTER_CONCURRENCY)
    private val posterWarmupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private fun imageHeadersFor(imageUrl: String?): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/"
        )

        val imageHost = imageUrl
            ?.takeUnless { it.startsWith("data:", true) }
            ?.let(::hostOf)
            .orEmpty()

        val cookies = sharedCloudflareKiller.savedCookies[imageHost]
            ?: sharedCloudflareKiller.savedCookies[hostOf(mainUrl)]

        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") {
                "${it.key}=${it.value}"
            }
        }

        return headers
    }

    private fun hostOf(url: String): String = runCatching {
        URI(url).host.orEmpty()
    }.getOrDefault("")

    /**
     * Fetch an Anichin page and invoke Cloudstream's WebView challenge solver
     * only when the site answers with an anti-bot status. Clearance cookies are
     * cached and shared for later home, search, detail and episode requests.
     */
    private suspend fun fetchSiteDocument(
        url: String,
        referer: String = "$mainUrl/"
    ): Document {
        val host = hostOf(url)

        suspend fun requestWithCloudflare(): Document {
            val response = app.get(
                url,
                headers = siteHeaders,
                referer = referer,
                timeout = SITE_REQUEST_TIMEOUT_SECONDS,
                interceptor = sharedCloudflareKiller
            )

            if (!response.isSuccessful) {
                val status = response.code
                response.okhttpResponse.close()
                throw IllegalStateException("HTTP $status from $host")
            }

            return response.document
        }

        if (sharedCloudflareKiller.savedCookies.containsKey(host)) {
            try {
                return requestWithCloudflare()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                sharedCloudflareKiller.savedCookies.remove(host)
            }
        }

        val first = app.get(
            url,
            headers = siteHeaders,
            referer = referer,
            timeout = SITE_REQUEST_TIMEOUT_SECONDS
        )

        if (first.code !in CLOUDFLARE_STATUS_CODES) {
            if (!first.isSuccessful) {
                val status = first.code
                first.okhttpResponse.close()
                throw IllegalStateException("HTTP $status from $host")
            }
            return first.document
        }

        first.okhttpResponse.close()

        return sharedCloudflareMutex.withLock {
            if (sharedCloudflareKiller.savedCookies.containsKey(host)) {
                return@withLock requestWithCloudflare()
            }
            requestWithCloudflare()
        }
    }

    private data class PlayerOption(
        val label: String,
        val url: String
    )

    private fun Element.getImageUrl(preferThumbnail: Boolean = false): String? {
        fun srcset(value: String): String? {
            val candidates = value
                .split(',')
                .mapNotNull { entry ->
                    val parts = entry.trim().split(Regex("""\s+"""))
                    val url = parts.firstOrNull()
                        ?.takeIf {
                            it.isNotBlank() && !it.startsWith("data:", true)
                        }
                        ?: return@mapNotNull null
                    val width = parts.getOrNull(1)
                        ?.removeSuffix("w")
                        ?.toIntOrNull()
                    url to width
                }

            if (!preferThumbnail) return candidates.lastOrNull()?.first

            return candidates
                .filter { (_, width) -> width != null && width >= MIN_POSTER_WIDTH }
                .minByOrNull { (_, width) -> width ?: Int.MAX_VALUE }
                ?.first
                ?: candidates.lastOrNull()?.first
        }

        val responsivePoster = srcset(attr("data-lazy-srcset"))
            ?: srcset(attr("data-srcset"))
            ?: srcset(attr("srcset"))

        return listOf(
            responsivePoster.takeIf { preferThumbnail },
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("data-cfsrc"),
            responsivePoster,
            attr("src")
        ).firstOrNull { imageUrl ->
            !imageUrl.isNullOrBlank() &&
                !imageUrl.startsWith("data:", ignoreCase = true)
        }
    }

    private fun guessImageMime(bytes: ByteArray, contentType: String?): String {
        val declared = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()

        if (declared?.startsWith("image/") == true) return declared

        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * Cloudstream's image loader does not use the provider's Cloudflare
     * interceptor. Fetch protected posters here and return a data URI so the
     * UI can render them without making a second unauthenticated request.
     */
    private suspend fun inlinePoster(rawUrl: String?, referer: String): String? {
        val fixed = rawUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: return null

        if (fixed.startsWith("data:", true)) return fixed

        posterCache[fixed]?.let { return it }

        return posterSemaphore.withPermit {
            posterCache[fixed]?.let { return@withPermit it }

            val dataUri = try {
                withTimeoutOrNull(POSTER_TIMEOUT_MS) {
                    val response = app.get(
                        fixed,
                        referer = referer.ifBlank { "$mainUrl/" },
                        headers = imageHeadersFor(fixed),
                        timeout = SITE_REQUEST_TIMEOUT_SECONDS,
                        interceptor = sharedCloudflareKiller
                    )

                    if (!response.isSuccessful) {
                        response.okhttpResponse.close()
                        return@withTimeoutOrNull null
                    }

                    val body = response.body
                    val bytes = body.bytes()
                    body.close()

                    if (bytes.isEmpty() || bytes.size > MAX_POSTER_BYTES) {
                        return@withTimeoutOrNull null
                    }

                    val mime = guessImageMime(
                        bytes,
                        response.headers["Content-Type"]
                    )

                    "data:$mime;base64," +
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (dataUri != null) {
                posterCache[fixed] = dataUri
                trimPosterCache()
            }

            dataUri
        }
    }

    private fun trimPosterCache() {
        while (posterCache.size > MAX_POSTER_CACHE_ENTRIES) {
            val oldestAvailableKey = posterCache.keys.firstOrNull() ?: return
            posterCache.remove(oldestAvailableKey)
        }
    }

    /**
     * Warm protected poster data without delaying home/search responses.
     * Cloudstream does not expose a provider-side UI refresh callback, so a
     * newly cached poster appears when the card is rebound (scroll/tab/refresh)
     * and is immediately available on the next visit during this app session.
     */
    private fun warmPosterCache(
        cards: List<CardData>,
        pageReferer: String
    ) {
        cards.forEach { card ->
            val fixed = card.poster
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrlNull(it) }
                ?: return@forEach

            if (
                fixed.startsWith("data:", true) ||
                posterCache.containsKey(fixed) ||
                !posterInFlight.add(fixed)
            ) {
                return@forEach
            }

            posterWarmupScope.launch {
                try {
                    delay(POSTER_WARMUP_DELAY_MS)
                    inlinePoster(fixed, pageReferer)
                } finally {
                    posterInFlight.remove(fixed)
                }
            }
        }
    }

    private data class CardData(
        val title: String,
        val href: String,
        val poster: String?,
        val type: TvType
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl = "${mainUrl}/${request.data}&page=$page"
        val document = fetchSiteDocument(pageUrl)

        val typeHint = if (
            request.data.contains("type=movie", ignoreCase = true)
        ) {
            TvType.Movie
        } else {
            null
        }

        val cards = document
            .select("div.listupd > article")
            .mapNotNull { it.toCardData(typeHint) }

        val home = buildSearchResponses(cards, pageUrl)

        val hasNext = document.selectFirst(
            "a.next.page-numbers, .pagination .next a, .hpage a.r, a[rel=next]"
        ) != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toCardData(
        typeHint: TvType? = null
    ): CardData? {

        val anchor = selectFirst("div.bsx > a[href]")
            ?: selectFirst("a[href]")
            ?: return null

        val title = anchor
            .attr("title")
            .trim()
            .ifBlank {
                selectFirst(".tt, h2, h3")
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }

        if (title.isBlank()) return null

        val href = fixUrl(anchor.attr("href"))

        val posterUrl = selectFirst("div.bsx > a img, img")
            ?.getImageUrl(preferThumbnail = true)

        val badge = selectFirst(".typez, .type, .status")
            ?.text()
            .orEmpty()

        val tvType = when {
            typeHint == TvType.Movie -> TvType.Movie
            badge.contains("Movie", ignoreCase = true) -> TvType.Movie
            href.contains("-movie-", ignoreCase = true) -> TvType.Movie
            else -> TvType.Anime
        }

        return CardData(title, href, posterUrl, tvType)
    }

    private fun buildSearchResponses(
        cards: List<CardData>,
        pageReferer: String
    ): List<SearchResponse> {
        val responses = cards.map { card ->
            val fixedPoster = card.poster?.let { fixUrlNull(it) }
            val poster = fixedPoster?.let { posterCache[it] ?: it }

            newAnimeSearchResponse(
                card.title,
                card.href,
                card.type
            ) {
                this.posterUrl = poster
                this.posterHeaders = imageHeadersFor(poster)
            }
        }

        warmPosterCache(cards, pageReferer)
        return responses
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        if (encodedQuery.isBlank()) {
            return emptyList()
        }

        val searchResponse = mutableListOf<SearchResponse>()

        for (page in 1..3) {

            val pageUrl = "${mainUrl}/page/$page/?s=$encodedQuery"
            val document = fetchSiteDocument(pageUrl)

            val cards = document
                .select("div.listupd > article")
                .mapNotNull { it.toCardData() }

            val results = buildSearchResponses(cards, pageUrl)

            if (results.isEmpty()) break

            searchResponse.addAll(results)
        }

        return searchResponse.distinctBy { it.url }
    }

    private fun isSeoSynopsis(
        text: String
    ): Boolean {
        val lower = text.lowercase()

        if (lower.startsWith("tonton streaming")) return true
        if (lower.startsWith("nonton ") && lower.contains("terlengkap")) return true

        val hits = listOf(
            "download gratis",
            "berbagai kualitas",
            "menghemat kuota",
            "mp4 mkv",
            "hardsub softsub",
            "streaming online",
            "di anichin"
        ).count { lower.contains(it) }

        return hits >= 2
    }

    private fun cleanSynopsis(raw: String?): String? {
        val text = raw
            ?.replace('\u00a0', ' ')
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()

        if (text.length < 35) return null
        if (isSeoSynopsis(text)) return null
        return text
    }

    private fun extractSynopsis(document: Document): String? {
        val heading = document.select("h2, h3, h4, h5")
            .firstOrNull {
                it.text().contains("Sinopsis", ignoreCase = true)
            }

        if (heading != null) {
            val paragraphs = mutableListOf<String>()
            var node = heading.nextElementSibling()

            repeat(10) {
                val current = node ?: return@repeat

                if (
                    current.tagName().lowercase() in
                    setOf("h1", "h2", "h3", "h4", "h5")
                ) {
                    node = null
                    return@repeat
                }

                val candidates = if (
                    current.tagName().equals("p", ignoreCase = true)
                ) {
                    listOf(current.text())
                } else {
                    current.select("p").map { it.text() }
                }

                candidates.mapNotNull(::cleanSynopsis)
                    .forEach(paragraphs::add)

                node = current.nextElementSibling()
            }

            if (paragraphs.isNotEmpty()) {
                return paragraphs.distinct().joinToString("\n\n")
            }
        }

        document.select(
            ".synp .entry-content, .synopsis, .sinopsis, .desc"
        ).forEach { container ->
            val paragraphs = container.select("p")
                .mapNotNull { cleanSynopsis(it.text()) }
                .distinct()

            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("\n\n")
            }

            cleanSynopsis(container.text())?.let { return it }
        }

        val contentParagraphs = document
            .select("div.entry-content p")
            .mapNotNull { cleanSynopsis(it.text()) }
            .filterNot {
                it.contains("server streaming", ignoreCase = true) ||
                    it.contains("grup telegram", ignoreCase = true)
            }
            .distinct()

        return contentParagraphs
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
    }

    private fun episodeNumberFrom(
        episodeElement: Element,
        link: String,
        title: String
    ): Double? {
        /*
         * V25: trust the episode permalink first.
         *
         * Some Anichin rows expose an internal/global counter in .epl-num.
         * Martial Master is one example: the row can show 7574 while the
         * actual episode permalink is ...-episode-690-....
         *
         * The permalink represents the playable episode, so it is the most
         * reliable source for Cloudstream episode grouping.
         */
        val urlNumber = Regex(
            """-episode-(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(link)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        if (urlNumber != null) {
            return urlNumber
        }

        /*
         * Fallback 2: explicit number in the episode title.
         *
         * This must come before .epl-num because Anichin occasionally stores
         * the WordPress/post counter there instead of the actual episode.
         * Example:
         *   .epl-num = 7574
         *   title    = Martial Master Episode 574 Subtitle Indonesia
         */
        val titleNumber = Regex(
            """(?:Episode|Ep|Eps)\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        if (titleNumber != null) {
            return titleNumber
        }

        /*
         * Last fallback only: dedicated number column.
         * Keep it for older pages that have neither a numeric permalink nor
         * an explicit Episode token in the title.
         */
        val globalNumberText = episodeElement
            .selectFirst(".epl-num, .epnum, .episode-number")
            ?.text()
            ?.trim()
            .orEmpty()

        return Regex(
            """\d+(?:\.\d+)?"""
        ).find(globalNumberText)
            ?.value
            ?.toDoubleOrNull()
    }

    private fun cleanEpisodeDisplayTitle(
        rawTitle: String,
        episodeNumber: Double?
    ): String {
        var cleaned = rawTitle
            .replace(
                Regex(
                    """Episode\s*\d+(?:\.\d+)?\s*Subtitle Indonesia""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                "Subtitle Indonesia",
                "",
                ignoreCase = true
            )
            .trim()

        /*
         * Some templates prefix the visible title with the same bad internal
         * counter that appears in .epl-num, e.g. "7574. - Martial Master".
         * Strip that prefix only when it disagrees with the permalink episode.
         */
        val leadingCounter = Regex(
            """^\s*(\d+(?:\.\d+)?)\s*[.\-:]+\s*"""
        ).find(cleaned)

        if (leadingCounter != null && episodeNumber != null) {
            val counter = leadingCounter
                .groupValues
                .getOrNull(1)
                ?.toDoubleOrNull()

            if (counter != null && counter != episodeNumber) {
                cleaned = cleaned
                    .removeRange(leadingCounter.range)
                    .trim()
            }
        }

        return cleaned.ifBlank {
            episodeNumber?.let { number ->
                if (number % 1.0 == 0.0) {
                    "Episode ${number.toInt()}"
                } else {
                    "Episode $number"
                }
            } ?: rawTitle
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val detailUrl = fixUrl(url)
        val document = fetchSiteDocument(detailUrl)

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()

        val rawPoster = (
            document
                .selectFirst("div.thumb img, div.ime img, img.wp-post-image")
                ?.getImageUrl()
                ?: document
                    .selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
        )

        val poster = (
            inlinePoster(rawPoster, detailUrl)
                ?: rawPoster?.let { fixUrlNull(it) }
        ).orEmpty()

        val description = extractSynopsis(document)

        val type = document
            .selectFirst(".spe")
            ?.text()
            .orEmpty()

        val tvType = if (type.contains("Movie", true)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        return if (tvType == TvType.TvSeries) {

            val episodes = document
                .select(".eplister li")
                .mapNotNull { episodeElement ->

                    val rawLink = episodeElement
                        .selectFirst("a[href]")
                        ?.attr("href")
                        ?.trim()
                        .orEmpty()

                    if (rawLink.isBlank()) {
                        return@mapNotNull null
                    }

                    val link = fixUrl(rawLink)

                    val episodeTitle = episodeElement
                        .selectFirst(".epl-title")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    val episodeSub = episodeElement
                        .selectFirst(".epl-sub span")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    val episodeDate = episodeElement
                        .selectFirst(".epl-date")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    val episodePoster = poster
                        .takeIf { it.isNotBlank() }
                        ?: episodeElement
                            .selectFirst("a img")
                            ?.getImageUrl()
                            ?.let { fixUrlNull(it) }

                    val episodeNumber = episodeNumberFrom(
                        episodeElement,
                        link,
                        episodeTitle
                    )

                    val cleanTitle = cleanEpisodeDisplayTitle(
                        episodeTitle,
                        episodeNumber
                    )

                    val episodeName = buildString {
                        if (cleanTitle.isNotBlank()) {
                            append(cleanTitle)
                        }

                        if (episodeSub.isNotBlank()) {
                            if (isNotEmpty()) append(" ")
                            append(episodeSub)
                            append(" Indonesia")
                        }
                    }.trim()

                    val episodeDescription =
                        episodeDate
                            .takeIf { it.isNotEmpty() }
                            ?.let { "Rilis: $it" }

                    newEpisode(link) {
                        this.name = episodeName
                        this.posterUrl = episodePoster
                        this.description = episodeDescription

                        if (
                            episodeNumber != null &&
                            episodeNumber % 1.0 == 0.0
                        ) {
                            this.episode = episodeNumber.toInt()
                        }
                    }
                }
                .reversed()

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes
            ) {
                this.posterUrl = poster.takeIf { it.isNotBlank() }
                this.posterHeaders = imageHeadersFor(poster)
                this.plot = description
            }

        } else {

            val movieHref = document
                .selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: url

            newMovieLoadResponse(
                title,
                movieHref,
                TvType.Movie,
                movieHref
            ) {
                this.posterUrl = poster.takeIf { it.isNotBlank() }
                this.posterHeaders = imageHeadersFor(poster)
                this.plot = description
            }
        }
    }

    /* V13 load-link pipeline starts here. */

    private fun absoluteUrl(
        base: String,
        raw: String
    ): String? {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (value.isBlank()) return null
        if (value.startsWith("javascript:", ignoreCase = true)) return null

        return try {
            when {
                value.startsWith("//") -> {
                    val scheme = URI(base).scheme ?: "https"
                    "$scheme:$value"
                }
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> URI(base).resolve(value).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePlayerValue(
        rawValue: String,
        baseUrl: String
    ): String? {
        val value = rawValue.trim()
        if (value.isBlank()) return null

        val looksLikeUrl = value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//") ||
            value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../")

        if (looksLikeUrl) {
            return absoluteUrl(baseUrl, value)
        }

        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        val decoded = runCatching {
            String(Base64.decode(value, Base64.DEFAULT))
        }.getOrNull() ?: return null

        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        return Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).find(decoded)
            ?.value
            ?.let { absoluteUrl(baseUrl, it) }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val clean = url.substringBefore('#').lowercase()
        return clean.contains(".m3u8") ||
            clean.contains(".mpd") ||
            clean.contains(".mp4")
    }

    private fun isLikelyPlayerUrl(url: String): Boolean {
        val value = url.lowercase()
        if (isDirectMediaUrl(value)) return true

        return PLAYER_HOST_HINTS.any { value.contains(it) }
    }

    private fun playerLabelScore(label: String): Int {
        val value = label.trim().lowercase()
        return when {
            value.isBlank() || value == "server" -> 0
            value.startsWith("direct ") ||
                value.startsWith("embedded ") ||
                value.startsWith("fallback ") -> 1
            else -> 10
        }
    }

    private fun dedupePlayers(players: List<PlayerOption>): List<PlayerOption> {
        return players
            .filter { it.url.startsWith("http") }
            .groupBy { it.url }
            .values
            .mapNotNull { matches ->
                matches.maxByOrNull { playerLabelScore(it.label) }
            }
            .sortedBy { it.priority() }
    }

    private fun serverDisplayName(label: String, url: String): String {
        val cleanLabel = label
            .replace(
                Regex("""\s*\[(?:ads?|setting\s+dns)\]\s*""", RegexOption.IGNORE_CASE),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (playerLabelScore(cleanLabel) >= 10) return cleanLabel

        val host = runCatching { URI(url).host.orEmpty().lowercase() }
            .getOrDefault("")

        return when {
            host.contains("dailymotion") || host.contains("dmcdn") -> "Dailymotion"
            host == "ok.ru" || host.endsWith(".ok.ru") ||
                host.contains("odnoklassniki") || host.contains("mycdn") -> "OK.ru"
            host.contains("rumble") -> "Rumble"
            host.contains("morencius") || host.contains("vidhide") -> "Vidhide"
            host.contains("anichin-player") -> "New Player"
            host.contains("anichin.stream") -> "Anichin Stream"
            host.contains("drive.google") || host.contains("googleusercontent") -> "Google Drive"
            host.contains("streamruby") || host.contains("rubyvid") -> "StreamRuby"
            host.contains("streamwish") || host.contains("wish") -> "StreamWish"
            host.contains("emturbovid") || host.contains("turboviplay") -> "Emturbovid"
            host.contains("filemoon") -> "Filemoon"
            host.contains("streamtape") -> "Streamtape"
            host.contains("mixdrop") -> "Mixdrop"
            host.isNotBlank() -> host.removePrefix("www.")
            else -> "Anichin"
        }
    }

    private fun extractorLinkScore(link: ExtractorLink): Int {
        val url = link.url.lowercase()
        val quality = link.quality.coerceAtLeast(0)

        return when {
            url.contains("master.m3u8") || url.contains("master_") -> 1_000_000 + quality
            link.type == ExtractorLinkType.M3U8 &&
                link.quality == Qualities.Unknown.value -> 900_000
            link.type == ExtractorLinkType.M3U8 -> 800_000 + quality
            link.type == ExtractorLinkType.DASH -> 700_000 + quality
            else -> 100_000 + quality
        }
    }

    private fun isAdaptiveMaster(link: ExtractorLink): Boolean {
        if (link.type != ExtractorLinkType.M3U8) return false

        val url = link.url.lowercase()
        return link.quality == Qualities.Unknown.value ||
            url.contains("master.m3u8") ||
            url.contains("master_")
    }

    private fun isAllowedQuality(link: ExtractorLink): Boolean {
        return link.url.isNotBlank() &&
            (link.quality >= MIN_VIDEO_QUALITY || isAdaptiveMaster(link))
    }

    private fun qualityLabel(link: ExtractorLink): String {
        return if (link.quality >= MIN_VIDEO_QUALITY) {
            "${link.quality}p"
        } else {
            "Auto"
        }
    }

    private fun qualityOrder(link: ExtractorLink): Int {
        return if (qualityLabel(link) == "Auto") Int.MAX_VALUE else link.quality
    }

    private suspend fun withWebsiteServerName(
        link: ExtractorLink,
        serverLabel: String
    ): ExtractorLink {
        val serverName = serverDisplayName(serverLabel, link.url)
        val displayName = if (qualityLabel(link) == "Auto") {
          "$serverName • Auto"
        } else {
           serverName
        }

        return newExtractorLink(
            source = displayName,
            name = displayName,
            url = link.url,
            type = link.type
        ) {
            this.referer = link.referer
            this.headers = link.headers
            this.quality = link.quality
            this.extractorData = link.extractorData
            this.audioTracks = link.audioTracks
        }
    }

    private fun playerRequestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate"
    )

    private fun decodeBase64Text(value: String): String? {
        val token = value.trim()
        if (token.length < 20) return null

        return runCatching {
            String(Base64.decode(token, Base64.DEFAULT))
        }.getOrElse {
            runCatching {
                String(Base64.decode(token, Base64.URL_SAFE))
            }.getOrNull()
        }
    }

    private fun extractPlayerUrlsFromText(
        rawText: String,
        baseUrl: String
    ): List<String> {
        val text = rawText
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")

        val urls = mutableListOf<String>()

        Regex(
            """<iframe[^>]+(?:src|data-src|data-video|data-embed)\s*=\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)
                ?.let { absoluteUrl(baseUrl, it) }
                ?.let(urls::add)
        }

        Regex(
            """(?:file|source|src|url|embed|player|video)\s*[:=]\s*[\"'](https?://[^\"']+)[\"']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        ).findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)
                ?.let { absoluteUrl(baseUrl, it) }
                ?.let(urls::add)
        }

        Regex(
            """https?://[^\s\"'<>\\]+""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            val url = match.value
                .replace("\\/", "/")
                .trimEnd(')', ']', '}', ',', ';')

            if (isLikelyPlayerUrl(url)) {
                absoluteUrl(baseUrl, url)?.let(urls::add)
            }
        }

        Regex(
            """[\"']([A-Za-z0-9+/_=-]{24,})[\"']"""
        ).findAll(text).forEach { match ->
            val encoded = match.groupValues.getOrNull(1) ?: return@forEach
            val decoded = decodeBase64Text(encoded) ?: return@forEach

            Regex(
                """<iframe[^>]+(?:src|data-src)\s*=\s*[\"']([^\"']+)[\"']""",
                RegexOption.IGNORE_CASE
            ).findAll(decoded).forEach { iframe ->
                iframe.groupValues.getOrNull(1)
                    ?.let { absoluteUrl(baseUrl, it) }
                    ?.let(urls::add)
            }

            Regex(
                """https?://[^\s\"'<>]+""",
                RegexOption.IGNORE_CASE
            ).findAll(decoded).forEach { urlMatch ->
                val url = urlMatch.value.replace("\\/", "/")
                if (isLikelyPlayerUrl(url)) {
                    absoluteUrl(baseUrl, url)?.let(urls::add)
                }
            }
        }

        return urls
            .filter { it.startsWith("http") }
            .distinct()
    }

    private fun Document.collectTopLevelPlayers(
        pageUrl: String
    ): List<PlayerOption> {
        val players = mutableListOf<PlayerOption>()

        select(
            "#embed_holder iframe[src], " +
                "#embed_holder iframe[data-src], " +
                ".player-embed iframe[src], " +
                ".player-embed iframe[data-src], " +
                ".embed_holder iframe[src], " +
                ".embed_holder iframe[data-src], " +
                "iframe.metaframe[src], " +
                "iframe[src], iframe[data-src]"
        ).forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }

            val url = absoluteUrl(pageUrl, src)
                ?: return@forEachIndexed

            if (!isLikelyPlayerUrl(url)) return@forEachIndexed

            players.add(
                PlayerOption(
                    label = "Direct ${index + 1}",
                    url = url
                )
            )
        }

        select(
            ".mobius option, " +
                "select.mirror option, " +
                ".mirror option, " +
                ".server option, " +
                "option[data-index], " +
                "option[data-video], " +
                "option[data-src], " +
                "option[data-embed], " +
                "option[data-url], " +
                "option[data-link], " +
                "option[data-player], " +
                "option[value]"
        ).forEach { option ->
            val label = option.text()
                .trim()
                .ifBlank { option.attr("data-index").trim() }
                .ifBlank { "Server" }

            val candidates = listOf(
                option.attr("value"),
                option.attr("data-video"),
                option.attr("data-src"),
                option.attr("data-embed"),
                option.attr("data-url"),
                option.attr("data-link"),
                option.attr("data-player"),
                option.attr("data-iframe")
            )

            candidates.forEach { raw ->
                val url = decodePlayerValue(raw, pageUrl)
                    ?: return@forEach

                players.add(
                    PlayerOption(
                        label = label,
                        url = url
                    )
                )
            }
        }

        select(
            "[data-video], [data-embed], [data-player], [data-url], [data-link], [data-iframe]"
        ).forEach { element ->
            listOf(
                element.attr("data-video"),
                element.attr("data-embed"),
                element.attr("data-player"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-iframe")
            ).forEach { raw ->
                decodePlayerValue(raw, pageUrl)?.let { url ->
                    if (isLikelyPlayerUrl(url)) {
                        players.add(
                            PlayerOption(
                                label = element.text().trim().ifBlank { "Server" },
                                url = url
                            )
                        )
                    }
                }
            }
        }

        extractPlayerUrlsFromText(html(), pageUrl).forEachIndexed { index, url ->
            players.add(
                PlayerOption(
                    label = "Embedded ${index + 1}",
                    url = url
                )
            )
        }

        return dedupePlayers(players)
    }

    private fun Document.collectNestedPlayerUrls(
        pageUrl: String
    ): List<String> {
        val urls = mutableListOf<String>()

        select(
            "iframe[src], " +
                "iframe[data-src], " +
                "video source[src], " +
                "source[src], " +
                "video[src], " +
                "[data-video], [data-embed], [data-player], [data-url], [data-link], [data-iframe]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-video"),
                element.attr("data-embed"),
                element.attr("data-player"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-iframe")
            ).forEach { raw ->
                if (raw.isBlank()) return@forEach

                val decoded = decodePlayerValue(raw, pageUrl)
                    ?: absoluteUrl(pageUrl, raw)

                if (decoded != null && isLikelyPlayerUrl(decoded)) {
                    urls.add(decoded)
                }
            }
        }

        select("script, textarea").forEach { element ->
            val scriptText = element.data()
                .ifBlank { element.html() }

            urls.addAll(
                extractPlayerUrlsFromText(
                    scriptText,
                    pageUrl
                )
            )
        }

        urls.addAll(extractPlayerUrlsFromText(html(), pageUrl))

        return urls
            .filter { it.startsWith("http") }
            .distinct()
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String
    ): Document? {
        val referers = listOf(
            referer,
            "$mainUrl/",
            "https://anichin.care/"
        ).filter { it.isNotBlank() }.distinct()

        for (candidateReferer in referers) {
            val document = try {
                withTimeoutOrNull(PLAYER_REQUEST_TIMEOUT_MS) {
                    app.get(
                        url,
                        referer = candidateReferer,
                        headers = playerRequestHeaders()
                    ).document
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (document != null) {
                val hasPlayer = document.collectNestedPlayerUrls(url).isNotEmpty()
                if (hasPlayer || !url.contains("anichin-player.web.id", true)) {
                    return document
                }
            }
        }

        return null
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        serverLabel: String,
        attemptedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey = "$url\u0000$referer"

        if (!attemptedUrls.add(attemptKey)) {
            return false
        }

        if (isDirectMediaUrl(url)) {
            val displayName = serverDisplayName(serverLabel, url)

            val type = when {
                url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                url.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = displayName,
                    name = displayName,
                    url = url,
                    type = type
                ) {
                    this.referer = referer
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val emitted = AtomicBoolean(false)
        val discoveredUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (discoveredUrls.add(link.url)) {
                emitted.set(true)
                callback(link)
            }
        }

        return try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(
                    url,
                    referer,
                    subtitleCallback,
                    wrappedCallback
                )
            }
            emitted.get()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun <T> collectSuccessful(
        items: List<T>,
        concurrency: Int,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) {
            return@coroutineScope false
        }

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        items.map { item ->
            async {
                semaphore.withPermit {
                    try {
                        block(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
            .awaitAll()
            .any { it }
    }

    private suspend fun <T> collectFirstSuccessfulSequentially(
        items: List<T>,
        block: suspend (T) -> Boolean
    ): Boolean {
        for (item in items) {
            val succeeded = try {
                block(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

            if (succeeded) return true
        }

        return false
    }

    private suspend fun <T> collectTwoLane(
        items: List<T>,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) {
            return@coroutineScope false
        }

        val fastLane = items.take(FAST_LANE_SIZE)
        val fullLane = items.drop(FAST_LANE_SIZE)

        val fastJob = async {
            collectSuccessful(
                fastLane,
                FAST_LANE_CONCURRENCY,
                block
            )
        }

        val fullJob = async {
            collectSuccessful(
                fullLane,
                FULL_LANE_CONCURRENCY,
                block
            )
        }

        listOf(fastJob, fullJob)
            .awaitAll()
            .any { it }
    }

    private suspend fun resolvePlayerPipeline(
        wrapperUrl: String,
        episodeUrl: String,
        serverLabel: String,
        attemptedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directSuccess = tryLoadExtractor(
            wrapperUrl,
            episodeUrl,
            serverLabel,
            attemptedUrls,
            subtitleCallback,
            callback
        )

        if (directSuccess) return true

        val wrapperDocument = fetchDocument(
            wrapperUrl,
            episodeUrl
        ) ?: return false

        val playerUrls = wrapperDocument.collectNestedPlayerUrls(wrapperUrl)

        return collectSuccessful(
            playerUrls,
            MAX_NESTED_CONCURRENCY
        ) { playerUrl ->
            val playerSuccess = tryLoadExtractor(
                playerUrl,
                wrapperUrl,
                serverLabel,
                attemptedUrls,
                subtitleCallback,
                callback
            )

            if (playerSuccess) {
                true
            } else {
                val nestedDocument = fetchDocument(
                    playerUrl,
                    wrapperUrl
                )

                if (nestedDocument == null) {
                    false
                } else {
                    val nestedUrls = nestedDocument.collectNestedPlayerUrls(playerUrl)

                    collectSuccessful(
                        nestedUrls,
                        MAX_NESTED_CONCURRENCY
                    ) { nestedUrl ->
                        tryLoadExtractor(
                            nestedUrl,
                            playerUrl,
                            serverLabel,
                            attemptedUrls,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = withTimeoutOrNull(EPISODE_REQUEST_TIMEOUT_MS) {
            fetchSiteDocument(data)
        } ?: return false

        val needsClosedCaptions = document.text().contains(
            "AKTIFKAN SUB CC",
            ignoreCase = true
        )

        val emittedSubtitleUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()

        val effectiveSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            if (
                needsClosedCaptions &&
                emittedSubtitleUrls.add(subtitle.url)
            ) {
                subtitleCallback(subtitle)
            }
        }

        val topLevelPlayers = document.collectTopLevelPlayers(data)
        val nestedPlayers = document.collectNestedPlayerUrls(data)

        val players = buildList {
            addAll(topLevelPlayers)
            nestedPlayers.forEachIndexed { index, url ->
                add(
                    PlayerOption(
                        label = "Fallback ${index + 1}",
                        url = url
                    )
                )
            }
        }
            .let(::dedupePlayers)

        Log.w(
            "Anichin",
            "ANICHIN_V45_DISCOVERY page=${data.substringAfter(mainUrl).take(90)} " +
                "top=${topLevelPlayers.size} nested=${nestedPlayers.size} merged=${players.size} " +
                "hosts=${players.take(8).joinToString(" | ") { runCatching { URI(it.url).host }.getOrNull().orEmpty() }}"
        )

        if (players.isEmpty()) {
            Log.w("Anichin", "ANICHIN_V45_DONE candidates=0 success=false")
            return false
        }

        val emittedUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()
        val emittedServerQualities: MutableSet<String> =
            ConcurrentHashMap.newKeySet()
        val emittedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val emissionLock = Any()

        suspend fun resolveAndEmit(player: PlayerOption): Boolean {
            val serverAttempts: MutableSet<String> =
                ConcurrentHashMap.newKeySet()
            val serverLinks: MutableList<ExtractorLink> =
                Collections.synchronizedList(mutableListOf())

            val pipelineTimeout = if (player.priority() <= 2) {
                PREFERRED_PIPELINE_TIMEOUT_MS
            } else {
                FALLBACK_PIPELINE_TIMEOUT_MS
            }

            withTimeoutOrNull(pipelineTimeout) {
                resolvePlayerPipeline(
                    player.url,
                    data,
                    player.label,
                    serverAttempts,
                    effectiveSubtitleCallback,
                    { link -> serverLinks.add(link) }
                )
            }

            val eligibleLinks = serverLinks
                .filter(::isAllowedQuality)
                .distinctBy { link -> link.url }

            /*
             * One clean source per website. A native master already contains
             * every adaptive track, so do not repeat its 1080p/720p children.
             * Only use the highest fixed link when no master was found.
             */
            val rawMasterLink = eligibleLinks
                .filter(::isAdaptiveMaster)
                .maxByOrNull(::extractorLinkScore)

            val fixedFallback = eligibleLinks
                .filterNot(::isAdaptiveMaster)
                .maxByOrNull(::qualityOrder)

            // Pass the website's native master URL straight to Cloudstream.
            // Rebuilding it as a data URI delays startup and is unsupported by
            // some ExoPlayer/Cloudstream versions.
            val orderedLinks = listOfNotNull(rawMasterLink ?: fixedFallback)

            var emittedForServer = false

            for (link in orderedLinks) {
                val serverName = serverDisplayName(player.label, link.url)
                val displayKey = "$serverName\u0000${qualityLabel(link)}"

                val shouldEmit = synchronized(emissionLock) {
                    val normalizedKey = displayKey.lowercase()
                    if (
                        emittedUrls.contains(link.url) ||
                        emittedServerQualities.contains(normalizedKey)
                    ) {
                        false
                    } else {
                        emittedUrls.add(link.url)
                        emittedServerQualities.add(normalizedKey)
                        true
                    }
                }

                if (shouldEmit) {
                    callback(withWebsiteServerName(link, player.label))
                    emittedCount.incrementAndGet()
                    emittedForServer = true
                }
            }

            return emittedForServer
        }

        val preferredPlayers = players.filter { it.priority() <= 2 }
        val fallbackPlayers = players.filter { it.priority() > 2 }
        val preferredServerGroups = preferredPlayers
            .groupBy { it.priority() }
            .values
            .toList()

        val preferredSuccess = withTimeoutOrNull(
            PREFERRED_GROUP_TIMEOUT_MS
        ) {
            /*
             * Start one lane for each preferred server family so duplicate
             * wrapper URLs cannot prevent OK.ru, Rumble or Dailymotion from
             * running. Each successful pipeline emits immediately, while the
             * other two lanes continue inside this active loadLinks call.
             */
            collectSuccessful(
                preferredServerGroups,
                PREFERRED_SERVER_CONCURRENCY,
            ) { serverPlayers ->
                collectFirstSuccessfulSequentially(
                    serverPlayers,
                    ::resolveAndEmit
                )
            }
        } ?: (emittedCount.get() > 0)

        val fallbackAttempted = !preferredSuccess && emittedCount.get() == 0

        val fallbackSuccess = if (fallbackAttempted) {
            withTimeoutOrNull(FALLBACK_GROUP_TIMEOUT_MS) {
                collectSuccessful(
                    fallbackPlayers,
                    FALLBACK_SERVER_CONCURRENCY,
                    ::resolveAndEmit
                )
            } ?: (emittedCount.get() > 0)
        } else {
            false
        }

        val success = preferredSuccess || fallbackSuccess || emittedCount.get() > 0

        Log.w(
            "Anichin",
            "ANICHIN_V45_DONE candidates=${players.size} preferred=${preferredPlayers.size} " +
                "fallbackAttempted=$fallbackAttempted emitted=${emittedCount.get()} success=$success"
        )

        return success
    }

    private fun PlayerOption.priority(): Int {
        val value = "$label $url".lowercase()

        return when {
            value.contains("ok.ru") || value.contains("okru") || value.contains("odnoklassniki") -> 0
            value.contains("rumble") -> 1
            value.contains("dailymotion") -> 2
            value.contains("anichin.stream") -> 3
            value.contains("anichin-player.web.id") -> 4
            value.contains("streamruby") || value.contains("ruby") -> 5
            value.contains("emturbovid") || value.contains("turboviplay") -> 6
            value.contains("morencius") || value.contains("vidhide") -> 7
            else -> 20
        }
    }

    companion object {
        private val sharedCloudflareKiller by lazy { CloudflareCompat() }
        private val sharedCloudflareMutex = Mutex()
        private val CLOUDFLARE_STATUS_CODES = setOf(403, 429, 503)

        private val PLAYER_HOST_HINTS = listOf(
            "ok.ru",
            "odnoklassniki",
            "dailymotion",
            "rumble",
            "anichin-player.web.id",
            "anichin.stream",
            "streamruby",
            "rubyvid",
            "emturbovid",
            "turboviplay",
            "morencius",
            "vidhide",
            "dood",
            "streamwish",
            "wish",
            "odysee.com",
            "odycdn.com",
            "mega.nz",
            "megacloud",
            "filemoon",
            "streamtape",
            "mixdrop"
        )

        private const val FAST_LANE_SIZE = 4
        private const val FAST_LANE_CONCURRENCY = 4
        private const val FULL_LANE_CONCURRENCY = 4
        private const val MAX_NESTED_CONCURRENCY = 3
        private const val PREFERRED_SERVER_CONCURRENCY = 3
        private const val FALLBACK_SERVER_CONCURRENCY = 3
        private const val EPISODE_REQUEST_TIMEOUT_MS = 8_000L
        private const val PLAYER_REQUEST_TIMEOUT_MS = 6_000L
        private const val EXTRACTOR_TIMEOUT_MS = 7_000L
        private const val PREFERRED_PIPELINE_TIMEOUT_MS = 6_000L
        private const val FALLBACK_PIPELINE_TIMEOUT_MS = 4_500L
        private const val PREFERRED_GROUP_TIMEOUT_MS = 7_000L
        private const val FALLBACK_GROUP_TIMEOUT_MS = 5_000L
        private const val SITE_REQUEST_TIMEOUT_SECONDS = 20L
        private const val POSTER_TIMEOUT_MS = 10_000L
        private const val POSTER_WARMUP_DELAY_MS = 500L
        private const val POSTER_CONCURRENCY = 2
        private const val MIN_POSTER_WIDTH = 300
        private const val MAX_POSTER_CACHE_ENTRIES = 48
        private const val MAX_POSTER_BYTES = 4_000_000
        private const val MIN_VIDEO_QUALITY = 720
    }
}
