package com.anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
        "geo.dailymotion.com"
    )

    private fun isFastVideoHost(url: String): Boolean {
        return fastVideoHosts.any { host ->
            url.contains(host, ignoreCase = true)
        }
    }

    private fun secondScanPriority(url: String): Int {
        val lower = url.lowercase()
        return when {
            "dood" in lower -> 0
            "streamruby" in lower -> 1
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

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        loadedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val shouldLoad = synchronized(loadedUrls) {
            loadedUrls.add(url)
        }
        if (!shouldLoad) return

        val lowerQualityLinks = mutableListOf<ExtractorLink>()
        var hasHdLink = false

        runCatching {
            loadExtractor(
                url,
                referer,
                subtitleCallback
            ) { link ->
                if (link.quality >= Qualities.P720.value) {
                    hasHdLink = true
                    synchronized(callback) {
                        callback(link)
                    }
                } else {
                    lowerQualityLinks.add(link)
                }
            }
        }.onFailure {
            // Ignore a failed extractor and continue scanning other servers.
        }

        /*
         * Quality rule:
         * If this server has 720p or better, hide its lower variants.
         * If it has no 720p+ link, keep only its best available fallback.
         * Unknown-quality links are preserved when they are the only result.
         */
        if (!hasHdLink && lowerQualityLinks.isNotEmpty()) {
            val bestFallbackQuality = lowerQualityLinks.maxOf { it.quality }
            lowerQualityLinks
                .filter { it.quality == bestFallbackQuality }
                .forEach { link ->
                    synchronized(callback) {
                        callback(link)
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

        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl).document
        val loadedUrls = mutableSetOf<String>()
        val secondScanCandidates = linkedMapOf<String, String>()

        fun addSecondScanCandidate(url: String, referer: String) {
            synchronized(secondScanCandidates) {
                if (!secondScanCandidates.containsKey(url)) {
                    secondScanCandidates[url] = referer
                }
            }
        }

        /*
         * Decode every Mobius option first. This part is local and cheap.
         * Network work is then executed in small concurrent batches.
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

        /*
         * Scan 1:
         * Fast hosts first, then wrapper hosts.
         * Four concurrent jobs keeps latency down without flooding the site.
         */
        streamUrls
            .sortedBy { streamUrl ->
                if (isFastVideoHost(streamUrl)) 0 else 1
            }
            .chunked(4)
            .forEach { batch ->
                batch.apmap { streamUrl ->

                    if (isFastVideoHost(streamUrl)) {
                        safeLoadExtractor(
                            streamUrl,
                            episodeUrl,
                            loadedUrls,
                            subtitleCallback,
                            callback
                        )
                        return@apmap
                    }

                    val streamDocument = runCatching {
                        app.get(
                            streamUrl,
                            headers = mapOf(
                                "Referer" to episodeUrl,
                                "Origin" to mainUrl,
                                "User-Agent" to USER_AGENT
                            )
                        ).document
                    }.getOrNull() ?: return@apmap

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

                        addSecondScanCandidate(playerUrl, streamUrl)

                        /*
                         * One nested check only.
                         * Keeping this depth protects loading time while still
                         * finding supported hosts hidden behind one extra iframe.
                         */
                        val nestedDocument = runCatching {
                            app.get(
                                playerUrl,
                                headers = mapOf(
                                    "Referer" to streamUrl,
                                    "User-Agent" to USER_AGENT
                                )
                            ).document
                        }.getOrNull() ?: return@playerLoop

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

                                addSecondScanCandidate(nestedUrl, playerUrl)
                            }
                    }
                }
            }

        /*
         * Scan 2:
         * Keep every discovered candidate. Dood and StreamRuby retain priority,
         * but the old hard limit of 12 has been removed.
         * Extractors run in controlled batches of four.
         */
        val secondScanSnapshot = synchronized(secondScanCandidates) {
            secondScanCandidates.entries
                .map { it.key to it.value }
        }

        secondScanSnapshot
            .sortedBy { (url, _) -> secondScanPriority(url) }
            .chunked(4)
            .forEach { batch ->
                batch.apmap { (url, referer) ->
                    safeLoadExtractor(
                        url,
                        referer,
                        loadedUrls,
                        subtitleCallback,
                        callback
                    )
                }
            }

        return true
    }
}
