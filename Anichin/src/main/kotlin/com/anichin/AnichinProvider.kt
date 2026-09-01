package com.anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AnichinProvider : MainAPI() {

    private val cloudflareKiller = CloudflareKiller()

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin 👾"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val loadLinksTimeoutMs = 90_000L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    private fun Element.getImageUrl(): String? {
        val imageUrl = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("src")
        ).firstOrNull {
            it.isNotBlank() &&
                !it.startsWith("data:", ignoreCase = true)
        }
        if (imageUrl != null) return imageUrl

        val srcSet = listOf(
            attr("data-srcset"),
            attr("srcset")
        ).firstOrNull { it.isNotBlank() } ?: return null

        return srcSet
            .split(",")
            .lastOrNull()
            ?.trim()
            ?.split(" ")
            ?.firstOrNull()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("data:", ignoreCase = true)
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = "$mainUrl/${request.data.trimStart('/')}"
        val separator = if (baseUrl.contains('?')) "&" else "?"
        val document = app.get(
            "$baseUrl${separator}page=$page",
            interceptor = cloudflareKiller,
            timeout = PAGE_TIMEOUT_SECONDS
        ).document

        val home = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.selectFirst(".hpage a.r[href]") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href]") ?: return null
        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".tt, h2")?.text()?.trim().orEmpty() }
        val href = anchor.attr("href").trim()
            .takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: return null
        if (title.isBlank()) return null

        val posterUrl = selectFirst("div.bsx > a img")
            ?.getImageUrl()
            ?.let { fixUrlNull(it) }

        val isMovie = selectFirst(".typez")
            ?.text()
            ?.contains("Movie", ignoreCase = true) == true

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> = coroutineScope {
        val searchQuery = URLEncoder.encode(query, "UTF-8")

        (1..MAX_SEARCH_PAGES).map { page ->
            async {
                tryOrNull {
                    app.get(
                        "$mainUrl/page/$page/?s=$searchQuery",
                        interceptor = cloudflareKiller,
                        timeout = PAGE_TIMEOUT_SECONDS
                    ).document
                        .select("div.listupd > article")
                        .mapNotNull { it.toSearchResult() }
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        val document = app.get(
            fixUrl(url),
            interceptor = cloudflareKiller,
            timeout = PAGE_TIMEOUT_SECONDS
        ).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()
        if (title.isBlank()) {
            throw ErrorLoadingException("Anichin: title not found")
        }

        val poster = (
            document
                .selectFirst("div.thumb img, div.ime img, img.wp-post-image")
                ?.getImageUrl()
                ?: document
                    .selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
        ).orEmpty()

        val description = document
            .selectFirst("div.entry-content")
            ?.text()
            ?.trim()

        val type = document
            .selectFirst(".spe")
            ?.text()
            .orEmpty()

        val genres = document.select(".genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = RELEASE_YEAR.find(type)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val showStatus = when {
            type.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            type.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }

        val recommendations = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val tvType = if (type.contains("Movie", true)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        return if (tvType == TvType.TvSeries) {
            val seasonNumber = SEASON_NUMBER.find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val episodes = document
                .select(".eplister li")
                .mapNotNull { episodeElement ->
                    val link = fixUrl(
                        episodeElement
                            .selectFirst("a")
                            ?.attr("href")
                            .orEmpty()
                    )
                    if (link.isBlank()) return@mapNotNull null

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

                    val episodeNumber = episodeElement
                        .selectFirst(".epl-num")
                        ?.text()
                        ?.let { EPISODE_NUMBER.find(it)?.value?.toIntOrNull() }
                        ?: EPISODE_IN_TITLE.find(episodeTitle)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    val baseEpisodeName = episodeNumber
                        ?.let { "Episode $it" }
                        ?: episodeTitle
                            .replace("Subtitle Indonesia", "", ignoreCase = true)
                            .trim()
                            .ifBlank { "Episode" }

                    val episodeName = episodeSub
                        .takeIf { it.isNotBlank() }
                        ?.let { "$baseEpisodeName - $it Indonesia" }
                        ?: baseEpisodeName

                    val episodeDescription =
                        episodeDate
                            .takeIf { it.isNotEmpty() }
                            ?.let { "Rilis: $it" }

                    newEpisode(link) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = episodePoster
                        this.description = episodeDescription
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
                this.year = year
                this.plot = description
                this.tags = genres
                this.recommendations = recommendations
                this.showStatus = showStatus
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
                this.year = year
                this.plot = description
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    /**
     * Runs a bounded group until one item succeeds. After the first success,
     * keep a short grace period for another ready link, then cancel slow work.
     */
    private suspend fun <T> firstSuccessful(
        items: List<T>,
        concurrency: Int = MAX_PLAYER_CONCURRENCY,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) return@coroutineScope false

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val results = Channel<Boolean>(Channel.UNLIMITED)

        val jobs = items.map { item ->
            launch {
                val succeeded = semaphore.withPermit {
                    try {
                        block(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }

                results.trySend(succeeded)
            }
        }

        var completed = 0
        var succeeded = false

        while (completed < jobs.size) {
            val result = results.receive()
            completed++
            if (result) {
                succeeded = true
                break
            }
        }

        if (succeeded && completed < jobs.size) {
            withTimeoutOrNull(SUCCESS_GRACE_PERIOD_MS) {
                while (completed < jobs.size) {
                    results.receive()
                    completed++
                }
            }
        }

        jobs.forEach { job ->
            if (job.isActive) job.cancel()
        }
        jobs.joinAll()
        results.close()

        succeeded
    }

    private suspend fun <T> tryOrNull(
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String
    ): Document? {
        return withTimeoutOrNull(PLAYER_REQUEST_TIMEOUT_MS) {
            tryOrNull {
                app.get(
                    url,
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    ),
                    interceptor = cloudflareKiller,
                    timeout = PLAYER_REQUEST_TIMEOUT_SECONDS
                ).document
            }
        }
    }

    private fun Document.collectPlayerUrls(baseUrl: String): List<String> {
        return select("iframe[src], iframe[data-src]")
            .mapNotNull { frame ->
                frame.attr("data-src")
                    .ifBlank { frame.attr("src") }
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        if (raw.startsWith("//")) {
                            "https:$raw"
                        } else if (raw.startsWith("http://", true) ||
                            raw.startsWith("https://", true)
                        ) {
                            raw
                        } else {
                            runCatching {
                                java.net.URI(baseUrl).resolve(raw).toString()
                            }.getOrDefault(raw)
                        }
                    }
            }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!attemptedUrls.add(url)) return false

        val emitted = AtomicBoolean(false)

        return try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(
                    url,
                    referer,
                    subtitleCallback
                ) { link ->
                    // Keep every available quality. CloudStream can rank the
                    // links, while SD-only mirrors remain valid fallbacks.
                    if (emittedUrls.add(link.url)) {
                        emitted.set(true)
                        callback(link)
                    }
                }
            }

            emitted.get()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolvePlayerPipeline(
        wrapperUrl: String,
        pageUrl: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Some sites occasionally put the real host directly inside Mobius.
        if (!isSiteUrl(wrapperUrl)) {
            val directSuccess = tryLoadExtractor(
                wrapperUrl,
                pageUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )
            if (directSuccess) return true
        }

        val wrapperDocument = fetchDocument(wrapperUrl, pageUrl) ?: return false
        val playerUrls = wrapperDocument.collectPlayerUrls(wrapperUrl)

        for (playerUrl in playerUrls) {
            val playerSuccess = tryLoadExtractor(
                playerUrl,
                wrapperUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )
            if (playerSuccess) return true

            // Only inspect another iframe level when the direct extractor did
            // not produce a playable link.
            val nestedDocument = fetchDocument(playerUrl, wrapperUrl) ?: continue
            val nestedUrls = nestedDocument.collectPlayerUrls(playerUrl)

            for (nestedUrl in nestedUrls) {
                val nestedSuccess = tryLoadExtractor(
                    nestedUrl,
                    playerUrl,
                    attemptedUrls,
                    emittedUrls,
                    subtitleCallback,
                    callback
                )
                if (nestedSuccess) return true
            }
        }

        return false
    }

    private fun isSiteUrl(url: String): Boolean {
        val siteHost = runCatching { java.net.URI(mainUrl).host }
            .getOrNull()
            ?: return false
        val urlHost = runCatching { java.net.URI(url).host }
            .getOrNull()
            ?: return false

        return urlHost.equals(siteHost, ignoreCase = true)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val document = app.get(
            episodeUrl,
            interceptor = cloudflareKiller,
            timeout = PAGE_TIMEOUT_SECONDS
        ).document

        val attemptedUrls = ConcurrentHashMap.newKeySet<String>()
        val emittedUrls = ConcurrentHashMap.newKeySet<String>()

        val players = document
            .select(".mobius option")
            .mapNotNull { option ->
                val encodedValue = option.attr("value").trim()
                if (encodedValue.isBlank()) return@mapNotNull null

                val decodedDocument = runCatching {
                    Jsoup.parse(base64Decode(encodedValue))
                }.getOrNull() ?: return@mapNotNull null

                val wrapperUrl = decodedDocument
                    .selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    .orEmpty()

                wrapperUrl
                    .takeIf { it.isNotBlank() }
                    ?.let { PlayerOption(option.text().trim(), fixUrl(it)) }
            }
            .distinctBy { it.url }
            .sortedBy { it.priority() }
            .take(MAX_PLAYER_OPTIONS)

        if (players.isEmpty()) {
            val staticPlayers = document.collectPlayerUrls(episodeUrl)
            return firstSuccessful(staticPlayers) { playerUrl ->
                tryLoadExtractor(
                    playerUrl,
                    episodeUrl,
                    attemptedUrls,
                    emittedUrls,
                    subtitleCallback,
                    callback
                )
            }
        }

        val preferredPlayers = players.take(FAST_PLAYER_OPTIONS)
        val preferredSuccess = firstSuccessful(preferredPlayers) { player ->
            resolvePlayerPipeline(
                player.url,
                episodeUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )
        }
        if (preferredSuccess) return true

        val fallbackPlayers = players.drop(FAST_PLAYER_OPTIONS)
        return firstSuccessful(fallbackPlayers) { player ->
            resolvePlayerPipeline(
                player.url,
                episodeUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )
        }
    }

    private fun PlayerOption.priority(): Int {
        val value = label.lowercase()
        return when {
            value.contains("ok.ru") || value.contains("okru") -> 0
            value.contains("dailymotion") -> 1
            value.contains("rumble") -> 2
            value.contains("streamruby") -> 3
            value.contains("dood") -> 4
            value.contains("vidhide") || value.contains("vidguard") -> 5
            else -> 10
        }
    }

    private data class PlayerOption(
        val label: String,
        val url: String
    )

    companion object {
        private const val PAGE_TIMEOUT_SECONDS = 30L
        private const val PLAYER_REQUEST_TIMEOUT_SECONDS = 15L
        private const val PLAYER_REQUEST_TIMEOUT_MS = 16_000L
        private const val EXTRACTOR_TIMEOUT_MS = 15_000L
        private const val MAX_SEARCH_PAGES = 3
        private const val MAX_PLAYER_CONCURRENCY = 4
        private const val FAST_PLAYER_OPTIONS = 4
        private const val MAX_PLAYER_OPTIONS = 12
        private const val SUCCESS_GRACE_PERIOD_MS = 1_500L

        private val EPISODE_NUMBER = Regex("\\d+")
        private val EPISODE_IN_TITLE = Regex(
            "(?i)Episode\\s*(\\d+)"
        )
        private val SEASON_NUMBER = Regex(
            "(?i)Season\\s*(\\d+)"
        )
        private val RELEASE_YEAR = Regex(
            "(?i)Tanggal\\s+rilis[^0-9]*(?:[A-Za-z]{3}\\s+\\d{1,2},\\s*)?(\\d{4})"
        )
    }
}
