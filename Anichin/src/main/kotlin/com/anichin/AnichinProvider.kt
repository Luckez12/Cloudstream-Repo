package com.anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections

class AnichinProvider : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin 👾"
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
        "rumble.com"
    )

    private fun isFastVideoHost(url: String): Boolean {
        return fastVideoHosts.any { host ->
            url.contains(host, ignoreCase = true)
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
            hasNext = true
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

    /*
     * True rolling bounded concurrency.
     *
     * A maximum of [concurrency] jobs may execute at the same time.
     * As soon as one job finishes, the next waiting item may start
     * immediately. Scan 2 still cannot begin until this whole function
     * returns for Scan 1.
     */
    private suspend fun <T> boundedParallelForEach(
        items: List<T>,
        concurrency: Int = 4,
        block: suspend (T) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        items.map { item ->
            async {
                semaphore.withPermit {
                    try {
                        block(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // A dead host must not cancel the remaining scan.
                    }
                }
            }
        }.awaitAll()

        Unit
    }

    private suspend fun <T> tryOrNull(
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        loadedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!loadedUrls.add(url)) return

        try {
            loadExtractor(
                url,
                referer,
                subtitleCallback
            ) { link ->
                /*
                 * Keep UNKNOWN quality because some extractors expose an HD
                 * stream without a reliable numeric quality value.
                 * Drop only streams explicitly identified as 1..719p.
                 */
                val quality = link.quality
                if (quality <= 0 || quality >= 720) {
                    callback(link)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Failed extractor: continue with the remaining hosts.
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl).document

        /*
         * These collections are touched by several coroutines during Scan 1
         * and Scan 2, therefore they must be thread safe.
         */
        val loadedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val secondScanCandidates =
            Collections.synchronizedMap(linkedMapOf<String, String>())

        /*
         * Decode all Mobius entries first. This is local work and avoids
         * mixing base64 parsing with network scheduling.
         */
        val streamUrls = document
            .select(".mobius option")
            .mapNotNull { option ->
                val encodedValue = option.attr("value").trim()
                if (encodedValue.isBlank()) return@mapNotNull null

                val decodedDocument = runCatching {
                    Jsoup.parse(base64Decode(encodedValue))
                }.getOrNull() ?: return@mapNotNull null

                val iframeUrl = decodedDocument
                    .selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    .orEmpty()

                iframeUrl
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
            }
            .distinct()

        /*
         * Fast direct hosts get first priority and are completely handled
         * before wrapper discovery begins.
         */
        streamUrls
            .filter { isFastVideoHost(it) }
            .forEach { streamUrl ->
                safeLoadExtractor(
                    streamUrl,
                    episodeUrl,
                    loadedUrls,
                    subtitleCallback,
                    callback
                )
            }

        /*
         * Scan 1:
         * Wrapper discovery with rolling bounded parallelism.
         * Maximum four wrapper requests run together. When one finishes,
         * the next waiting wrapper starts immediately.
         *
         * Fast hosts found inside a wrapper are emitted immediately.
         * Non-fast player URLs are queued for Scan 2.
         * Only one nested iframe level is checked.
         */
        val wrapperUrls = streamUrls.filterNot { isFastVideoHost(it) }

        boundedParallelForEach(
            items = wrapperUrls,
            concurrency = 4
        ) { streamUrl ->
            val streamDocument = tryOrNull {
                app.get(
                    streamUrl,
                    headers = mapOf(
                        "Referer" to episodeUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                ).document
            } ?: return@boundedParallelForEach

            val playerUrls = streamDocument
                .select("iframe[src]")
                .mapNotNull { iframe ->
                    iframe.attr("src")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                }
                .distinct()

            playerUrls.forEach playerLoop@ { playerUrl ->
                if (isFastVideoHost(playerUrl)) {
                    safeLoadExtractor(
                        playerUrl,
                        streamUrl,
                        loadedUrls,
                        subtitleCallback,
                        callback
                    )
                    return@playerLoop
                }

                synchronized(secondScanCandidates) {
                    if (!secondScanCandidates.containsKey(playerUrl)) {
                        secondScanCandidates[playerUrl] = streamUrl
                    }
                }

                /*
                 * One light nested check only.
                 * Non-fast nested URLs are queued for Scan 2.
                 */
                val nestedDocument = tryOrNull {
                    app.get(
                        playerUrl,
                        headers = mapOf(
                            "Referer" to streamUrl,
                            "User-Agent" to USER_AGENT
                        )
                    ).document
                } ?: return@playerLoop

                nestedDocument
                    .select("iframe[src]")
                    .mapNotNull { nested ->
                        nested.attr("src")
                            .trim()
                            .takeIf { it.isNotBlank() }
                            ?.let { fixUrl(it) }
                    }
                    .distinct()
                    .forEach nestedLoop@ { nestedUrl ->
                        if (isFastVideoHost(nestedUrl)) {
                            safeLoadExtractor(
                                nestedUrl,
                                playerUrl,
                                loadedUrls,
                                subtitleCallback,
                                callback
                            )
                            return@nestedLoop
                        }

                        synchronized(secondScanCandidates) {
                            if (!secondScanCandidates.containsKey(nestedUrl)) {
                                secondScanCandidates[nestedUrl] = playerUrl
                            }
                        }
                    }
            }
        }

        /*
         * Scan 2 starts only after Scan 1 has completely finished.
         * Direct extractor scan only, maximum 12 candidates.
         * Four extractors at most run together using rolling concurrency.
         */
        val scan2 = synchronized(secondScanCandidates) {
            secondScanCandidates.entries
                .take(12)
                .map { it.key to it.value }
        }

        boundedParallelForEach(
            items = scan2,
            concurrency = 4
        ) { (url, referer) ->
            safeLoadExtractor(
                url,
                referer,
                loadedUrls,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
