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
        "anime/?status=ongoing&order&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)",
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
                    timeout = 15L
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "$mainUrl/${request.data}&page=$page"
        val document = app.get(pageUrl).document
        val home = coroutineScope {
            document.select("div.listupd > article")
                .map { element -> async { element.toSearchResult(pageUrl) } }
                .awaitAll()
                .filterNotNull()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private suspend fun Element.toSearchResult(pageReferer: String): SearchResponse? {
        val anchor = this.selectFirst("div.bsx > a[href], a[href]") ?: return null
        val title = anchor.attr("title").trim().ifBlank {
            this.selectFirst(".tt, h2, h3")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val href = fixUrl(anchor.attr("href"))
        val rawPoster = this.selectFirst("div.bsx > a img, img")?.getImageUrl()
        val posterUrl = resolvePosterUrl(rawPoster, pageReferer)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val pageUrl = "${mainUrl}/page/$page/?s=$query"
        val document = app.get(pageUrl).document
        val results = coroutineScope {
            document.select("div.listupd > article")
                .map { element -> async { element.toSearchResult(pageUrl) } }
                .awaitAll()
                .filterNotNull()
                .toNewSearchResponseList()
        }
        return results
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href=document.selectFirst("div.eplister > ul > li a")?.attr("href") ?:""
        val rawPoster = document.selectFirst("div.thumb img, div.ime img, img.wp-post-image")
            ?.getImageUrl()
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
        val poster = resolvePosterUrl(rawPoster, url)
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type=document.selectFirst(".spe")?.text().toString()
        val tvtag=if (type.contains("Movie")) TvType.Movie else TvType.TvSeries
        return if (tvtag == TvType.TvSeries) {
            val episodeRegex = Regex("(\\d+)")

            val episodes = coroutineScope {
                document.select("div.eplister > ul > li").map { info ->
                    async {
                        val href1 = info.select("a").attr("href")
                        val rawEpisodePoster = info.selectFirst("a img")?.getImageUrl()
                        val posterr = if (rawEpisodePoster != null) {
                            resolvePosterUrl(rawEpisodePoster, url) ?: poster
                        } else {
                            poster
                        }

                        val epText = info.selectFirst("div.epl-num")?.text().orEmpty()
                        val epnum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

                        newEpisode(href1) {
                            this.episode = epnum
                            this.name = epnum?.let { "Episode $it" } ?: epText
                            this.posterUrl = posterr
                        }
                    }
                }.awaitAll()
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
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

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        return clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            clean.endsWith(".mp4") ||
            clean.endsWith(".webm")
    }

    private fun normalizedQuality(link: ExtractorLink): Int {
        if (link.quality > 0) return link.quality

        return Regex(
            """(?<!\d)(2160|1440|1080|900|720|576|540|480|432|360|270|240|144)p?(?!\d)""",
            RegexOption.IGNORE_CASE
        ).find("${link.name} ${link.url}")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun normalizedQualityFromUrl(url: String): Int {
        return Regex(
            """(?<!\d)(2160|1440|1080|900|720|576|540|480|432|360|270|240|144)p?(?!\d)""",
            RegexOption.IGNORE_CASE
        ).find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun emitFilteredLink(
        player: PlayerCandidate,
        link: ExtractorLink,
        emitted: MutableSet<String>,
        acceptedCount: AtomicInteger,
        droppedBelow720: AtomicInteger,
        droppedUnknown: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = normalizedQuality(link)

        if (quality in 1 until MIN_QUALITY) {
            droppedBelow720.incrementAndGet()
            return false
        }

        if (quality <= 0) {
            droppedUnknown.incrementAndGet()
            return false
        }

        val emitKey = "${player.language.name}\u0000${link.url}"
        if (!emitted.add(emitKey)) return false

        callback(
            newExtractorLink(
                source = link.name,
                name = "${player.language.displayName} • ${link.name}",
                url = link.url,
                type = link.type
            ) {
                this.referer = link.referer
                this.headers = link.headers
                this.quality = quality
            }
        )
        acceptedCount.incrementAndGet()
        return true
    }

    private suspend fun tryPlayerCandidate(
        player: PlayerCandidate,
        episodeUrl: String,
        attempted: MutableSet<String>,
        emitted: MutableSet<String>,
        acceptedCount: AtomicInteger,
        droppedBelow720: AtomicInteger,
        droppedUnknown: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey = "${player.language.name}\u0000${player.url}\u0000$episodeUrl"
        if (!attempted.add(attemptKey)) return false

        val produced = AtomicBoolean(false)
        val noSubtitles: (SubtitleFile) -> Unit = { }
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (
                emitFilteredLink(
                    player,
                    link,
                    emitted,
                    acceptedCount,
                    droppedBelow720,
                    droppedUnknown,
                    callback
                )
            ) {
                produced.set(true)
            }
        }

        if (isDirectMedia(player.url)) {
            val quality = normalizedQualityFromUrl(player.url)
            if (quality >= MIN_QUALITY) {
                val type = when {
                    player.url.substringBefore('?').contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    player.url.substringBefore('?').contains(".mpd", true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                val directLink = newExtractorLink(
                    source = "Animexin",
                    name = "${player.language.displayName} • Direct",
                    url = player.url,
                    type = type
                ) {
                    this.referer = episodeUrl
                    this.quality = quality
                }

                if (
                    emitFilteredLink(
                        player,
                        directLink,
                        emitted,
                        acceptedCount,
                        droppedBelow720,
                        droppedUnknown,
                        callback
                    )
                ) {
                    produced.set(true)
                }
            } else if (quality > 0) {
                droppedBelow720.incrementAndGet()
            } else {
                droppedUnknown.incrementAndGet()
            }
            return produced.get()
        }

        try {
            withTimeoutOrNull(12_000L) {
                loadExtractor(
                    player.url,
                    episodeUrl,
                    noSubtitles,
                    wrappedCallback
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Continue into wrapper parsing.
        }

        if (produced.get()) return true

        val wrapperDocument = try {
            withTimeoutOrNull(8_000L) {
                app.get(player.url, referer = episodeUrl).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val nested = wrapperDocument.collectNestedPlayerUrls(player.url)

        for (nestedUrl in nested) {
            if (isDirectMedia(nestedUrl)) {
                val quality = normalizedQualityFromUrl(nestedUrl)
                if (quality < MIN_QUALITY) {
                    if (quality > 0) droppedBelow720.incrementAndGet()
                    else droppedUnknown.incrementAndGet()
                    continue
                }

                val type = when {
                    nestedUrl.substringBefore('?').contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    nestedUrl.substringBefore('?').contains(".mpd", true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                val directLink = newExtractorLink(
                    source = "Animexin",
                    name = "${player.language.displayName} • Direct",
                    url = nestedUrl,
                    type = type
                ) {
                    this.referer = player.url
                    this.quality = quality
                }

                if (
                    emitFilteredLink(
                        player,
                        directLink,
                        emitted,
                        acceptedCount,
                        droppedBelow720,
                        droppedUnknown,
                        callback
                    )
                ) {
                    produced.set(true)
                }
                continue
            }

            val nestedKey = "${player.language.name}\u0000$nestedUrl\u0000${player.url}"
            if (!attempted.add(nestedKey)) continue

            try {
                withTimeoutOrNull(9_000L) {
                    loadExtractor(
                        nestedUrl,
                        player.url,
                        noSubtitles,
                        wrappedCallback
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep trying remaining hardsub mirrors.
            }
        }

        return produced.get()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.w("Animexin", "ANIMEXIN_V7_LOADLINKS start minQuality=${MIN_QUALITY}p mode=hardsub-id-en")

        val document = try {
            withTimeoutOrNull(12_000L) {
                app.get(data).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: run {
            Log.w("Animexin", "ANIMEXIN_V7_LOADLINKS pageFetch=false")
            return false
        }

        val discovery = document.collectHardSubCandidates(data)
        val players = discovery.players
        val indoCount = players.count { it.language == HardSubLanguage.INDONESIA }
        val englishCount = players.count { it.language == HardSubLanguage.ENGLISH }

        Log.w(
            "Animexin",
            "ANIMEXIN_V7_DISCOVERY raw=${discovery.rawCount} selected=${players.size} " +
                "indo=$indoCount english=$englishCount rejected=${discovery.rejectedCount} " +
                "samples=${discovery.rejectedSamples.joinToString(" || ")}"
        )

        if (players.isEmpty()) {
            Log.w("Animexin", "ANIMEXIN_V7_DISCOVERY selected=0 reason=no-labelled-hardsub-options")
            return false
        }

        val attempted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val emitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val acceptedCount = AtomicInteger(0)
        val droppedBelow720 = AtomicInteger(0)
        val droppedUnknown = AtomicInteger(0)
        val semaphore = Semaphore(3)

        val success = coroutineScope {
            players.map { player ->
                async {
                    semaphore.withPermit {
                        tryPlayerCandidate(
                            player,
                            data,
                            attempted,
                            emitted,
                            acceptedCount,
                            droppedBelow720,
                            droppedUnknown,
                            callback
                        )
                    }
                }
            }.awaitAll().any { it }
        }

        Log.w(
            "Animexin",
            "ANIMEXIN_V7_DONE players=${players.size} accepted=${acceptedCount.get()} " +
                "dropBelow720=${droppedBelow720.get()} dropUnknown=${droppedUnknown.get()} success=$success"
        )

        return success
    }

    companion object {
        private const val MIN_QUALITY = 720
    }
}
