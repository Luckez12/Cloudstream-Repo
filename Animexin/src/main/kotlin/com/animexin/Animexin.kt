package com.animexin

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?order=update" to "Donghua",
        "anime/?type=movie&order=update" to "Movies",
        "anime/?sub=raw&order=update" to "Anime (RAW)"
    )

    // AnimeXin currently returns 403 for poster requests made by Coil on some
    // Cloudstream builds. posterHeaders alone is not enough on those builds,
    // so fetch protected posters through Cloudstream's own HTTP client while
    // the AnimeXin page session/referer is active and hand Coil a data URI.
    private val imageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
            "Sec-Fetch-Dest" to "image",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "same-origin"
        )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterFetchSemaphore = Semaphore(5)

    private fun Element.getImageUrl(): String? {
        fun fromSrcset(value: String): String? = value
            .split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .filter { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            .lastOrNull()

        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            fromSrcset(attr("data-srcset")).orEmpty(),
            fromSrcset(attr("srcset")).orEmpty(),
            attr("src")
        ).firstOrNull { imageUrl ->
            imageUrl.isNotBlank() &&
                !imageUrl.startsWith("data:", ignoreCase = true)
        }
    }

    private fun guessImageMime(bytes: ByteArray, contentType: String?): String {
        val cleanType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (cleanType?.startsWith("image/") == true) return cleanType

        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private suspend fun resolvePosterUrl(rawUrl: String?, referer: String): String? {
        val fixedUrl = rawUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: return null

        if (!fixedUrl.contains("animexin.dev", ignoreCase = true)) {
            return fixedUrl
        }

        posterCache[fixedUrl]?.let { return it }

        return posterFetchSemaphore.withPermit {
            posterCache[fixedUrl]?.let { return@withPermit it }

            val inlinePoster = runCatching {
                val response = app.get(
                    fixedUrl,
                    referer = referer.ifBlank { "$mainUrl/" },
                    headers = imageHeaders,
                    timeout = 5L
                )

                if (!response.isSuccessful) {
                    Log.w(
                        "Animexin",
                        "ANIMEXIN_POSTER_FETCH code=${response.code} host=${runCatching { java.net.URI(fixedUrl).host }.getOrNull()}"
                    )
                    return@runCatching null
                }

                val declaredSize = response.size
                if (declaredSize != null && declaredSize > 2_500_000L) {
                    Log.w("Animexin", "ANIMEXIN_POSTER_FETCH skip=oversize bytes=$declaredSize")
                    return@runCatching null
                }

                val body = response.body
                val bytes = body.bytes()
                body.close()

                if (bytes.isEmpty() || bytes.size > 2_500_000) {
                    Log.w("Animexin", "ANIMEXIN_POSTER_FETCH skip=invalid-size bytes=${bytes.size}")
                    return@runCatching null
                }

                val mime = guessImageMime(bytes, response.headers["Content-Type"])
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUri = "data:$mime;base64,$encoded"
                Log.i("Animexin", "ANIMEXIN_POSTER_FETCH code=${response.code} bytes=${bytes.size} inline=true")
                dataUri
            }.onFailure { error ->
                Log.w("Animexin", "ANIMEXIN_POSTER_FETCH_FAIL type=${error::class.simpleName}")
            }.getOrNull()

            if (inlinePoster != null) {
                posterCache[fixedUrl] = inlinePoster
            }

            // Returning null is intentional when the protected fetch fails.
            // Falling back to the raw AnimeXin URL would only make Coil hit
            // the same confirmed HTTP 403 again.
            inlinePoster
        }
    }

    // Fast list/search poster path. Never perform an HTTP image request here.
    // If the detail page has already cached an inline poster use it, otherwise
    // return the normal URL immediately and let Cloudstream render the page.
    private fun fastPosterUrl(rawUrl: String?): String? {
        val fixedUrl = rawUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: return null

        return posterCache[fixedUrl] ?: fixedUrl
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val started = System.currentTimeMillis()
        val pageUrl = "$mainUrl/${request.data}&page=$page"
        val document = app.get(pageUrl).document

        val typeHint = if (
            request.data.contains("type=movie", ignoreCase = true)
        ) {
            TvType.Movie
        } else {
            null
        }

        // Anichin-style: pure HTML mapping, no poster network calls.
        val home = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult(typeHint) }

        val hasNext = document.selectFirst(
            "a.next.page-numbers, .pagination .next a, .hpage a.r, a[rel=next]"
        ) != null

        Log.w(
            "Animexin",
            "ANIMEXIN_V13_PAGE items=${home.size} ms=${System.currentTimeMillis() - started}"
        )

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(
        typeHint: TvType? = null
    ): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href], a[href]")
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
            ?.let(::fastPosterUrl)

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
            this.posterHeaders = imageHeaders
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val started = System.currentTimeMillis()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        if (encodedQuery.isBlank()) {
            return emptyList<SearchResponse>().toNewSearchResponseList()
        }

        val pageUrl = "$mainUrl/page/$page/?s=$encodedQuery"
        val document = app.get(pageUrl).document

        val results = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()

        Log.w(
            "Animexin",
            "ANIMEXIN_V13_SEARCH items=${results.size} ms=${System.currentTimeMillis() - started}"
        )

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val started = System.currentTimeMillis()
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()

        val rawPoster = document
            .selectFirst("div.thumb img, div.ime img, img.wp-post-image")
            ?.getImageUrl()
            ?: document
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()

        // One image request maximum for the whole detail page.
        // Episodes reuse this poster instead of fetching one image per episode.
        val poster = resolvePosterUrl(rawPoster, fixedUrl)
            ?: fastPosterUrl(rawPoster)

        val description = document
            .selectFirst("div.entry-content, .synopsis, .sinopsis, .desc")
            ?.text()
            ?.trim()

        val typeText = document
            .selectFirst(".spe")
            ?.text()
            .orEmpty()

        val isMovie = typeText.contains("Movie", ignoreCase = true)

        return if (!isMovie) {
            val episodeRegex = Regex("""(\d+)""")

            // Anichin-style: local DOM parsing only, no async poster downloads.
            val episodes = document
                .select("div.eplister > ul > li, .eplister li")
                .mapNotNull { info ->
                    val rawHref = info
                        .selectFirst("a[href]")
                        ?.attr("href")
                        ?.trim()
                        .orEmpty()

                    if (rawHref.isBlank()) return@mapNotNull null

                    val episodeUrl = fixUrl(rawHref)
                    val epText = info
                        .selectFirst("div.epl-num, .epl-num")
                        ?.text()
                        .orEmpty()

                    val epNumber = Regex(
                        """-episode-(\d+)""",
                        RegexOption.IGNORE_CASE
                    ).find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: episodeRegex
                            .find(epText)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    val episodeTitle = info
                        .selectFirst(".epl-title")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    newEpisode(episodeUrl) {
                        this.episode = epNumber
                        this.name = episodeTitle
                            .takeIf { it.isNotBlank() }
                            ?: epNumber?.let { "Episode $it" }
                            ?: epText
                        this.posterUrl = poster
                    }
                }
                .reversed()

            Log.w(
                "Animexin",
                "ANIMEXIN_V13_LOAD type=series episodes=${episodes.size} ms=${System.currentTimeMillis() - started}"
            )

            newTvSeriesLoadResponse(
                title,
                fixedUrl,
                TvType.Anime,
                episodes
            ) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = description
            }
        } else {
            val movieHref = document
                .selectFirst(".eplister li > a[href]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
                ?: fixedUrl

            Log.w(
                "Animexin",
                "ANIMEXIN_V13_LOAD type=movie ms=${System.currentTimeMillis() - started}"
            )

            newMovieLoadResponse(
                title,
                fixedUrl,
                TvType.Movie,
                movieHref
            ) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = description
            }
        }
    }

    private enum class HardSubLanguage(val displayName: String) {
        INDONESIA("Hardsub Indonesia"),
        ENGLISH("Hardsub English")
    }

    private data class PlayerCandidate(
        val label: String,
        val url: String,
        val language: HardSubLanguage
    )

    private data class PlayerDiscovery(
        val players: List<PlayerCandidate>,
        val rawCount: Int,
        val rejectedCount: Int,
        val rejectedSamples: List<String>
    )

    private fun absolutePlayerUrl(baseUrl: String, raw: String): String? {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (value.isBlank()) return null
        if (value.startsWith("javascript:", ignoreCase = true)) return null

        return runCatching {
            when {
                value.startsWith("//") -> {
                    val scheme = URI(baseUrl).scheme ?: "https"
                    "$scheme:$value"
                }
                value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true) -> value
                else -> URI(baseUrl).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun extractPlayerUrls(rawValue: String, baseUrl: String): List<String> {
        val raw = rawValue.trim()
        if (raw.isBlank()) return emptyList()

        val urls = mutableListOf<String>()

        fun collectFromText(text: String) {
            val cleaned = text
                .replace("&amp;", "&")
                .replace("\\/", "/")

            val parsed = Jsoup.parse(cleaned, baseUrl)
            parsed.select(
                "iframe[src], iframe[data-src], video[src], video[data-src], " +
                    "video source[src], source[src], a[data-video], a[data-src]"
            ).forEach { element ->
                val candidate = listOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-video")
                ).firstOrNull { it.isNotBlank() }

                candidate?.let { absolutePlayerUrl(baseUrl, it) }?.let(urls::add)
            }

            Regex(
                """https?://[^\s\"'<>]+""",
                RegexOption.IGNORE_CASE
            ).findAll(cleaned).forEach { match ->
                absolutePlayerUrl(baseUrl, match.value)?.let(urls::add)
            }
        }

        val looksLikeUrl = raw.startsWith("http://", true) ||
            raw.startsWith("https://", true) ||
            raw.startsWith("//") ||
            raw.startsWith("/") ||
            raw.startsWith("./") ||
            raw.startsWith("../")

        if (looksLikeUrl) {
            absolutePlayerUrl(baseUrl, raw)?.let(urls::add)
        }

        if (raw.contains("<iframe", true) ||
            raw.contains("<video", true) ||
            raw.contains("http", true)
        ) {
            collectFromText(raw)
        }

        val decoded = runCatching {
            String(Base64.decode(raw, Base64.DEFAULT))
        }.getOrNull()

        if (!decoded.isNullOrBlank()) {
            collectFromText(decoded)
        }

        return urls
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    private fun classifyHardSub(text: String): HardSubLanguage? {
        val normalized = text
            .lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (normalized.isBlank()) return null

        if (
            normalized.contains("all sub") ||
            normalized.contains("allsub") ||
            normalized.contains("softsub") ||
            normalized.contains("soft sub") ||
            normalized.contains("multi sub") ||
            normalized.contains("multisub")
        ) {
            return null
        }

        val hasHardSub = normalized.contains("hardsub") ||
            normalized.contains("hard sub") ||
            Regex("""\bhard\s*sub\b""").containsMatchIn(normalized)

        if (!hasHardSub) return null

        val isIndonesia = normalized.contains("indonesia") ||
            Regex("""\bindo\b""").containsMatchIn(normalized)
        val isEnglish = normalized.contains("english") ||
            Regex("""\beng\b""").containsMatchIn(normalized)

        return when {
            isIndonesia && !isEnglish -> HardSubLanguage.INDONESIA
            isEnglish && !isIndonesia -> HardSubLanguage.ENGLISH
            else -> null
        }
    }

    private fun Element.nearbyHeadingText(): String {
        val select = when {
            tagName().equals("option", ignoreCase = true) -> {
                val parentElement = parent()
                if (parentElement?.tagName()?.equals("optgroup", ignoreCase = true) == true) {
                    parentElement.parent()
                } else {
                    parentElement
                }
            }
            tagName().equals("select", ignoreCase = true) -> this
            else -> parent()
        }

        val parts = mutableListOf<String>()
        var sibling = select?.previousElementSibling()
        repeat(4) {
            val current = sibling ?: return@repeat
            val tag = current.tagName().lowercase()
            if (
                tag in setOf("h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "p") ||
                current.classNames().any { cls ->
                    cls.contains("title", true) ||
                        cls.contains("label", true) ||
                        cls.contains("server", true) ||
                        cls.contains("sub", true)
                }
            ) {
                parts += current.text()
            }
            sibling = current.previousElementSibling()
        }

        var ancestor = select?.parent()
        repeat(3) {
            val current = ancestor ?: return@repeat
            val own = current.ownText().trim()
            if (own.isNotBlank() && own.length <= 120) parts += own

            current.children()
                .firstOrNull { child ->
                    val tag = child.tagName().lowercase()
                    tag in setOf("h1", "h2", "h3", "h4", "h5", "h6", "strong", "b")
                }
                ?.text()
                ?.takeIf { it.isNotBlank() }
                ?.let(parts::add)

            ancestor = current.parent()
        }

        return parts.distinct().joinToString(" ")
    }

    private fun Element.detectHardSubLanguage(): HardSubLanguage? {
        val optionGroup = parent()
            ?.takeIf { it.tagName().equals("optgroup", ignoreCase = true) }

        val select = when {
            tagName().equals("option", ignoreCase = true) -> optionGroup?.parent() ?: parent()
            tagName().equals("select", ignoreCase = true) -> this
            else -> parent()
        }

        val specificContexts = listOf(
            optionGroup?.attr("label").orEmpty(),
            attr("label"),
            attr("title"),
            attr("data-label"),
            attr("data-name"),
            text(),
            select?.attr("aria-label").orEmpty(),
            select?.attr("title").orEmpty(),
            select?.attr("data-label").orEmpty(),
            select?.attr("data-name").orEmpty(),
            select?.id().orEmpty(),
            select?.className().orEmpty(),
            nearbyHeadingText()
        )

        specificContexts.forEach { context ->
            classifyHardSub(context)?.let { return it }
        }

        return null
    }

    private fun Document.collectHardSubCandidates(pageUrl: String): PlayerDiscovery {
        val players = mutableListOf<PlayerCandidate>()
        val rejectedSamples = mutableListOf<String>()
        var rawCount = 0
        var rejectedCount = 0

        val entries = select(
            ".mobius option, .mirror option, .server option, .player option, " +
                "option[data-video], option[data-src], option[data-embed], select option[value], " +
                ".mobius [data-video], .mobius [data-embed], " +
                ".mirror [data-video], .mirror [data-embed], " +
                ".server [data-video], .server [data-embed], " +
                ".player [data-video], .player [data-embed]"
        ).distinct()

        entries.forEach { element ->
            rawCount++
            val language = element.detectHardSubLanguage()
            if (language == null) {
                rejectedCount++
                if (rejectedSamples.size < 6) {
                    val sample = listOf(
                        element.parent()?.attr("label").orEmpty(),
                        element.text(),
                        element.attr("label"),
                        element.attr("data-name"),
                        element.nearbyHeadingText()
                    ).filter { it.isNotBlank() }
                        .joinToString(" / ")
                        .replace(Regex("""\s+"""), " ")
                        .take(140)
                    if (sample.isNotBlank()) rejectedSamples += sample
                }
                return@forEach
            }

            val serverLabel = element.text().trim()
                .ifBlank { element.attr("label").trim() }
                .ifBlank { element.attr("data-name").trim() }
                .ifBlank { "Server" }

            val label = "${language.displayName} • $serverLabel"

            listOf(
                element.attr("value"),
                element.attr("data-video"),
                element.attr("data-src"),
                element.attr("data-embed")
            ).forEach { raw ->
                extractPlayerUrls(raw, pageUrl).forEach { url ->
                    players += PlayerCandidate(label, url, language)
                }
            }
        }

        return PlayerDiscovery(
            players = players
                .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
                .distinctBy { "${it.language.name}\u0000${it.url}" },
            rawCount = rawCount,
            rejectedCount = rejectedCount,
            rejectedSamples = rejectedSamples.distinct()
        )
    }

    private fun Document.collectNestedPlayerUrls(pageUrl: String): List<String> {
        val urls = mutableListOf<String>()

        select(
            "iframe[src], iframe[data-src], video[src], video[data-src], " +
                "video source[src], source[src]"
        ).forEach { element ->
            val raw = listOf(
                element.attr("src"),
                element.attr("data-src")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            absolutePlayerUrl(pageUrl, raw)?.let(urls::add)
        }

        select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }
                .replace("\\/", "/")

            Regex(
                """(?:file|source|src|url)\s*[:=]\s*[\"'](https?://[^\"']+)[\"']""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            ).findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { absolutePlayerUrl(pageUrl, it) }
                    ?.let(urls::add)
            }

            Regex(
                """https?://[^\s\"'<>]+\.(?:m3u8|mpd|mp4|webm)(?:\?[^\s\"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text).forEach { match ->
                absolutePlayerUrl(pageUrl, match.value)?.let(urls::add)
            }
        }

        return urls.distinct()
    }

    private fun directMediaType(url: String): ExtractorLinkType? {
        val clean = url
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()

        return when {
            clean.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            clean.endsWith(".mpd") -> ExtractorLinkType.DASH
            clean.endsWith(".mp4") ||
                clean.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private fun explicitQuality(text: String): Int? {
        return Regex(
            """(?<!\d)(2160|1440|1080|900|720|576|540|480|432|360|270|240|144)p?(?!\d)""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    /*
     * V13 policy requested by user:
     * - known 720p+ => accept
     * - known below 720p => reject
     * - Qualities.Unknown (400 sentinel) => accept immediately
     *
     * Unknown is not treated as 400p and no extra HLS manifest request is made.
     */
    private fun acceptedQuality(link: ExtractorLink): Int? {
        val explicit = explicitQuality("${link.name} ${link.url}")

        if (
            link.quality == Qualities.Unknown.value ||
            link.quality <= 0
        ) {
            if (explicit != null && explicit < MIN_KNOWN_QUALITY) {
                return null
            }

            return explicit ?: Qualities.Unknown.value
        }

        if (link.quality < MIN_KNOWN_QUALITY) {
            return null
        }

        return link.quality
    }

    private fun emitFilteredLink(
        player: PlayerCandidate,
        link: ExtractorLink,
        emittedUrls: MutableSet<String>,
        acceptedCount: AtomicInteger,
        acceptedUnknown: AtomicInteger,
        droppedKnownLow: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = acceptedQuality(link)

        if (quality == null) {
            droppedKnownLow.incrementAndGet()
            return false
        }

        val emitKey = "${player.language.name}\u0000${link.url}"
        if (!emittedUrls.add(emitKey)) return false

        if (
            quality == Qualities.Unknown.value ||
            link.quality == Qualities.Unknown.value ||
            link.quality <= 0
        ) {
            acceptedUnknown.incrementAndGet()
        }

        @Suppress("DEPRECATION")
        val relabeled = ExtractorLink(
            source = link.source,
            name = "${player.language.displayName} • ${link.name}",
            url = link.url,
            referer = link.referer,
            quality = quality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
            audioTracks = link.audioTracks
        )

        // Important: emit immediately, same fast behavior as Anichin.
        callback(relabeled)
        acceptedCount.incrementAndGet()
        return true
    }

    private fun emitDirectMedia(
        player: PlayerCandidate,
        url: String,
        referer: String,
        emittedUrls: MutableSet<String>,
        acceptedCount: AtomicInteger,
        acceptedUnknown: AtomicInteger,
        droppedKnownLow: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = directMediaType(url) ?: return false
        val explicit = explicitQuality(url)

        if (explicit != null && explicit < MIN_KNOWN_QUALITY) {
            droppedKnownLow.incrementAndGet()
            return false
        }

        val emitKey = "${player.language.name}\u0000$url"
        if (!emittedUrls.add(emitKey)) return false

        val quality = explicit ?: Qualities.Unknown.value
        if (explicit == null) acceptedUnknown.incrementAndGet()

        @Suppress("DEPRECATION")
        val direct = ExtractorLink(
            source = "Animexin",
            name = "${player.language.displayName} • Direct",
            url = url,
            referer = referer,
            quality = quality,
            headers = emptyMap(),
            extractorData = null,
            type = type,
            audioTracks = emptyList()
        )

        callback(direct)
        acceptedCount.incrementAndGet()
        return true
    }

    private suspend fun tryLoadExtractorFast(
        player: PlayerCandidate,
        url: String,
        referer: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        acceptedCount: AtomicInteger,
        acceptedUnknown: AtomicInteger,
        droppedKnownLow: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey =
            "${player.language.name}\u0000$url\u0000$referer"

        if (!attemptedUrls.add(attemptKey)) {
            return false
        }

        if (directMediaType(url) != null) {
            return emitDirectMedia(
                player,
                url,
                referer,
                emittedUrls,
                acceptedCount,
                acceptedUnknown,
                droppedKnownLow,
                callback
            )
        }

        val emitted = AtomicBoolean(false)
        val noSubtitles: (SubtitleFile) -> Unit = { }

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (
                emitFilteredLink(
                    player,
                    link,
                    emittedUrls,
                    acceptedCount,
                    acceptedUnknown,
                    droppedKnownLow,
                    callback
                )
            ) {
                emitted.set(true)
            }
        }

        return try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(
                    url,
                    referer,
                    noSubtitles,
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

    private suspend fun fetchPlayerDocumentFast(
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

    private fun PlayerCandidate.priority(): Int {
        val value = "$label $url".lowercase()

        return when {
            value.contains("vtbe") -> 0
            value.contains("streamwish") ||
                value.contains("wishfast") -> 1
            value.contains("filemoon") -> 2
            value.contains("dailymotion") -> 3
            value.contains("waaw") -> 4
            else -> 20
        }
    }

    private fun balancedPlayers(
        players: List<PlayerCandidate>
    ): List<PlayerCandidate> {
        val indo = players
            .filter { it.language == HardSubLanguage.INDONESIA }
            .sortedBy { it.priority() }

        val english = players
            .filter { it.language == HardSubLanguage.ENGLISH }
            .sortedBy { it.priority() }

        return buildList {
            val count = maxOf(indo.size, english.size)
            for (index in 0 until count) {
                indo.getOrNull(index)?.let(::add)
                english.getOrNull(index)?.let(::add)
            }
        }
    }

    private suspend fun resolvePlayerPipelineFast(
        player: PlayerCandidate,
        episodeUrl: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        acceptedCount: AtomicInteger,
        acceptedUnknown: AtomicInteger,
        droppedKnownLow: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directSuccess = tryLoadExtractorFast(
            player,
            player.url,
            episodeUrl,
            attemptedUrls,
            emittedUrls,
            acceptedCount,
            acceptedUnknown,
            droppedKnownLow,
            callback
        )

        if (directSuccess) return true

        val wrapperDocument = fetchPlayerDocumentFast(
            player.url,
            episodeUrl
        ) ?: return false

        val playerUrls = wrapperDocument
            .collectNestedPlayerUrls(player.url)

        return collectSuccessful(
            playerUrls,
            MAX_NESTED_CONCURRENCY
        ) { playerUrl ->
            val playerSuccess = tryLoadExtractorFast(
                player,
                playerUrl,
                player.url,
                attemptedUrls,
                emittedUrls,
                acceptedCount,
                acceptedUnknown,
                droppedKnownLow,
                callback
            )

            if (playerSuccess) {
                true
            } else {
                val nestedDocument = fetchPlayerDocumentFast(
                    playerUrl,
                    player.url
                )

                if (nestedDocument == null) {
                    false
                } else {
                    val nestedUrls = nestedDocument
                        .collectNestedPlayerUrls(playerUrl)

                    collectSuccessful(
                        nestedUrls,
                        MAX_NESTED_CONCURRENCY
                    ) { nestedUrl ->
                        tryLoadExtractorFast(
                            player,
                            nestedUrl,
                            playerUrl,
                            attemptedUrls,
                            emittedUrls,
                            acceptedCount,
                            acceptedUnknown,
                            droppedKnownLow,
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
        val started = System.currentTimeMillis()
        val firstLinkMs = AtomicInteger(-1)

        Log.w(
            "Animexin",
            "ANIMEXIN_V13_LOADLINKS start mode=hardsub-id-en unknown=accept flow=anichin-fast"
        )

        val document = try {
            withTimeoutOrNull(EPISODE_REQUEST_TIMEOUT_MS) {
                app.get(data).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val discovery = document.collectHardSubCandidates(data)
        val players = balancedPlayers(discovery.players)

        val indoCount = players.count {
            it.language == HardSubLanguage.INDONESIA
        }

        val englishCount = players.count {
            it.language == HardSubLanguage.ENGLISH
        }

        Log.w(
            "Animexin",
            "ANIMEXIN_V13_DISCOVERY raw=${discovery.rawCount} " +
                "selected=${players.size} indo=$indoCount english=$englishCount " +
                "rejected=${discovery.rejectedCount} " +
                "samples=${discovery.rejectedSamples.joinToString(" || ")}"
        )

        if (players.isEmpty()) return false

        val attemptedUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()

        val emittedUrls: MutableSet<String> =
            ConcurrentHashMap.newKeySet()

        val acceptedCount = AtomicInteger(0)
        val acceptedUnknown = AtomicInteger(0)
        val droppedKnownLow = AtomicInteger(0)

        val immediateCallback: (ExtractorLink) -> Unit = { link ->
            val elapsed = (
                System.currentTimeMillis() - started
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            if (firstLinkMs.compareAndSet(-1, elapsed)) {
                Log.w(
                    "Animexin",
                    "ANIMEXIN_V13_FIRST_LINK ms=$elapsed name=${link.name}"
                )
            }

            callback(link)
        }

        val success = collectTwoLane(players) { player ->
            resolvePlayerPipelineFast(
                player,
                data,
                attemptedUrls,
                emittedUrls,
                acceptedCount,
                acceptedUnknown,
                droppedKnownLow,
                immediateCallback
            )
        }

        Log.w(
            "Animexin",
            "ANIMEXIN_V13_DONE players=${players.size} " +
                "accepted=${acceptedCount.get()} " +
                "acceptedUnknown=${acceptedUnknown.get()} " +
                "dropKnownBelow720=${droppedKnownLow.get()} " +
                "firstMs=${firstLinkMs.get()} " +
                "totalMs=${System.currentTimeMillis() - started} " +
                "success=$success"
        )

        return success
    }

    companion object {
        private const val MIN_KNOWN_QUALITY = 720

        private const val FAST_LANE_SIZE = 4
        private const val FAST_LANE_CONCURRENCY = 4
        private const val FULL_LANE_CONCURRENCY = 3
        private const val MAX_NESTED_CONCURRENCY = 2

        private const val EPISODE_REQUEST_TIMEOUT_MS = 8_000L
        private const val PLAYER_REQUEST_TIMEOUT_MS = 5_000L
        private const val EXTRACTOR_TIMEOUT_MS = 7_000L
    }
}
