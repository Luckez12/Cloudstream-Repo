package com.animexin

import android.util.Base64
import android.util.Log
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

class Animexin : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Release",
        "anime/?status=ongoing&order=update" to "Ongoing",
        "anime/?order=popular" to "Popular",
        "anime/?type=movie&order=update" to "Movies",
        "anime/?status=completed&order=update" to "Completed"
    )

    private data class PlayerOption(
        val label: String,
        val url: String
    )

    private data class CardData(
        val title: String,
        val href: String,
        val poster: String?,
        val type: TvType
    )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterMisses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun String.cleanImageCandidate(): String? {
        val value = trim()
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("\\/", "/")
        if (value.isBlank()) return null
        val lower = value.lowercase()
        if (lower.startsWith("data:")) return null
        if (lower.contains("blank.gif") || lower.contains("lazy.svg") ||
            lower.contains("placeholder") || lower.contains("loading.gif")) return null
        return value
    }

    private fun Element.bestSrcSetUrl(attribute: String): String? {
        val srcset = attr(attribute).trim()
        if (srcset.isBlank()) return null
        return srcset.split(',')
            .mapNotNull { item ->
                val parts = item.trim().split(Regex("""\s+"""))
                val url = parts.firstOrNull()?.cleanImageCandidate() ?: return@mapNotNull null
                val score = parts.getOrNull(1)
                    ?.removeSuffix("w")
                    ?.removeSuffix("x")
                    ?.toDoubleOrNull()
                    ?: 0.0
                url to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun Element.imageUrl(): String? {
        listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("data-cfsrc"),
            attr("data-url"),
            attr("src")
        ).forEach { raw -> raw.cleanImageCandidate()?.let { return it } }

        bestSrcSetUrl("data-srcset")?.let { return it }
        bestSrcSetUrl("srcset")?.let { return it }

        listOf(
            attr("data-bg"),
            attr("data-background"),
            attr("data-background-image")
        ).forEach { raw -> raw.cleanImageCandidate()?.let { return it } }

        Regex("""background(?:-image)?\s*:\s*url\((?:['\"])?([^)'\"]+)(?:['\"])?\)""", RegexOption.IGNORE_CASE)
            .find(attr("style"))
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanImageCandidate()
            ?.let { return it }

        return null
    }

    private fun normalizePoster(raw: String?): String? {
        val value = raw?.cleanImageCandidate() ?: return null
        return fixUrlNull(value)
    }

    private fun Document.extractPoster(title: String? = null): String? {
        val preferred = select(
            ".thumb img, .bigcover img, .ime img, .infox .thumb img, " +
                ".ts-post-image, img.wp-post-image, .postbody img"
        )
        preferred.forEach { image -> normalizePoster(image.imageUrl())?.let { return it } }

        if (!title.isNullOrBlank()) {
            val needle = title.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
            select("img").forEach { image ->
                val alt = image.attr("alt").lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
                if (needle.isNotBlank() && alt.isNotBlank() && (alt.contains(needle) || needle.contains(alt))) {
                    normalizePoster(image.imageUrl())?.let { return it }
                }
            }
        }

        selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.let(::normalizePoster)
            ?.let { return it }

        return null
    }

    private fun Element.toCardData(typeHint: TvType? = null): CardData? {
        val anchor = selectFirst("div.bsx > a[href]")
            ?: selectFirst(".bsx a[href]")
            ?: selectFirst("a[href]")
            ?: return null

        val title = anchor.attr("title").trim().ifBlank {
            selectFirst(".tt, .tt h2, h2, h3")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val href = fixUrl(anchor.attr("href"))
        val image = selectFirst(
            "div.bsx > a img, .bsx img, .limit img, img.ts-post-image, img.wp-post-image, img"
        )
        val poster = normalizePoster(image?.imageUrl())
            ?: normalizePoster(selectFirst("[data-bg], [data-background], [style*=background]")?.imageUrl())

        val badge = selectFirst(".typez, .type, .status, .bt .epx")?.text().orEmpty()
        val tvType = when {
            typeHint == TvType.Movie -> TvType.Movie
            badge.contains("Movie", ignoreCase = true) -> TvType.Movie
            href.contains("-movie-", ignoreCase = true) -> TvType.Movie
            else -> TvType.Anime
        }

        return CardData(title, href, poster, tvType)
    }

    private suspend fun fetchPosterFromDetail(card: CardData): String? {
        posterCache[card.href]?.let { return it }
        if (posterMisses.contains(card.href)) return null

        val poster = try {
            withTimeoutOrNull(POSTER_FALLBACK_TIMEOUT_MS) {
                app.get(card.href, referer = "$mainUrl/").document.extractPoster(card.title)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (poster != null) {
            posterCache[card.href] = poster
            Log.i(TAG, "ANIMEXIN_POSTER_FALLBACK title=${card.title.take(45)} host=${runCatching { URI(poster).host }.getOrNull()}")
        } else {
            posterMisses.add(card.href)
        }
        return poster
    }

    private suspend fun enrichPosters(cards: List<CardData>): List<CardData> = coroutineScope {
        val semaphore = Semaphore(POSTER_FALLBACK_CONCURRENCY)
        cards.map { card ->
            async {
                if (card.poster != null) card
                else semaphore.withPermit {
                    card.copy(poster = fetchPosterFromDetail(card))
                }
            }
        }.awaitAll()
    }

    private fun CardData.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl/${request.data}${separator}page=$page"
        val document = app.get(url, referer = "$mainUrl/").document

        val typeHint = if (request.data.contains("type=movie", ignoreCase = true)) TvType.Movie else null
        val cards = document
            .select("div.listupd > article, .listupd article, article.bs")
            .mapNotNull { it.toCardData(typeHint) }
        val enriched = enrichPosters(cards)
        val home = enriched.map { it.toSearchResponse() }

        val hasNext = document.selectFirst(
            "a.next.page-numbers, .pagination .next a, .hpage a.r, a[rel=next]"
        ) != null

        Log.i(
            TAG,
            "ANIMEXIN_HOME section=${request.name} page=$page items=${home.size} " +
                "posters=${enriched.count { it.poster != null }} missing=${enriched.count { it.poster == null }} next=$hasNext"
        )

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        if (encoded.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()

        val searchDoc = app.get("$mainUrl/page/$page/?s=$encoded").document
        val cards = searchDoc
            .select("div.listupd > article, .listupd article, article.bs")
            .mapNotNull { it.toCardData() }
            .distinctBy { it.href }
        val enriched = enrichPosters(cards)

        Log.i(
            TAG,
            "ANIMEXIN_SEARCH page=$page items=${enriched.size} posters=${enriched.count { it.poster != null }} " +
                "missing=${enriched.count { it.poster == null }} query=${query.take(40)}"
        )
        return enriched.map { it.toSearchResponse() }.toNewSearchResponseList()
    }

    private fun cleanSynopsisText(raw: String?): String? {
        val text = raw
            ?.replace('\u00a0', ' ')
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()

        if (text.length < 25) return null

        val lower = text.lowercase()
        val seoHits = listOf(
            "download free",
            "download gratis",
            "various quality",
            "berbagai kualitas",
            "streaming online",
            "mp4 mkv",
            "hardsub softsub"
        ).count { lower.contains(it) }

        return if (seoHits >= 2) null else text
    }

    private fun extractSynopsis(document: Document): String? {
        val headings = document.select("h2, h3, h4")
        val synopsisHeading = headings.firstOrNull {
            it.text().contains("Synopsis", ignoreCase = true) ||
                it.text().contains("Sinopsis", ignoreCase = true)
        }

        if (synopsisHeading != null) {
            val parts = mutableListOf<String>()
            var node = synopsisHeading.nextElementSibling()
            repeat(10) {
                val current = node ?: return@repeat
                if (current.tagName().lowercase() in setOf("h1", "h2", "h3", "h4")) {
                    node = null
                    return@repeat
                }

                val texts = if (current.tagName().equals("p", true)) {
                    listOf(current.text())
                } else {
                    current.select("p").map { it.text() }
                }

                texts.mapNotNull(::cleanSynopsisText).forEach(parts::add)
                node = current.nextElementSibling()
            }

            if (parts.isNotEmpty()) return parts.distinct().joinToString("\n\n")
        }

        document.select(".synp .entry-content, .synopsis, .sinopsis, .desc").forEach { container ->
            val paragraphs = container.select("p")
                .mapNotNull { cleanSynopsisText(it.text()) }
                .distinct()
            if (paragraphs.isNotEmpty()) return paragraphs.joinToString("\n\n")
            cleanSynopsisText(container.text())?.let { return it }
        }

        return document.select("div.entry-content p")
            .mapNotNull { cleanSynopsisText(it.text()) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
    }

    private fun episodeNumberFrom(element: Element, link: String, title: String): Double? {
        Regex("""-episode-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(link)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { return it }

        Regex("""(?:Episode|Ep|Eps)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { return it }

        return element.selectFirst(".epl-num, .epnum, .episode-number")
            ?.text()
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.extractPoster(title).orEmpty()

        val description = extractSynopsis(document)
        val infoText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = infoText.contains("Movie", ignoreCase = true)

        val episodeRows = document.select(".eplister li, div.eplister > ul > li")
        val episodes = episodeRows.mapNotNull { row ->
            val rawLink = row.selectFirst("a[href]")?.attr("href")?.trim().orEmpty()
            if (rawLink.isBlank()) return@mapNotNull null

            val link = fixUrl(rawLink)
            val rowTitle = row.selectFirst(".epl-title")?.text()?.trim()
                .orEmpty()
                .ifBlank { row.selectFirst("a")?.attr("title")?.trim().orEmpty() }
            val epNumber = episodeNumberFrom(row, link, rowTitle)
            val epDate = row.selectFirst(".epl-date")?.text()?.trim().orEmpty()
            val epPoster = normalizePoster(row.selectFirst("a img")?.imageUrl())
                ?: fixUrlNull(poster)

            val displayName = rowTitle.ifBlank {
                epNumber?.let {
                    if (it % 1.0 == 0.0) "Episode ${it.toInt()}" else "Episode $it"
                } ?: "Episode"
            }

            newEpisode(link) {
                name = displayName
                posterUrl = epPoster
                description = epDate.takeIf { it.isNotBlank() }?.let { "Release: $it" }
                if (epNumber != null && epNumber % 1.0 == 0.0) episode = epNumber.toInt()
            }
        }

        Log.i(TAG, "ANIMEXIN_LOAD title=${title.take(60)} movie=$isMovie episodes=${episodes.size}")

        return if (!isMovie && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, fixedUrl, TvType.Anime, episodes.reversed()) {
                posterUrl = fixUrlNull(poster)
                posterHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                plot = description
            }
        } else {
            val moviePage = episodeRows.firstOrNull()
                ?.selectFirst("a[href]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
                ?: fixedUrl

            newMovieLoadResponse(title, fixedUrl, TvType.Movie, moviePage) {
                posterUrl = fixUrlNull(poster)
                posterHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                plot = description
            }
        }
    }

    private fun absoluteUrl(base: String, raw: String): String? {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (value.isBlank()) return null
        if (value.startsWith("javascript:", ignoreCase = true)) return null
        if (value.startsWith("data:", ignoreCase = true)) return null

        return try {
            when {
                value.startsWith("//") -> "${URI(base).scheme ?: "https"}:$value"
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                else -> URI(base).resolve(value).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractUrlFromHtml(value: String, baseUrl: String): String? {
        val cleaned = value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\/", "/")

        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
            ?.value
            ?.let { return absoluteUrl(baseUrl, it) }

        return null
    }

    private fun decodePlayerValue(rawValue: String, baseUrl: String): String? {
        val value = rawValue.trim()
        if (value.isBlank()) return null

        val looksLikeUrl = value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//") ||
            value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../")

        if (looksLikeUrl) return absoluteUrl(baseUrl, value)
        extractUrlFromHtml(value, baseUrl)?.let { return it }

        val decodedCandidates = listOf(Base64.DEFAULT, Base64.URL_SAFE).mapNotNull { flag ->
            runCatching { String(Base64.decode(value, flag)) }.getOrNull()
        }

        decodedCandidates.forEach { decoded ->
            extractUrlFromHtml(decoded, baseUrl)?.let { return it }
        }

        return null
    }

    private fun Document.collectTopLevelPlayers(pageUrl: String): List<PlayerOption> {
        val players = mutableListOf<PlayerOption>()

        select(
            "#embed_holder iframe[src], #embed_holder iframe[data-src], " +
                ".player-embed iframe[src], .player-embed iframe[data-src], " +
                ".embed_holder iframe[src], .embed_holder iframe[data-src], " +
                ".video-content iframe[src], .video-content iframe[data-src], " +
                ".mobius iframe[src], .mobius iframe[data-src], " +
                "iframe.metaframe[src]"
        ).forEachIndexed { index, iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            absoluteUrl(pageUrl, src)?.let {
                players += PlayerOption("Direct ${index + 1}", it)
            }
        }

        select(
            ".mobius option, select.mirror option, .mirror option, .server option, " +
                "select option[value], option[data-index], option[data-video], option[data-src], option[data-embed]"
        ).forEach { option ->
            val label = option.text().trim()
                .ifBlank { option.attr("data-index").trim() }
                .ifBlank { "Server" }

            listOf(
                option.attr("value"),
                option.attr("data-video"),
                option.attr("data-src"),
                option.attr("data-embed"),
                option.attr("data-url")
            ).forEach { raw ->
                decodePlayerValue(raw, pageUrl)?.let {
                    players += PlayerOption(label, it)
                }
            }
        }

        select("[data-video], [data-embed], [data-src], [data-url]").forEach { element ->
            val label = element.text().trim().ifBlank { element.attr("class").trim() }.ifBlank { "Server" }
            listOf(
                element.attr("data-video"),
                element.attr("data-embed"),
                element.attr("data-src"),
                element.attr("data-url")
            ).forEach { raw ->
                decodePlayerValue(raw, pageUrl)?.let {
                    players += PlayerOption(label, it)
                }
            }
        }

        // The current AnimeXin page exposes the active server as a normal iframe.
        // Keep a broad iframe fallback only after the player-specific selectors above.
        if (players.isEmpty()) {
            select("iframe[src], iframe[data-src]").forEachIndexed { index, iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                absoluteUrl(pageUrl, src)?.let {
                    players += PlayerOption("Iframe ${index + 1}", it)
                }
            }
        }

        return players
            .filter { it.url.startsWith("http") }
            .filterNot { it.url.contains("doubleclick", true) || it.url.contains("googleads", true) }
            .distinctBy { it.url }
            .sortedBy { it.priority() }
    }

    private fun Document.collectNestedPlayerUrls(pageUrl: String): List<String> {
        val urls = mutableListOf<String>()

        select("iframe[src], iframe[data-src], video[src], video source[src], source[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            absoluteUrl(pageUrl, raw)?.let(urls::add)
        }

        select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }

            Regex(
                """(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+)["']""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            ).findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { absoluteUrl(pageUrl, it) }
                    ?.let(urls::add)
            }

            Regex(
                """https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text).forEach { match ->
                urls += match.value.replace("\\/", "/")
            }
        }

        return urls
            .filter { it.startsWith("http") }
            .distinct()
    }

    private suspend fun fetchDocument(url: String, referer: String): Document? {
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

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.substringBefore('#').lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mpd")
    }

    private suspend fun emitDirectMedia(
        url: String,
        referer: String,
        emittedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isDirectMedia(url)) return false
        if (!emittedUrls.add(url)) return false

        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = "AnimeXin Direct",
                url = url,
                type = type
            ) {
                this.referer = referer
                quality = Qualities.Unknown.value
            }
        )
        return true
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
        if (!attemptedUrls.add(attemptKey)) return false

        if (emitDirectMedia(url, referer, emittedUrls, callback)) {
            Log.i(TAG, "ANIMEXIN_DIRECT host=${runCatching { URI(url).host }.getOrNull()}")
            return true
        }

        val emitted = AtomicBoolean(false)
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                emitted.set(true)
                Log.i(TAG, "ANIMEXIN_EMIT source=${link.name} host=${runCatching { URI(link.url).host }.getOrNull()}")
                callback(link)
            }
        }

        return try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(url, referer, subtitleCallback, wrappedCallback)
            }
            emitted.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ANIMEXIN_EXTRACT_FAIL host=${runCatching { URI(url).host }.getOrNull()} type=${e::class.simpleName}")
            false
        }
    }

    private suspend fun <T> collectSuccessful(
        items: List<T>,
        concurrency: Int,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) return@coroutineScope false
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
        }.awaitAll().any { it }
    }

    private suspend fun resolvePlayerPipeline(
        player: PlayerOption,
        episodeUrl: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "ANIMEXIN_PLAYER label=${player.label.take(30)} host=${runCatching { URI(player.url).host }.getOrNull()}")

        if (tryLoadExtractor(
                player.url,
                episodeUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )
        ) return true

        val wrapper = fetchDocument(player.url, episodeUrl) ?: return false
        val nested = wrapper.collectNestedPlayerUrls(player.url)

        return collectSuccessful(nested, MAX_NESTED_CONCURRENCY) { nestedUrl ->
            if (tryLoadExtractor(
                    nestedUrl,
                    player.url,
                    attemptedUrls,
                    emittedUrls,
                    subtitleCallback,
                    callback
                )
            ) {
                true
            } else {
                val secondDoc = fetchDocument(nestedUrl, player.url)
                val secondLevel = secondDoc?.collectNestedPlayerUrls(nestedUrl).orEmpty()
                collectSuccessful(secondLevel, MAX_NESTED_CONCURRENCY) { finalUrl ->
                    tryLoadExtractor(
                        finalUrl,
                        nestedUrl,
                        attemptedUrls,
                        emittedUrls,
                        subtitleCallback,
                        callback
                    )
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
        val document = try {
            withTimeoutOrNull(EPISODE_REQUEST_TIMEOUT_MS) {
                app.get(data).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val players = document.collectTopLevelPlayers(data)
        val attemptedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val emittedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val subtitleUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val safeSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            if (subtitleUrls.add(subtitle.url)) subtitleCallback(subtitle)
        }

        Log.i(TAG, "ANIMEXIN_PLAYERS count=${players.size} page=${data.takeLast(70)}")

        if (players.isEmpty()) {
            val staticUrls = document.collectNestedPlayerUrls(data)
            Log.i(TAG, "ANIMEXIN_STATIC_PLAYERS count=${staticUrls.size}")
            return collectSuccessful(staticUrls, PLAYER_CONCURRENCY) { url ->
                tryLoadExtractor(
                    url,
                    data,
                    attemptedUrls,
                    emittedUrls,
                    safeSubtitleCallback,
                    callback
                )
            }
        }

        return collectSuccessful(players, PLAYER_CONCURRENCY) { player ->
            resolvePlayerPipeline(
                player,
                data,
                attemptedUrls,
                emittedUrls,
                safeSubtitleCallback,
                callback
            )
        }
    }

    private fun PlayerOption.priority(): Int {
        val value = "$label $url".lowercase()
        return when {
            value.contains("ok.ru") || value.contains("okru") || value.contains("odnoklassniki") -> 0
            value.contains("dailymotion") || value.contains("geo.dailymotion") -> 1
            value.contains("rumble") -> 2
            value.contains("streamruby") || value.contains("ruby") -> 3
            value.contains("filemoon") -> 4
            value.contains("streamwish") || value.contains("wishfast") -> 5
            value.contains("vtbe") -> 6
            else -> 20
        }
    }

    companion object {
        private const val TAG = "Animexin"
        private const val PLAYER_CONCURRENCY = 3
        private const val MAX_NESTED_CONCURRENCY = 2
        private const val EPISODE_REQUEST_TIMEOUT_MS = 10_000L
        private const val PLAYER_REQUEST_TIMEOUT_MS = 7_000L
        private const val EXTRACTOR_TIMEOUT_MS = 9_000L
        private const val POSTER_FALLBACK_TIMEOUT_MS = 5_000L
        private const val POSTER_FALLBACK_CONCURRENCY = 4
    }
}
