package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnichinV2 : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin V2"
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

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        loadedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!loadedUrls.add(url)) return

        runCatching {
            loadExtractor(
                url,
                referer,
                subtitleCallback,
                callback
            )
        }.onFailure {
            // ignore failed extractor
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

        /*
         * Scan 1:
         * Immediate fast scan.
         * OkRu, Odnoklassniki and Rumble are loaded as soon as they are found.
         * This part must stay light so playback can start faster.
         */
        document.select(".mobius option").forEach optionLoop@ { option ->

            val encodedValue = option.attr("value").trim()
            if (encodedValue.isBlank()) return@optionLoop

            val decodedDocument = runCatching {
                Jsoup.parse(base64Decode(encodedValue))
            }.getOrNull() ?: return@optionLoop

            val iframeUrl = decodedDocument
                .selectFirst("iframe[src]")
                ?.attr("src")
                ?.trim()
                .orEmpty()

            if (iframeUrl.isBlank()) return@optionLoop

            val streamUrl = fixUrl(iframeUrl)

            if (isFastVideoHost(streamUrl)) {
                safeLoadExtractor(
                    streamUrl,
                    episodeUrl,
                    loadedUrls,
                    subtitleCallback,
                    callback
                )
                return@optionLoop
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
            }.getOrNull() ?: return@optionLoop

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

                secondScanCandidates[playerUrl] = streamUrl

                /*
                 * One light nested check only.
                 * This keeps the old fast behavior for OkRu/Rumble hidden inside one wrapper.
                 * Non-fast nested URLs are saved for Scan 2, not extracted here.
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

                        secondScanCandidates[nestedUrl] = playerUrl
                    }
            }
        }

        /*
         * Scan 2:
         * Direct player scan only.
         * No deep crawling, no raw HTML crawling, no background coroutine.
         * This follows the working AnichinX style that brings back Dood and StreamRuby.
         */
        secondScanCandidates
            .entries
            .take(12)
            .forEach { (url, referer) ->
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
