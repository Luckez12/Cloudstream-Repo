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

    private data class PlayerCandidate(
        val label: String,
        val url: String
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

    private fun Document.collectPlayerCandidates(pageUrl: String): List<PlayerCandidate> {
        val players = mutableListOf<PlayerCandidate>()

        // AnimeXin exposes one active iframe plus server choices. The older
        // provider ignored the active iframe completely and only inspected
        // `.mobius option`, which loses players when the theme changes.
        select(
            "#embed_holder iframe[src], #embed_holder iframe[data-src], " +
                ".player-embed iframe[src], .player-embed iframe[data-src], " +
                ".embed_holder iframe[src], .embed_holder iframe[data-src], " +
                ".video-content iframe[src], .video-content iframe[data-src], " +
                "iframe.metaframe[src], iframe[src]"
        ).forEachIndexed { index, iframe ->
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            absolutePlayerUrl(pageUrl, raw)?.let { url ->
                players.add(PlayerCandidate("Active Player ${index + 1}", url))
            }
        }

        // Newer AnimeXin pages group choices as Hardsub Indonesia, Hardsub
        // English and All Subs. Keep the optgroup label so diagnostics show
        // which group is being discovered, and accept all common value attrs.
        select(
            ".mobius option, select.mirror option, .mirror option, " +
                ".server option, option[data-video], option[data-src], " +
                "option[data-embed], select option[value]"
        ).forEach { option ->
            val groupLabel = option.parent()
                ?.takeIf { it.tagName().equals("optgroup", ignoreCase = true) }
                ?.attr("label")
                ?.trim()
                .orEmpty()

            val optionLabel = option.text().trim()
                .ifBlank { option.attr("data-index").trim() }
                .ifBlank { "Server" }

            val label = listOf(groupLabel, optionLabel)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")
                .ifBlank { "Server" }

            listOf(
                option.attr("value"),
                option.attr("data-video"),
                option.attr("data-src"),
                option.attr("data-embed")
            ).forEach { raw ->
                extractPlayerUrls(raw, pageUrl).forEach { url ->
                    players.add(PlayerCandidate(label, url))
                }
            }
        }

        return players
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .distinctBy { it.url }
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

    private fun Document.emitEmbeddedSubtitles(
        pageUrl: String,
        emittedSubtitleUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val url = absolutePlayerUrl(pageUrl, raw) ?: return@forEach
            if (!emittedSubtitleUrls.add(url)) return@forEach

            val language = track.attr("label").trim()
                .ifBlank { track.attr("srclang").trim() }
                .ifBlank { "AnimeXin Subs" }

            subtitleCallback(newSubtitleFile(language, url))
        }

        select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }
                .replace("\\/", "/")

            Regex(
                """https?://[^\s\"'<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^\s\"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text).forEach { match ->
                val url = match.value
                if (emittedSubtitleUrls.add(url)) {
                    subtitleCallback(newSubtitleFile("AnimeXin All Subs", url))
                }
            }
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        return clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            clean.endsWith(".mp4") ||
            clean.endsWith(".webm")
    }

    private suspend fun tryPlayerCandidate(
        player: PlayerCandidate,
        episodeUrl: String,
        attempted: MutableSet<String>,
        emitted: MutableSet<String>,
        emittedSubtitleUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey = "${player.url}\u0000$episodeUrl"
        if (!attempted.add(attemptKey)) return false

        val produced = AtomicBoolean(false)
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) {
                produced.set(true)
                callback(link)
            }
        }

        if (isDirectMedia(player.url)) {
            if (emitted.add(player.url)) {
                val type = when {
                    player.url.substringBefore('?').contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    player.url.substringBefore('?').contains(".mpd", true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback(
                    newExtractorLink(
                        "Animexin",
                        "${player.label} • Direct",
                        player.url,
                        type
                    ) {
                        this.referer = episodeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                produced.set(true)
            }
            return produced.get()
        }

        try {
            withTimeoutOrNull(15_000L) {
                loadExtractor(
                    player.url,
                    episodeUrl,
                    subtitleCallback,
                    wrappedCallback
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Continue into the wrapper parser below.
        }

        if (produced.get()) {
            Log.i("Animexin", "ANIMEXIN_PLAYER_OK label=${player.label} mode=extractor")
            return true
        }

        val wrapperDocument = try {
            withTimeoutOrNull(12_000L) {
                app.get(player.url, referer = episodeUrl).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: run {
            Log.w("Animexin", "ANIMEXIN_PLAYER_EMPTY label=${player.label} stage=wrapper")
            return false
        }

        wrapperDocument.emitEmbeddedSubtitles(
            player.url,
            emittedSubtitleUrls,
            subtitleCallback
        )

        val nested = wrapperDocument.collectNestedPlayerUrls(player.url)
        Log.i(
            "Animexin",
            "ANIMEXIN_PLAYER_NESTED label=${player.label} urls=${nested.size}"
        )

        for (nestedUrl in nested) {
            if (isDirectMedia(nestedUrl)) {
                if (!emitted.add(nestedUrl)) continue

                val type = when {
                    nestedUrl.substringBefore('?').contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    nestedUrl.substringBefore('?').contains(".mpd", true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        "Animexin",
                        "${player.label} • Direct",
                        nestedUrl,
                        type
                    ) {
                        this.referer = player.url
                        this.quality = Qualities.Unknown.value
                    }
                )
                produced.set(true)
                continue
            }

            val nestedKey = "$nestedUrl\u0000${player.url}"
            if (!attempted.add(nestedKey)) continue

            try {
                withTimeoutOrNull(12_000L) {
                    loadExtractor(
                        nestedUrl,
                        player.url,
                        subtitleCallback,
                        wrappedCallback
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep trying the remaining mirrors.
            }
        }

        if (produced.get()) {
            Log.i("Animexin", "ANIMEXIN_PLAYER_OK label=${player.label} mode=nested")
        } else {
            Log.w("Animexin", "ANIMEXIN_PLAYER_EMPTY label=${player.label} stage=extract")
        }

        return produced.get()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            withTimeoutOrNull(15_000L) {
                app.get(data).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val emittedSubtitleUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        document.emitEmbeddedSubtitles(data, emittedSubtitleUrls, subtitleCallback)

        val players = document.collectPlayerCandidates(data)
        Log.i(
            "Animexin",
            "ANIMEXIN_PLAYERS count=${players.size} labels=${players.map { it.label }.distinct().joinToString(" | ")}"
        )

        if (players.isEmpty()) {
            Log.w("Animexin", "ANIMEXIN_PLAYERS_EMPTY url=$data")
            return false
        }

        val attempted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val emitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val semaphore = Semaphore(4)

        val success = coroutineScope {
            players.map { player ->
                async {
                    semaphore.withPermit {
                        tryPlayerCandidate(
                            player,
                            data,
                            attempted,
                            emitted,
                            emittedSubtitleUrls,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }.awaitAll().any { it }
        }

        Log.i(
            "Animexin",
            "ANIMEXIN_LINKS_DONE players=${players.size} links=${emitted.size} subtitles=${emittedSubtitleUrls.size} success=$success"
        )
        return success
    }
}
