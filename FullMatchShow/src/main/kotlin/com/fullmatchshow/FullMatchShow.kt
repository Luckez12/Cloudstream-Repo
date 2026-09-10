package com.fullmatchshow

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
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FullMatchShow : MainAPI() {
    override var mainUrl = "https://fullmatchshows.com"
    override var name = "FullMatchShow"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Others)

    /*
     * FullMatchShows changed from the old country paths (/england/, /spain/)
     * to /leagues/... archive paths. These routes are live on the current site.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Matches",
        "$mainUrl/leagues/premier-league/" to "Premier League",
        "$mainUrl/leagues/champions-league/" to "Champions League",
        "$mainUrl/leagues/la-liga/" to "La Liga",
        "$mainUrl/leagues/serie-a/" to "Serie A",
        "$mainUrl/leagues/bundesliga/" to "Bundesliga",
        "$mainUrl/leagues/ligue-1/" to "Ligue 1",
        "$mainUrl/leagues/europa-league/" to "Europa League",
        "$mainUrl/leagues/international/" to "International"
    )

    private data class EpisodeData(
        val label: String,
        val pageUrl: String,
        val urls: List<String>
    )

    private fun pagedUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        val clean = baseUrl.substringBefore('?').trimEnd('/')
        return "$clean/page/$page/"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val started = System.currentTimeMillis()
        val url = pagedUrl(request.data, page)
        val document = app.get(url).document
        val items = document.collectMatchCards()
        val hasNext = document.hasNextPage()

        Log.w(
            "FullMatchShow",
            "FULLMATCH_V3_PAGE section=${request.name} page=$page items=${items.size} hasNext=$hasNext ms=${System.currentTimeMillis() - started}"
        )

        return newHomePageResponse(
            HomePageList(
                request.name,
                items,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val started = System.currentTimeMillis()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        if (encoded.isBlank()) {
            return emptyList<SearchResponse>().toNewSearchResponseList()
        }

        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = app.get(url).document
        val items = document.collectMatchCards()
        val hasNext = document.hasNextPage()

        Log.w(
            "FullMatchShow",
            "FULLMATCH_V3_SEARCH query=${query.trim()} page=$page items=${items.size} hasNext=$hasNext ms=${System.currentTimeMillis() - started}"
        )

        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)
    }

    private fun Document.hasNextPage(): Boolean {
        return selectFirst(
            "a.next.page-numbers, a.next, a[rel=next], .pagination .next a, .nav-links .next"
        ) != null
    }

    /*
     * The current theme has used H6 cards on the homepage and H4 cards on
     * league archives. Do not bind the provider to one CSS card class again.
     * Match post links are identified from semantic heading anchors instead.
     */
    private fun Document.collectMatchCards(): List<SearchResponse> {
        return select("h2 a[href], h3 a[href], h4 a[href], h5 a[href], h6 a[href]")
            .mapNotNull { anchor -> anchor.toMatchSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val rawHref = attr("href").trim()
        val href = fixUrlNull(rawHref) ?: return null
        if (!isMatchPostUrl(href)) return null

        val title = attr("title")
            .trim()
            .ifBlank { text().trim() }
            .replace(Regex("""\s+"""), " ")

        if (!looksLikeMatchTitle(title)) return null

        val poster = findNearbyPoster()?.let { fixUrlNull(it) }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    private fun isMatchPostUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host.orEmpty().lowercase()
            val path = uri.path.orEmpty().lowercase()

            if (!host.endsWith("fullmatchshows.com")) return false
            if (path == "/" || path.isBlank()) return false

            val blocked = listOf(
                "/leagues/",
                "/tag/",
                "/category/",
                "/author/",
                "/page/",
                "/feed/",
                "/wp-"
            )

            blocked.none { path.startsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeMatchTitle(title: String): Boolean {
        val clean = " ${title.lowercase()} "
        return clean.contains(" vs ") ||
            clean.contains(" full match ") ||
            clean.contains(" replay ")
    }

    private fun Element.findNearbyPoster(): String? {
        var current: Element? = this

        repeat(7) {
            val element = current ?: return@repeat
            val image = element.selectFirst("img")
            val imageUrl = image?.realImageUrl()
            if (!imageUrl.isNullOrBlank()) return imageUrl
            current = element.parent()
        }

        return null
    }

    private fun Element.realImageUrl(): String? {
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
        ).firstOrNull {
            it.isNotBlank() && !it.startsWith("data:", true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val started = System.currentTimeMillis()
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl).document

        val title = document.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.replace(Regex("""\s+"""), " ")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: document.selectFirst("article img, main img, .entry-content img")
                ?.realImageUrl()
                ?.let { fixUrlNull(it) }

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("article p, .entry-content p, main p")
                ?.text()
                ?.trim()

        val year = Regex("""\b(20\d{2})\b""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: document.selectFirst("time[datetime]")
                ?.attr("datetime")
                ?.take(4)
                ?.toIntOrNull()

        val tags = document.select("a[href*='/leagues/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)

        val groups = document.collectCurrentVideoGroups(fixedUrl)
        val episodes = groups.mapIndexed { index, group ->
            newEpisode(group.toJson()) {
                name = group.label
                episode = index + 1
                posterUrl = poster
            }
        }

        Log.w(
            "FullMatchShow",
            "FULLMATCH_V3_LOAD title=${title.take(60)} groups=${groups.size} urls=${groups.sumOf { it.urls.size }} ms=${System.currentTimeMillis() - started} labels=${groups.take(8).joinToString(" | ") { it.label }}"
        )

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(
            title,
            fixedUrl,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
        }
    }

    /*
     * Current match pages no longer use the old table.video-table layout.
     * They expose direct external player anchors such as playmogo.com,
     * playmate.to, fullmatchshows.embedseek.com and mixdrop.top.
     * Group mirrors with the same logical label into one Cloudstream episode.
     */
    private fun Document.collectCurrentVideoGroups(pageUrl: String): List<EpisodeData> {
        val groups = LinkedHashMap<String, MutableList<String>>()

        val selectors = listOf(
            "article a[href]",
            "main a[href]",
            ".entry-content a[href]",
            ".post-content a[href]",
            ".s-content a[href]",
            ".thecontent a[href]"
        ).joinToString(", ")

        select(selectors).forEach { anchor ->
            val rawHref = anchor.attr("href").trim()
            val href = absoluteUrl(pageUrl, rawHref) ?: return@forEach
            if (!isExternalVideoCandidate(href, anchor)) return@forEach

            val rawLabel = listOf(
                anchor.text(),
                anchor.attr("title"),
                anchor.attr("aria-label")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            val label = normalizeVideoLabel(rawLabel, href)
            groups.getOrPut(label) { mutableListOf() }.add(href)
        }

        /*
         * Some posts may use an iframe instead of visible links. Keep these as
         * a fallback without mixing obvious ad iframes into normal posts.
         */
        select("article iframe[src], main iframe[src], .entry-content iframe[src]")
            .forEach { iframe ->
                val href = absoluteUrl(pageUrl, iframe.attr("src"))
                    ?: return@forEach
                if (!isKnownVideoHost(href)) return@forEach
                groups.getOrPut("Video") { mutableListOf() }.add(href)
            }

        return groups.entries
            .map { (label, urls) ->
                EpisodeData(
                    label = label,
                    pageUrl = pageUrl,
                    urls = urls.distinct()
                )
            }
            .filter { it.urls.isNotEmpty() }
    }

    private fun normalizeVideoLabel(rawLabel: String, url: String): String {
        var label = rawLabel
            .replace(Regex("""\s+"""), " ")
            .trim()

        /* Older posts append the host name to the logical label. */
        label = label.replace(
            Regex(
                """\s+(?:doodstream|dood|streamhg|mixdrop|gofile|playmate|playmogo|embedseek)\s*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()

        if (label.isBlank() || label.length > 100) {
            label = hostName(url)
        }

        return label.ifBlank { "Video" }
    }

    private fun isExternalVideoCandidate(url: String, anchor: Element): Boolean {
        val host = hostName(url).lowercase()
        if (host.isBlank()) return false
        if (host.endsWith("fullmatchshows.com")) return false

        val blockedHosts = listOf(
            "t.me",
            "telegram.me",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com",
            "reddit.com",
            "gravatar.com"
        )

        if (blockedHosts.any { host == it || host.endsWith(".$it") }) {
            return false
        }

        if (isKnownVideoHost(url)) return true

        val label = listOf(
            anchor.text(),
            anchor.attr("title"),
            anchor.attr("aria-label")
        ).joinToString(" ")

        return VIDEO_LABEL_REGEX.containsMatchIn(label)
    }

    private fun isKnownVideoHost(url: String): Boolean {
        val host = hostName(url).lowercase()
        if (host.isBlank()) return false

        return VIDEO_HOST_HINTS.any { hint ->
            host == hint || host.endsWith(".$hint") || host.contains(hint)
        }
    }

    private fun hostName(url: String): String {
        return try {
            URI(url).host.orEmpty().removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    private fun absoluteUrl(baseUrl: String, rawUrl: String): String? {
        val value = rawUrl
            .trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (value.isBlank()) return null
        if (value.startsWith("javascript:", true)) return null
        if (value == "#") return null

        return try {
            when {
                value.startsWith("//") -> {
                    val scheme = URI(baseUrl).scheme ?: "https"
                    "$scheme:$value"
                }

                value.startsWith("http://", true) ||
                    value.startsWith("https://", true) -> value

                else -> URI(baseUrl).resolve(value).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val started = System.currentTimeMillis()
        val payload = AppUtils.tryParseJson<EpisodeData>(data)
            ?: parseLegacyEpisodeData(data)
            ?: return false

        if (payload.urls.isEmpty()) return false

        val emittedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val visitedPlayers: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val emittedCount = AtomicInteger(0)
        val firstLinkMs = AtomicInteger(-1)
        val semaphore = Semaphore(PLAYER_CONCURRENCY)

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                val elapsed = (System.currentTimeMillis() - started)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()

                if (firstLinkMs.compareAndSet(-1, elapsed)) {
                    Log.w(
                        "FullMatchShow",
                        "FULLMATCH_V3_FIRST_LINK label=${payload.label} ms=$elapsed source=${link.name}"
                    )
                }

                emittedCount.incrementAndGet()
                callback(link)
            }
        }

        coroutineScope {
            payload.urls.map { playerUrl ->
                async {
                    semaphore.withPermit {
                        resolvePlayer(
                            playerUrl,
                            payload.pageUrl,
                            depth = 0,
                            visitedPlayers = visitedPlayers,
                            subtitleCallback = subtitleCallback,
                            callback = wrappedCallback
                        )
                    }
                }
            }.awaitAll()
        }

        val success = emittedCount.get() > 0
        Log.w(
            "FullMatchShow",
            "FULLMATCH_V3_DONE label=${payload.label} players=${payload.urls.size} links=${emittedCount.get()} firstMs=${firstLinkMs.get()} totalMs=${System.currentTimeMillis() - started} success=$success"
        )

        return success
    }

    private fun parseLegacyEpisodeData(data: String): EpisodeData? {
        val parts = data.split("\t", limit = 2)
        val url = parts.firstOrNull()?.trim().orEmpty()
        if (url.isBlank()) return null

        return EpisodeData(
            label = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "Video" },
            pageUrl = mainUrl,
            urls = listOf(url)
        )
    }

    private suspend fun resolvePlayer(
        url: String,
        referer: String,
        depth: Int,
        visitedPlayers: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > MAX_WRAPPER_DEPTH) return false

        val key = "$depth\u0000$url"
        if (!visitedPlayers.add(key)) return false

        if (isDirectMedia(url)) {
            callback(
                newExtractorLink(
                    source = hostName(url).ifBlank { "FullMatchShow" },
                    name = hostName(url).ifBlank { "Direct" },
                    url = url,
                    type = INFER_TYPE
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(url)
                }
            )
            return true
        }

        val extracted = AtomicBoolean(false)

        try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(
                    url,
                    referer,
                    subtitleCallback
                ) { link ->
                    extracted.set(true)
                    callback(link)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Continue with generic wrapper parsing below.
        }

        if (extracted.get()) return true

        val document = try {
            withTimeoutOrNull(WRAPPER_TIMEOUT_MS) {
                app.get(url, referer = referer).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val nested = document.collectNestedPlayers(url)
        if (nested.isEmpty()) return false

        var success = false
        for (nestedUrl in nested.take(MAX_NESTED_PLAYERS)) {
            if (
                resolvePlayer(
                    nestedUrl,
                    url,
                    depth + 1,
                    visitedPlayers,
                    subtitleCallback,
                    callback
                )
            ) {
                success = true
            }
        }

        return success
    }

    private fun Document.collectNestedPlayers(baseUrl: String): List<String> {
        val urls = mutableListOf<String>()

        select(
            "iframe[src], iframe[data-src], video[src], video[data-src], " +
                "video source[src], source[src], a[data-video], a[data-src]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-video")
            ).firstOrNull { it.isNotBlank() }
                ?.let { absoluteUrl(baseUrl, it) }
                ?.let(urls::add)
        }

        select("script").forEach { script ->
            val text = script.data()
                .ifBlank { script.html() }
                .replace("\\/", "/")

            Regex(
                """(?:file|source|src|url)\s*[:=]\s*[\"'](https?://[^\"']+)[\"']""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            ).findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { absoluteUrl(baseUrl, it) }
                    ?.let(urls::add)
            }

            Regex(
                """https?://[^\s\"'<>]+\.(?:m3u8|mpd|mp4|webm)(?:\?[^\s\"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text).forEach { match ->
                absoluteUrl(baseUrl, match.value)?.let(urls::add)
            }
        }

        return urls
            .filter { it.startsWith("http", true) }
            .distinct()
    }

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        return clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            clean.endsWith(".mp4") ||
            clean.endsWith(".webm")
    }

    companion object {
        private val VIDEO_LABEL_REGEX = Regex(
            """(?:highlights?|extended|pre\s*match|full\s*match|1st\s*half|2(?:nd|st)\s*half|live\s*match)""",
            RegexOption.IGNORE_CASE
        )

        private val VIDEO_HOST_HINTS = listOf(
            "playmogo.com",
            "playmate.to",
            "embedseek.com",
            "mixdrop",
            "dood",
            "streamhg",
            "gofile",
            "streamtape",
            "filemoon",
            "dailymotion",
            "ok.ru",
            "youtube.com",
            "youtu.be",
            "voe",
            "streamwish",
            "wishfast",
            "vidhide",
            "vidoza"
        )

        private const val PLAYER_CONCURRENCY = 4
        private const val MAX_WRAPPER_DEPTH = 2
        private const val MAX_NESTED_PLAYERS = 8
        private const val EXTRACTOR_TIMEOUT_MS = 9_000L
        private const val WRAPPER_TIMEOUT_MS = 6_000L
    }
}
