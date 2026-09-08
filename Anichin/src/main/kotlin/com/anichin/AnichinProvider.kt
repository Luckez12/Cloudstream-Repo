package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
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

class AnichinProvider : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin V2"
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

    private data class PlayerOption(
        val label: String,
        val url: String
    )

    private fun Element.getImageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("src")
        ).firstOrNull { imageUrl ->
            imageUrl.isNotBlank() &&
                !imageUrl.startsWith("data:", ignoreCase = true)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (request.data == "home:popular-today") {
            if (page > 1) {
                return newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = emptyList(),
                        isHorizontalImages = false
                    ),
                    hasNext = false
                )
            }

            val document = app.get(mainUrl).document
            val home = document.parsePopularToday()

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                ),
                hasNext = false
            )
        }

        val document = app.get(
            "${mainUrl}/${request.data}&page=$page"
        ).document

        val typeHint = if (
            request.data.contains("type=movie", ignoreCase = true)
        ) {
            TvType.Movie
        } else {
            null
        }

        val home = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult(typeHint) }

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

    private fun Document.parsePopularToday(): List<SearchResponse> {
        val heading = select("h2, h3, h4, h5")
            .firstOrNull {
                it.text().contains(
                    "Terpopuler Hari Ini",
                    ignoreCase = true
                )
            }
            ?: return emptyList()

        val sectionNodes = mutableListOf<Element>()

        var sibling = heading.nextElementSibling()
        var inspected = 0

        while (sibling != null && inspected < 16) {
            val nextHeading = sibling.selectFirst(
                "h2, h3, h4, h5"
            )

            if (
                nextHeading != null &&
                !nextHeading.text().contains(
                    "Terpopuler Hari Ini",
                    ignoreCase = true
                )
            ) {
                break
            }

            sectionNodes.add(sibling)
            sibling = sibling.nextElementSibling()
            inspected += 1
        }

        /*
         * Some Anichin templates wrap the heading and cards in one parent.
         * Use the parent's following siblings only when direct siblings did
         * not expose episode cards.
         */
        if (
            sectionNodes.flatMap { it.select("a[href]") }
                .none { it.isEpisodeCardLink() }
        ) {
            sectionNodes.clear()

            var parentSibling = heading.parent()
                ?.nextElementSibling()

            inspected = 0

            while (
                parentSibling != null &&
                inspected < 12
            ) {
                val text = parentSibling.text()

                if (
                    text.contains(
                        "Rilisan Terbaru",
                        ignoreCase = true
                    )
                ) {
                    break
                }

                sectionNodes.add(parentSibling)
                parentSibling =
                    parentSibling.nextElementSibling()
                inspected += 1
            }
        }

        return sectionNodes
            .flatMap { node -> node.select("a[href]") }
            .filter { it.isEpisodeCardLink() }
            .mapNotNull { it.toPopularTodayResult() }
            .distinctBy { it.url }
            .take(12)
    }

    private fun Element.isEpisodeCardLink(): Boolean {
        val href = attr("href")
        val label = attr("title")
            .ifBlank { text() }

        return href.contains(
            "-episode-",
            ignoreCase = true
        ) || Regex(
            """\bEpisode\s+(?:\d+(?:\.\d+)?|Movie)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(label)
    }

    private fun Element.toPopularTodayResult(): SearchResponse? {
        val rawHref = attr("href")
            .trim()

        if (rawHref.isBlank()) return null

        val episodeUrl = fixUrl(rawHref)

        val seriesUrl = Regex(
            """-episode-(?:\d+(?:\.\d+)?|movie)(?:-[^/?#]*)?/?(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).replace(
            episodeUrl,
            "/"
        )

        if (seriesUrl == episodeUrl) {
            return null
        }

        val rawTitle = attr("title")
            .trim()
            .ifBlank { text().trim() }

        val title = rawTitle
            .replace(
                Regex(
                    """\s+Episode\s+(?:\d+(?:\.\d+)?|Movie).*?$""",
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

        if (title.isBlank()) return null

        var card: Element? = this
        var posterUrl: String? = null
        var depth = 0

        while (
            card != null &&
            posterUrl == null &&
            depth < 5
        ) {
            posterUrl = card
                .selectFirst("img")
                ?.getImageUrl()
                ?.let { fixUrlNull(it) }

            card = card.parent()
            depth += 1
        }

        return newAnimeSearchResponse(
            title,
            seriesUrl,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(
        typeHint: TvType? = null
    ): SearchResponse? {

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
            ?.getImageUrl()
            ?.let { fixUrlNull(it) }

        val badge = selectFirst(".typez, .type, .status")
            ?.text()
            .orEmpty()

        val tvType = when {
            typeHint == TvType.Movie -> TvType.Movie
            badge.contains("Movie", ignoreCase = true) -> TvType.Movie
            href.contains("-movie-", ignoreCase = true) -> TvType.Movie
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(
            title,
            href,
            tvType
        ) {
            this.posterUrl = posterUrl
        }
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

            val document = app.get(
                "${mainUrl}/page/$page/?s=$encodedQuery"
            ).document

            val results = document
                .select("div.listupd > article")
                .mapNotNull { it.toSearchResult() }

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

        val document = app.get(
            fixUrl(url)
        ).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()

        val poster = (
            document
                .selectFirst("div.thumb img, div.ime img, img.wp-post-image")
                ?.getImageUrl()
                ?: document
                    .selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
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

                    val episodePoster = episodeElement
                        .selectFirst("a img")
                        ?.getImageUrl()
                        ?.let { fixUrlNull(it) }
                        ?: fixUrlNull(poster)

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
                this.posterUrl = fixUrlNull(poster)
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
                this.posterUrl = fixUrlNull(poster)
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
                "iframe.metaframe[src]"
        ).forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }

            val url = absoluteUrl(pageUrl, src)
                ?: return@forEachIndexed

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
                "option[data-src]"
        ).forEach { option ->
            val label = option.text()
                .trim()
                .ifBlank { option.attr("data-index").trim() }
                .ifBlank { "Server" }

            val candidates = listOf(
                option.attr("value"),
                option.attr("data-video"),
                option.attr("data-src"),
                option.attr("data-embed")
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

        return players
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
            .sortedBy { it.priority() }
    }

    private fun Document.collectNestedPlayerUrls(
        pageUrl: String
    ): List<String> {
        val urls = mutableListOf<String>()

        select(
            "iframe[src], " +
                "iframe[data-src], " +
                "video source[src], " +
                "source[src]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }

            absoluteUrl(pageUrl, raw)?.let { urls.add(it) }
        }

        select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }

            Regex(
                """(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+)["']""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            ).findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { absoluteUrl(pageUrl, it) }
                    ?.let { urls.add(it) }
            }

            Regex(
                """https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text).forEach { match ->
                urls.add(match.value.replace("\\/", "/"))
            }
        }

        return urls
            .filter { it.startsWith("http") }
            .distinct()
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String
    ): Document? {
        return try {
            withTimeoutOrNull(PLAYER_REQUEST_TIMEOUT_MS) {
                app.get(url, referer = referer).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey = "$url\u0000$referer"

        if (!attemptedUrls.add(attemptKey)) {
            return false
        }

        val emitted = AtomicBoolean(false)

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
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
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directSuccess = tryLoadExtractor(
            wrapperUrl,
            episodeUrl,
            attemptedUrls,
            emittedUrls,
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
                attemptedUrls,
                emittedUrls,
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
                            attemptedUrls,
                            emittedUrls,
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
            app.get(data).document
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

        val attemptedUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()

        val emittedUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()

        val players = document.collectTopLevelPlayers(data)

        if (players.isEmpty()) {
            val staticPlayers = document.collectNestedPlayerUrls(data)

            return collectTwoLane(staticPlayers) { playerUrl ->
                tryLoadExtractor(
                    playerUrl,
                    data,
                    attemptedUrls,
                    emittedUrls,
                    effectiveSubtitleCallback,
                    callback
                )
            }
        }

        return collectTwoLane(players) { player ->
            resolvePlayerPipeline(
                player.url,
                data,
                attemptedUrls,
                emittedUrls,
                effectiveSubtitleCallback,
                callback
            )
        }
    }

    private fun PlayerOption.priority(): Int {
        val value = "$label $url".lowercase()

        return when {
            value.contains("ok.ru") || value.contains("okru") || value.contains("odnoklassniki") -> 0
            value.contains("dailymotion") -> 1
            value.contains("rumble") -> 2
            value.contains("anichin.stream") -> 3
            value.contains("anichin-player.web.id") -> 4
            value.contains("streamruby") || value.contains("ruby") -> 5
            value.contains("emturbovid") || value.contains("turboviplay") -> 6
            value.contains("morencius") || value.contains("vidhide") -> 7
            else -> 20
        }
    }

    companion object {
        private const val FAST_LANE_SIZE = 3
        private const val FAST_LANE_CONCURRENCY = 3
        private const val FULL_LANE_CONCURRENCY = 3
        private const val MAX_NESTED_CONCURRENCY = 2

        private const val EPISODE_REQUEST_TIMEOUT_MS = 10_000L
        private const val PLAYER_REQUEST_TIMEOUT_MS = 7_000L
        private const val EXTRACTOR_TIMEOUT_MS = 8_000L
    }
}
