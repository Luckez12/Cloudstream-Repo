package com.anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    private val fastVideoHosts = setOf(
        "ok.ru",
        "odnoklassniki",
        "rumble.com",
        "dailymotion.com",
        "geo.dailymotion.com",
        "rubyvidhub.com",
        "streamruby.com",
        "streamruby.net"
    )

    private fun isFastVideoHost(url: String): Boolean {
        return fastVideoHosts.any { host ->
            url.contains(host, ignoreCase = true)
        }
    }

    private fun streamPriority(url: String): Int {
        val lower = url.lowercase()
        return when {
            isFastVideoHost(url) -> 0
            "dood" in lower || "streamruby" in lower -> 1
            else -> 2
        }
    }

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

        val document = app.get(
            "${mainUrl}/${request.data}&page=$page"
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
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse {

        val title = select("div.bsx > a")
            .attr("title")
            .trim()

        val href = fixUrl(
            select("div.bsx > a")
                .attr("href")
        )

        val posterUrl = selectFirst("div.bsx > a img")
            ?.getImageUrl()
            ?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()
        val searchQuery = URLEncoder.encode(query, "UTF-8")

        for (page in 1..3) {

            val document = app.get(
                "${mainUrl}/page/$page/?s=$searchQuery"
            ).document

            val results = document
                .select("div.listupd > article")
                .mapNotNull { it.toSearchResult() }

            if (results.isEmpty()) break

            searchResponse.addAll(results)
        }

        return searchResponse.distinctBy { it.url }
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

        val description = document
            .selectFirst("div.entry-content")
            ?.text()
            ?.trim()

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
                .map { episodeElement ->

                    val link = fixUrl(
                        episodeElement
                            .selectFirst("a")
                            ?.attr("href")
                            .orEmpty()
                    )

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

                    val cleanTitle = episodeTitle
                        .replace(
                            Regex(
                                "Episode\\s*\\d+\\s*Subtitle Indonesia",
                                RegexOption.IGNORE_CASE
                            ),
                            ""
                        )
                        .replace(
                            "Subtitle Indonesia",
                            ""
                        )
                        .trim()

                    val episodeName =
                        "- $cleanTitle $episodeSub Indonesia".trim()

                    val episodeDescription =
                        episodeDate
                            .takeIf { it.isNotEmpty() }
                            ?.let { "Rilis: $it" }

                    newEpisode(link) {
                        this.name = episodeName
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

    private fun isAcceptedQuality(link: ExtractorLink): Boolean {
        return link.quality == Qualities.Unknown.value ||
            link.quality >= Qualities.P720.value
    }

    /**
     * First-valid-source rule:
     * - 720p and above are accepted immediately.
     * - Unknown quality is accepted because some HLS/direct links do not expose
     *   a resolution before the player reads the manifest.
     * - Known qualities below 720p are dropped completely.
     *
     * Returns true only when this extractor wins the first-source race.
     */
    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        loadedUrls: MutableSet<String>,
        winner: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val shouldLoad = synchronized(loadedUrls) {
            loadedUrls.add(url)
        }
        if (!shouldLoad || winner.get()) return false

        val emitted = AtomicBoolean(false)

        runCatching {
            loadExtractor(
                url,
                referer,
                subtitleCallback
            ) { link ->
                if (
                    isAcceptedQuality(link) &&
                    winner.compareAndSet(false, true)
                ) {
                    emitted.set(true)
                    synchronized(callback) {
                        callback(link)
                    }
                }
            }
        }.onFailure {
            // Ignore failed/unsupported extractors and continue to the next URL.
        }

        return emitted.get()
    }

    /**
     * Scan one Mobius stream path.
     *
     * The URL itself is offered to CloudStream's extractor first. If that
     * already resolves to a playable source, no wrapper/nested request is made.
     * Only unresolved URLs are opened as HTML to discover iframe targets.
     */
    private suspend fun scanStreamUrl(
        streamUrl: String,
        episodeUrl: String,
        loadedUrls: MutableSet<String>,
        winner: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (winner.get()) return true

        if (
            safeLoadExtractor(
                streamUrl,
                episodeUrl,
                loadedUrls,
                winner,
                subtitleCallback,
                callback
            )
        ) {
            return true
        }

        if (winner.get()) return true

        val streamDocument = runCatching {
            app.get(
                streamUrl,
                headers = mapOf(
                    "Referer" to episodeUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
            ).document
        }.getOrNull() ?: return false

        val playerUrls = streamDocument
            .select("iframe[src]")
            .mapNotNull { iframe ->
                iframe.attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
            }
            .distinct()
            .sortedBy { streamPriority(it) }

        for (playerUrl in playerUrls) {
            if (winner.get()) return true

            /*
             * Try the extractor BEFORE manually opening the player page.
             * This removes the old duplicate request path for Dood,
             * StreamRuby, Ok.ru, Rumble, Dailymotion and any other host
             * already supported by CloudStream.
             */
            if (
                safeLoadExtractor(
                    playerUrl,
                    streamUrl,
                    loadedUrls,
                    winner,
                    subtitleCallback,
                    callback
                )
            ) {
                return true
            }

            if (winner.get()) return true

            /*
             * Unknown/unsupported player only:
             * one nested iframe check, no deep crawl.
             */
            val nestedDocument = runCatching {
                app.get(
                    playerUrl,
                    headers = mapOf(
                        "Referer" to streamUrl,
                        "User-Agent" to USER_AGENT
                    )
                ).document
            }.getOrNull() ?: continue

            val nestedUrls = nestedDocument
                .select("iframe[src]")
                .mapNotNull { nested ->
                    nested.attr("src")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                }
                .distinct()
                .sortedBy { streamPriority(it) }

            for (nestedUrl in nestedUrls) {
                if (winner.get()) return true

                if (
                    safeLoadExtractor(
                        nestedUrl,
                        playerUrl,
                        loadedUrls,
                        winner,
                        subtitleCallback,
                        callback
                    )
                ) {
                    return true
                }
            }
        }

        return winner.get()
    }

    /**
     * Real concurrency limit, not fixed batches.
     *
     * At most four workers are active. As soon as one worker finishes a failed
     * path, it immediately takes the next URL. A slow server therefore does not
     * block the other queued servers.
     *
     * The first accepted source cancels the remaining workers so loadLinks can
     * finish immediately and hand playback back to CloudStream.
     */
    private suspend fun findFirstPlayable(
        streamUrls: List<String>,
        episodeUrl: String,
        loadedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        if (streamUrls.isEmpty()) return@coroutineScope false

        val queue = Channel<String>(Channel.UNLIMITED)
        streamUrls.forEach { queue.trySend(it) }
        queue.close()

        val winner = AtomicBoolean(false)
        val completed = AtomicInteger(0)
        val result = CompletableDeferred<Boolean>()

        val workerCount = minOf(4, streamUrls.size)

        val workers = List(workerCount) {
            launch {
                for (streamUrl in queue) {
                    if (winner.get() || result.isCompleted) break

                    val found = runCatching {
                        scanStreamUrl(
                            streamUrl,
                            episodeUrl,
                            loadedUrls,
                            winner,
                            subtitleCallback,
                            callback
                        )
                    }.getOrDefault(false)

                    val done = completed.incrementAndGet()

                    if (found || winner.get()) {
                        result.complete(true)
                        break
                    }

                    if (done >= streamUrls.size) {
                        result.complete(false)
                        break
                    }
                }
            }
        }

        val found = result.await()

        if (found) {
            workers.forEach { it.cancel() }
        }

        workers.forEach { worker ->
            runCatching { worker.join() }
        }

        found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl).document
        val loadedUrls = mutableSetOf<String>()

        /*
         * Mobius decoding is local and cheap. Decode everything first, then put
         * direct/known hosts at the front of the worker queue.
         */
        val streamUrls = document
            .select(".mobius option")
            .mapNotNull { option ->
                val encodedValue = option.attr("value").trim()
                if (encodedValue.isBlank()) return@mapNotNull null

                val decodedDocument = runCatching {
                    Jsoup.parse(base64Decode(encodedValue))
                }.getOrNull() ?: return@mapNotNull null

                decodedDocument
                    .selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
            }
            .distinct()
            .sortedBy { streamPriority(it) }

        return findFirstPlayable(
            streamUrls,
            episodeUrl,
            loadedUrls,
            subtitleCallback,
            callback
        )
    }

}
