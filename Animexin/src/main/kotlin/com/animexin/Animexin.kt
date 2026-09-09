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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Animexin : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?order=update" to "Donghua",
        "anime/?type=movie&order=update" to "Movies",
        "anime/?sub=raw&order=update" to "Anime (RAW)"
    )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterSemaphore = Semaphore(POSTER_CONCURRENCY)

    private val imageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
        )

    private fun Element.getImageUrl(): String? {
        fun srcset(value: String): String? = value
            .split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .filter { it.isNotBlank() && !it.startsWith("data:", true) }
            .lastOrNull()

        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            srcset(attr("data-srcset")).orEmpty(),
            srcset(attr("srcset")).orEmpty(),
            attr("src")
        ).firstOrNull { it.isNotBlank() && !it.startsWith("data:", true) }
    }

    private fun guessImageMime(bytes: ByteArray, contentType: String?): String {
        val declared = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (declared?.startsWith("image/") == true) return declared
        return when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private suspend fun inlinePoster(rawUrl: String?, referer: String): String? {
        val fixed = rawUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: return null

        if (!fixed.contains("animexin.dev", true)) return fixed
        posterCache[fixed]?.let { return it }

        return posterSemaphore.withPermit {
            posterCache[fixed]?.let { return@withPermit it }

            val dataUri = try {
                withTimeoutOrNull(POSTER_TIMEOUT_MS) {
                    val response = app.get(
                        fixed,
                        referer = referer.ifBlank { "$mainUrl/" },
                        headers = imageHeaders
                    )
                    if (!response.isSuccessful) return@withTimeoutOrNull null
                    val body = response.body
                    val bytes = body.bytes()
                    body.close()
                    if (bytes.isEmpty() || bytes.size > MAX_POSTER_BYTES) return@withTimeoutOrNull null
                    val mime = guessImageMime(bytes, response.headers["Content-Type"])
                    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (dataUri != null) posterCache[fixed] = dataUri
            dataUri
        }
    }

    private data class CardData(
        val title: String,
        val href: String,
        val poster: String?,
        val type: TvType
    )

    private fun Element.toCardData(typeHint: TvType? = null): CardData? {
        val anchor = selectFirst("div.bsx > a[href], a[href]") ?: return null
        val title = anchor.attr("title").trim().ifBlank {
            selectFirst(".tt, h2, h3")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null
        val href = fixUrl(anchor.attr("href"))
        val rawPoster = selectFirst("div.bsx > a img, img")?.getImageUrl()
        val badge = selectFirst(".typez, .type, .status")?.text().orEmpty()
        val type = when {
            typeHint == TvType.Movie -> TvType.Movie
            badge.contains("Movie", true) -> TvType.Movie
            else -> TvType.Anime
        }
        return CardData(title, href, rawPoster, type)
    }

    private suspend fun buildSearchResponses(
        cards: List<CardData>,
        pageReferer: String
    ): List<SearchResponse> = coroutineScope {
        cards.map { card ->
            async {
                val poster = inlinePoster(card.poster, pageReferer)
                    ?: card.poster?.let { fixUrlNull(it) }
                newAnimeSearchResponse(card.title, card.href, card.type) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
            }
        }.awaitAll()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val started = System.currentTimeMillis()
        val separator = if (request.data.contains('?')) "&" else "?"
        val pageUrl = "$mainUrl/${request.data}${separator}page=$page"
        val document = app.get(pageUrl).document
        val typeHint = if (request.data.contains("type=movie", true)) TvType.Movie else null
        val cards = document.select("div.listupd > article").mapNotNull { it.toCardData(typeHint) }
        val responses = buildSearchResponses(cards, pageUrl)
        val hasNext = document.selectFirst("a.next.page-numbers, .pagination .next a, a[rel=next]") != null
        Log.w("Animexin", "ANIMEXIN_V14_PAGE cards=${cards.size} ms=${System.currentTimeMillis() - started}")
        return newHomePageResponse(HomePageList(request.name, responses, false), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val started = System.currentTimeMillis()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        if (encoded.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()
        val pageUrl = "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(pageUrl).document
        val cards = document.select("div.listupd > article").mapNotNull { it.toCardData() }
        val responses = buildSearchResponses(cards, pageUrl)
        Log.w("Animexin", "ANIMEXIN_V14_SEARCH cards=${cards.size} ms=${System.currentTimeMillis() - started}")
        return responses.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val started = System.currentTimeMillis()
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val rawPoster = document.selectFirst("div.thumb img, div.ime img, img.wp-post-image")?.getImageUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val poster = inlinePoster(rawPoster, fixedUrl) ?: rawPoster?.let { fixUrlNull(it) }
        val description = document.selectFirst("div.entry-content, .synopsis, .sinopsis, .desc")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", true)

        return if (!isMovie) {
            val episodes = document.select("div.eplister > ul > li, .eplister li").mapNotNull { info ->
                val href = info.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val episodeUrl = fixUrl(href)
                val epText = info.selectFirst(".epl-num")?.text().orEmpty()
                val epNum = Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(episodeUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                val epTitle = info.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                newEpisode(episodeUrl) {
                    this.episode = epNum
                    this.name = epTitle.takeIf { it.isNotBlank() } ?: epNum?.let { "Episode $it" } ?: epText
                    this.posterUrl = poster
                }
            }.reversed()
            Log.w("Animexin", "ANIMEXIN_V14_LOAD type=series episodes=${episodes.size} ms=${System.currentTimeMillis() - started}")
            newTvSeriesLoadResponse(title, fixedUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = description
            }
        } else {
            val movieHref = document.selectFirst(".eplister li > a[href]")?.attr("href")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: fixedUrl
            Log.w("Animexin", "ANIMEXIN_V14_LOAD type=movie ms=${System.currentTimeMillis() - started}")
            newMovieLoadResponse(title, fixedUrl, TvType.Movie, movieHref) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = description
            }
        }
    }

    private enum class HardSubLanguage(val display: String) {
        INDONESIA("Hardsub Indonesia"),
        ENGLISH("Hardsub English")
    }

    private data class PlayerCandidate(
        val language: HardSubLanguage,
        val label: String,
        val url: String
    )

    private fun classifyHardSub(text: String): HardSubLanguage? {
        val normalized = text.lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("""\s+"""), " ")
        if (normalized.contains("all sub") || normalized.contains("softsub") || normalized.contains("multi sub")) return null
        val hard = normalized.contains("hardsub") || normalized.contains("hard sub")
        if (!hard) return null
        val indo = normalized.contains("indonesia") || Regex("""\bindo\b""").containsMatchIn(normalized)
        val eng = normalized.contains("english") || Regex("""\beng\b""").containsMatchIn(normalized)
        return when {
            indo && !eng -> HardSubLanguage.INDONESIA
            eng && !indo -> HardSubLanguage.ENGLISH
            else -> null
        }
    }

    private fun Element.nearbyContext(): String {
        val values = mutableListOf<String>()
        parent()?.attr("label")?.takeIf { it.isNotBlank() }?.let(values::add)
        values += text()
        var node = parent()?.previousElementSibling()
        repeat(4) {
            val current = node ?: return@repeat
            if (current.tagName().lowercase() in setOf("h1", "h2", "h3", "h4", "strong", "b", "p")) {
                values += current.text()
            }
            node = current.previousElementSibling()
        }
        return values.joinToString(" ")
    }

    private fun Element.detectLanguage(): HardSubLanguage? {
        val contexts = listOf(
            parent()?.attr("label").orEmpty(),
            attr("label"), attr("title"), attr("data-label"), attr("data-name"),
            text(), nearbyContext()
        )
        contexts.forEach { classifyHardSub(it)?.let { lang -> return lang } }
        return null
    }

    private fun absoluteUrl(base: String, raw: String): String? {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (value.isBlank() || value.startsWith("javascript:", true)) return null
        return try {
            when {
                value.startsWith("//") -> "${URI(base).scheme ?: "https"}:$value"
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                else -> URI(base).resolve(value).toString()
            }
        } catch (_: Exception) { null }
    }

    private fun decodePlayer(rawValue: String, baseUrl: String): List<String> {
        val raw = rawValue.trim()
        if (raw.isBlank()) return emptyList()
        val urls = mutableListOf<String>()
        absoluteUrl(baseUrl, raw)?.takeIf { raw.startsWith("http", true) || raw.startsWith("//") || raw.startsWith("/") }?.let(urls::add)
        val decoded = try { String(Base64.decode(raw, Base64.DEFAULT)) } catch (_: Exception) { null }
        listOfNotNull(raw, decoded).forEach { content ->
            val doc = Jsoup.parse(content, baseUrl)
            doc.select("iframe[src], iframe[data-src], video[src], source[src]").forEach { element ->
                val candidate = element.attr("src").ifBlank { element.attr("data-src") }
                absoluteUrl(baseUrl, candidate)?.let(urls::add)
            }
            Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE).findAll(content.replace("\\/", "/"))
                .forEach { absoluteUrl(baseUrl, it.value)?.let(urls::add) }
        }
        return urls.filter { it.startsWith("http", true) }.distinct()
    }

    private fun Document.discoverPlayers(pageUrl: String): List<PlayerCandidate> {
        val players = mutableListOf<PlayerCandidate>()
        val entries = select(".mobius option, .mirror option, .server option, .player option, option[data-video], option[data-src], option[data-embed], select option[value]").distinct()
        entries.forEach { option ->
            val language = option.detectLanguage() ?: return@forEach
            val label = option.text().trim().ifBlank { "Server" }
            listOf(option.attr("value"), option.attr("data-video"), option.attr("data-src"), option.attr("data-embed")).forEach { raw ->
                decodePlayer(raw, pageUrl).forEach { url -> players += PlayerCandidate(language, label, url) }
            }
        }
        return players.distinctBy { "${it.language.name}\u0000${it.url}" }
    }

    private fun Document.nestedUrls(baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        select("iframe[src], iframe[data-src], video[src], source[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            absoluteUrl(baseUrl, raw)?.let(urls::add)
        }
        select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }.replace("\\/", "/")
            Regex("""https?://[^\s\"'<>]+\.(?:m3u8|mpd|mp4|webm)(?:\?[^\s\"'<>]*)?""", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { absoluteUrl(baseUrl, it.value)?.let(urls::add) }
        }
        return urls.distinct()
    }

    private fun explicitQuality(text: String): Int? = Regex(
        """(?<!\d)(2160|1440|1080|900|818|816|720|576|540|480|432|360|270|240|144)p?(?!\d)""",
        RegexOption.IGNORE_CASE
    ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun acceptedQuality(link: ExtractorLink): Int? {
        val explicit = explicitQuality("${link.name} ${link.url}")
        if (link.quality == Qualities.Unknown.value || link.quality <= 0) {
            if (explicit != null && explicit < MIN_QUALITY) return null
            return explicit ?: Qualities.Unknown.value
        }
        return if (link.quality >= MIN_QUALITY) link.quality else null
    }

    private fun cleanSourceName(link: ExtractorLink): String {
        return link.name.replace(Regex("""\s+\d{3,4}p\s*$""", RegexOption.IGNORE_CASE), "").trim()
            .ifBlank { link.source }
    }

    private fun emitLink(
        player: PlayerCandidate,
        link: ExtractorLink,
        emitted: MutableSet<String>,
        accepted: AtomicInteger,
        unknown: AtomicInteger,
        low: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = acceptedQuality(link) ?: run { low.incrementAndGet(); return false }
        val dedupeKey = "${player.language.name}\u0000${cleanSourceName(link)}\u0000$quality"
        if (!emitted.add(dedupeKey)) return false
        if (quality == Qualities.Unknown.value) unknown.incrementAndGet()
        @Suppress("DEPRECATION")
        val output = ExtractorLink(
            source = link.source,
            name = "${player.language.display} • ${cleanSourceName(link)}",
            url = link.url,
            referer = link.referer,
            quality = quality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
            audioTracks = link.audioTracks
        )
        callback(output)
        accepted.incrementAndGet()
        return true
    }

    private suspend fun tryPlayer(
        player: PlayerCandidate,
        episodeUrl: String,
        emitted: MutableSet<String>,
        accepted: AtomicInteger,
        unknown: AtomicInteger,
        low: AtomicInteger,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val produced = AtomicBoolean(false)
        val noSubtitles: (SubtitleFile) -> Unit = { }
        val wrapped: (ExtractorLink) -> Unit = { link ->
            if (emitLink(player, link, emitted, accepted, unknown, low, callback)) produced.set(true)
        }
        try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(player.url, episodeUrl, noSubtitles, wrapped)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        if (produced.get()) return true

        val wrapper = try {
            withTimeoutOrNull(WRAPPER_TIMEOUT_MS) { app.get(player.url, referer = episodeUrl).document }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return false

        val nested = wrapper.nestedUrls(player.url).take(MAX_NESTED_PER_PLAYER)
        for (url in nested) {
            try {
                withTimeoutOrNull(NESTED_TIMEOUT_MS) { loadExtractor(url, player.url, noSubtitles, wrapped) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            if (produced.get()) return true
        }
        return produced.get()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val started = System.currentTimeMillis()
        val firstMs = AtomicInteger(-1)
        Log.w("Animexin", "ANIMEXIN_V14_LOADLINKS start mode=hardsub-id-en unknown=accept")
        val document = withTimeoutOrNull(EPISODE_TIMEOUT_MS) { app.get(data).document } ?: return false
        val players = document.discoverPlayers(data)
        val indo = players.count { it.language == HardSubLanguage.INDONESIA }
        val english = players.count { it.language == HardSubLanguage.ENGLISH }
        Log.w("Animexin", "ANIMEXIN_V14_DISCOVERY selected=${players.size} indo=$indo english=$english")
        if (players.isEmpty()) return false

        val emitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val accepted = AtomicInteger(0)
        val unknown = AtomicInteger(0)
        val low = AtomicInteger(0)
        val immediate: (ExtractorLink) -> Unit = { link ->
            val elapsed = (System.currentTimeMillis() - started).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (firstMs.compareAndSet(-1, elapsed)) Log.w("Animexin", "ANIMEXIN_V14_FIRST_LINK ms=$elapsed name=${link.name}")
            callback(link)
        }
        val semaphore = Semaphore(PLAYER_CONCURRENCY)
        val success = coroutineScope {
            players.map { player ->
                async {
                    semaphore.withPermit { tryPlayer(player, data, emitted, accepted, unknown, low, immediate) }
                }
            }.awaitAll().any { it }
        }
        Log.w("Animexin", "ANIMEXIN_V14_DONE players=${players.size} accepted=${accepted.get()} acceptedUnknown=${unknown.get()} dropKnownBelow720=${low.get()} firstMs=${firstMs.get()} totalMs=${System.currentTimeMillis() - started} success=$success")
        return success
    }

    companion object {
        private const val MIN_QUALITY = 720
        private const val POSTER_CONCURRENCY = 10
        private const val POSTER_TIMEOUT_MS = 3_000L
        private const val MAX_POSTER_BYTES = 2_500_000
        private const val EPISODE_TIMEOUT_MS = 8_000L
        private const val EXTRACTOR_TIMEOUT_MS = 6_000L
        private const val WRAPPER_TIMEOUT_MS = 4_000L
        private const val NESTED_TIMEOUT_MS = 4_000L
        private const val PLAYER_CONCURRENCY = 5
        private const val MAX_NESTED_PER_PLAYER = 2
    }
}
