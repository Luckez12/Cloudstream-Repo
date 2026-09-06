package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AnichinProvider : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Latest Release"
    )

    private data class PlayerOption(
        val label: String,
        val url: String
    )

    private fun parseItems(
        doc: Document,
        selector: String
    ): List<SearchResponse> {
        return doc.select(selector).mapNotNull { item ->
            val anchor = item.selectFirst(".bsx > a, a") ?: return@mapNotNull null
            val href = anchor.attr("href").ifBlank { return@mapNotNull null }

            val poster = item.selectFirst(".limit img, img")?.let { image ->
                image.attr("data-src")
                    .ifBlank { image.attr("data-lazy-src") }
                    .ifBlank { image.attr("src") }
                    .ifBlank { null }
            }

            val type = item.selectFirst(".typez")?.text()?.trim()
            val tvType = when {
                type.equals("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val title = item.selectFirst(".tt")?.ownText()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2, h2, h3")?.text()?.trim()?.ifBlank { null }
                ?: anchor.attr("title").trim().ifBlank { null }
                ?: return@mapNotNull null

            val epText = item.selectFirst(".bt .epx, .epx, .ep")?.text()?.trim()
            val epNum = Regex("""(\d+)""")
                .find(epText.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            newAnimeSearchResponse(title, href, tvType) {
                posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.name == "Popular Today") {
            val doc = app.get(mainUrl).document

            val items = parseItems(
                doc,
                ".releases.hothome + .listupd.normal article.bs, " +
                    ".listupd.popular article.bs, " +
                    ".listupd article.bs"
            )

            return newHomePageResponse(request.name, items)
        }

        val url = if (page > 1) {
            "$mainUrl/page/$page/"
        } else {
            mainUrl
        }

        val doc = app.get(url).document

        val items = parseItems(
            doc,
            ".releases.latesthome + .listupd.normal article.bs, " +
                ".listupd.normal article.bs, " +
                ".listupd article.bs"
        )

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.replace(" ", "+")}"
        ).document

        return parseItems(
            doc,
            "div.listupd article.bs, article.bs"
        )
    }

    private fun episodeToSeriesUrl(url: String): String? {
        val slug = url
            .substringBefore("?")
            .trimEnd('/')
            .substringAfterLast("/")

        val seriesSlug = Regex(
            """-episode-\d+(?:\.\d+)?(?:-[^/]*)?$""",
            RegexOption.IGNORE_CASE
        ).replace(slug, "")

        if (seriesSlug == slug || seriesSlug.isBlank()) {
            return null
        }

        return "$mainUrl/seri/$seriesSlug/"
    }

    private suspend fun getEpisodesFromRestApi(
        animeUrl: String,
        poster: String?
    ): MutableList<Episode> {
        val slug = animeUrl
            .substringBefore("?")
            .trimEnd('/')
            .substringAfterLast("/")

        val categoryRaw = app.get(
            "$mainUrl/wp-json/wp/v2/categories" +
                "?slug=$slug&per_page=1&_fields=id"
        ).text

        val categoryList =
            tryParseJson<List<Map<String, Any?>>>(categoryRaw)

        val categoryId =
            categoryList
                ?.firstOrNull()
                ?.get("id")
                ?.toString()
                ?.substringBefore(".")

        val episodes = mutableListOf<Episode>()

        if (categoryId == null) {
            return episodes
        }

        var apiPage = 1

        while (apiPage <= MAX_SEARCH_PAGES) {
            val postRaw = app.get(
                "$mainUrl/wp-json/wp/v2/posts" +
                    "?categories=$categoryId" +
                    "&per_page=100" +
                    "&page=$apiPage" +
                    "&_fields=id,title,link"
            ).text

            val posts =
                tryParseJson<List<Map<String, Any?>>>(postRaw)
                    ?: break

            if (posts.isEmpty()) {
                break
            }

            posts.forEach { post ->
                val epHref =
                    post["link"]?.toString()
                        ?: return@forEach

                val titleObj =
                    post["title"] as? Map<*, *>

                val epTitle =
                    titleObj
                        ?.get("rendered")
                        ?.toString()
                        ?.replace(Regex("<[^>]+>"), "")
                        ?.trim()
                        ?.ifBlank { null }
                        ?: return@forEach

                val epNum = Regex(
                    """(?:Episode|Ep|Eps|E)\s*(\d+(?:\.\d+)?)""",
                    RegexOption.IGNORE_CASE
                ).find(epTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        name = epTitle
                        episode = epNum?.toInt()
                        posterUrl = poster
                    }
                )
            }

            if (posts.size < 100) {
                break
            }

            apiPage++
        }

        return episodes
    }

    private fun getEpisodesFromDocument(
        doc: Document,
        poster: String?
    ): MutableList<Episode> {
        val episodes = mutableListOf<Episode>()

        doc.select(
            ".eplister a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href], " +
                ".bixbox.bxcl.epcheck a[href], " +
                "a[href*='-episode-']"
        ).forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach

            val text = anchor.text().trim()
            if (text.isBlank()) return@forEach

            val epNum = Regex(
                """(?:Episode|Ep|Eps|E)?\s*(\d+(?:\.\d+)?)""",
                RegexOption.IGNORE_CASE
            ).find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

            episodes.add(
                newEpisode(href) {
                    name = text
                    episode = epNum?.toInt()
                    posterUrl = poster
                }
            )
        }

        return episodes
            .distinctBy { it.data }
            .toMutableList()
    }

    override suspend fun load(url: String): LoadResponse {
        val isEpisode =
            url.contains("-episode-", ignoreCase = true)

        val seriesUrl =
            if (isEpisode) episodeToSeriesUrl(url)
            else null

        val animeUrl =
            seriesUrl ?: url

        val doc =
            app.get(animeUrl).document

        val title =
            doc.selectFirst("h1.entry-title")?.text()?.trim()
                ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
                ?: doc.selectFirst(".infox h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                ?: throw ErrorLoadingException("Title not found")

        val poster =
            doc.selectFirst(".thumb img")?.let { image ->
                image.attr("data-src")
                    .ifBlank { image.attr("data-lazy-src") }
                    .ifBlank { image.attr("src") }
                    .ifBlank { null }
            }
                ?: doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.ifBlank { null }

        val synopsis =
            doc.select(".desc p, .entry-content p, .synp .entry-content")
                .text()
                .trim()
                .ifBlank { null }

        val tags =
            doc.select(".genxed a, .genx a")
                .mapNotNull {
                    it.text().trim().ifBlank { null }
                }
                .distinct()

        val status =
            doc.select(".spe span, .info-content .spe span")
                .firstOrNull {
                    it.text().contains(
                        "Status",
                        ignoreCase = true
                    )
                }
                ?.text()
                ?.substringAfter(":")
                ?.trim()

        val year =
            Regex("""\b(19|20)\d{2}\b""")
                .find(
                    doc.select(
                        ".spe, .info-content, .entry-content"
                    ).text()
                )
                ?.value
                ?.toIntOrNull()

        val episodes = try {
            getEpisodesFromRestApi(
                animeUrl,
                poster
            )
        } catch (_: Exception) {
            mutableListOf()
        }

        if (episodes.isEmpty()) {
            episodes.addAll(
                getEpisodesFromDocument(
                    doc,
                    poster
                )
            )
        }

        episodes.sortWith(
            compareBy<Episode> {
                it.episode ?: Int.MAX_VALUE
            }.thenBy {
                it.name.orEmpty()
            }
        )

        if (episodes.isNotEmpty()) {
            return newAnimeLoadResponse(
                title,
                animeUrl,
                TvType.Anime
            ) {
                engName = title
                posterUrl = poster
                addEpisodes(
                    DubStatus.Subbed,
                    episodes
                )
                plot = synopsis
                this.tags = tags
                this.year = year

                showStatus =
                    when {
                        status?.contains(
                            "Completed",
                            ignoreCase = true
                        ) == true -> ShowStatus.Completed

                        status?.contains(
                            "Ongoing",
                            ignoreCase = true
                        ) == true -> ShowStatus.Ongoing

                        else -> null
                    }

                doc.selectFirst(
                    "[data-alid], [data-anilist]"
                )?.let { element ->
                    val id =
                        element.attr("data-alid")
                            .ifBlank {
                                element.attr("data-anilist")
                            }
                            .toIntOrNull()

                    if (id != null) {
                        addAniListId(id)
                    }
                }

                doc.selectFirst(
                    "[data-malid], [data-mal]"
                )?.let { element ->
                    val id =
                        element.attr("data-malid")
                            .ifBlank {
                                element.attr("data-mal")
                            }
                            .toIntOrNull()

                    if (id != null) {
                        addMalId(id)
                    }
                }
            }
        }

        return newMovieLoadResponse(
            title,
            animeUrl,
            TvType.AnimeMovie,
            animeUrl
        ) {
            posterUrl = poster
            plot = synopsis
            this.tags = tags
            this.year = year
        }
    }

    private fun absoluteUrl(
        base: String,
        raw: String
    ): String? {
        val value =
            raw.trim()
                .replace("&amp;", "&")
                .replace("\\/", "/")

        if (value.isBlank()) {
            return null
        }

        if (value.startsWith(
                "javascript:",
                ignoreCase = true
            )
        ) {
            return null
        }

        return try {
            when {
                value.startsWith("//") -> {
                    val scheme =
                        URI(base).scheme ?: "https"

                    "$scheme:$value"
                }

                value.startsWith("http://") ||
                    value.startsWith("https://") -> {
                    value
                }

                else -> {
                    URI(base)
                        .resolve(value)
                        .toString()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePlayerValue(
        rawValue: String,
        baseUrl: String
    ): String? {
        val value = rawValue.trim()
        if (value.isBlank()) return null

        // Only resolve values that are clearly URLs/paths. A Base64 payload is
        // otherwise a valid relative-URI string and would be misread as a path.
        val looksLikeUrl = value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//") ||
            value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../")

        if (looksLikeUrl) {
            return absoluteUrl(baseUrl, value)
        }

        // Some player options contain raw iframe HTML.
        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        val decoded = runCatching {
            String(Base64.decode(value, Base64.DEFAULT))
        }.getOrNull() ?: return null

        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        return Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).find(decoded)
            ?.value
            ?.let { absoluteUrl(baseUrl, it) }
    }

    private fun Document.collectTopLevelPlayers(
        pageUrl: String
    ): List<PlayerOption> {
        val players =
            mutableListOf<PlayerOption>()

        select(
            "#embed_holder iframe[src], " +
                "#embed_holder iframe[data-src], " +
                ".player-embed iframe[src], " +
                ".player-embed iframe[data-src], " +
                ".embed_holder iframe[src], " +
                ".embed_holder iframe[data-src], " +
                "iframe.metaframe[src]"
        ).forEachIndexed { index, iframe ->
            val src =
                iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }

            val url =
                absoluteUrl(
                    pageUrl,
                    src
                )
                    ?: return@forEachIndexed

            players.add(
                PlayerOption(
                    label = "Direct ${index + 1}",
                    url = url
                )
            )
        }

        select(
            ".mobius option, " +
                "select.mirror option, " +
                ".mirror option, " +
                ".server option, " +
                "option[data-index], " +
                "option[data-video], " +
                "option[data-src]"
        ).forEach { option ->
            val label =
                option.text()
                    .trim()
                    .ifBlank {
                        option.attr("data-index")
                            .trim()
                    }
                    .ifBlank {
                        "Server"
                    }

            val candidates = listOf(
                option.attr("value"),
                option.attr("data-video"),
                option.attr("data-src"),
                option.attr("data-embed")
            )

            candidates.forEach { raw ->
                val url =
                    decodePlayerValue(
                        raw,
                        pageUrl
                    )
                        ?: return@forEach

                players.add(
                    PlayerOption(
                        label = label,
                        url = url
                    )
                )
            }
        }

        return players
            .filter {
                it.url.startsWith("http")
            }
            .distinctBy {
                it.url
            }
            .sortedBy {
                it.priority()
            }
    }

    private fun Document.collectNestedPlayerUrls(
        pageUrl: String
    ): List<String> {
        val urls =
            mutableListOf<String>()

        select(
            "iframe[src], " +
                "iframe[data-src], " +
                "video source[src], " +
                "source[src]"
        ).forEach { element ->
            val raw =
                element.attr("src")
                    .ifBlank {
                        element.attr("data-src")
                    }

            absoluteUrl(
                pageUrl,
                raw
            )?.let {
                urls.add(it)
            }
        }

        select("script").forEach { script ->
            val text =
                script.data()
                    .ifBlank {
                        script.html()
                    }

            Regex(
                """(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+)["']""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.MULTILINE
                )
            ).findAll(text)
                .forEach { match ->
                    match.groupValues
                        .getOrNull(1)
                        ?.let {
                            absoluteUrl(
                                pageUrl,
                                it
                            )
                        }
                        ?.let {
                            urls.add(it)
                        }
                }

            Regex(
                """https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text)
                .forEach { match ->
                    urls.add(
                        match.value
                            .replace("\\/", "/")
                    )
                }
        }

        return urls
            .filter {
                it.startsWith("http")
            }
            .distinct()
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String
    ): Document? {
        return try {
            withTimeoutOrNull(PLAYER_REQUEST_TIMEOUT_MS) {
                app.get(url, referer = referer).document
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey =
            "$url\u0000$referer"

        if (!attemptedUrls.add(attemptKey)) {
            return false
        }

        val emitted =
            AtomicBoolean(false)

        val wrappedCallback:
            (ExtractorLink) -> Unit = { link ->

            if (emittedUrls.add(link.url)) {
                emitted.set(true)
                callback(link)
            }
        }

        return try {
            withTimeoutOrNull(
                EXTRACTOR_TIMEOUT_MS
            ) {
                loadExtractor(
                    url,
                    referer,
                    { _ ->
                        // Anichin streams already contain the intended subtitles.
                        // Ignore extractor-provided tracks such as Dailymotion
                        // autogenerated captions.
                    },
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

    private suspend fun <T> collectSuccessful(
        items: List<T>,
        concurrency: Int,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) {
            return@coroutineScope false
        }

        val semaphore =
            Semaphore(
                concurrency.coerceAtLeast(1)
            )

        items.map { item ->
            async {
                semaphore.withPermit {
                    try {
                        block(item)
                    } catch (
                        e: CancellationException
                    ) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
            .awaitAll()
            .any {
                it
            }
    }

    /**
     * Hybrid two-lane scheduler for Cloudstream link loading.
     *
     * The first three priority players get their own fast lane so OK.ru,
     * Dailymotion, Rumble, or whichever sources are ranked first can resolve
     * immediately. The rest of the player list is processed at the same time
     * in a separate bounded lane.
     *
     * Nothing is cancelled after the first success. Extractor callbacks are
     * emitted as soon as each source resolves, while all remaining sources
     * continue inside the same Cloudstream coroutine lifecycle.
     */
    private suspend fun <T> collectTwoLane(
        items: List<T>,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) {
            return@coroutineScope false
        }

        val fastLane =
            items.take(FAST_LANE_SIZE)

        val fullLane =
            items.drop(FAST_LANE_SIZE)

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

        listOf(
            fastJob,
            fullJob
        )
            .awaitAll()
            .any { it }
    }

    private suspend fun resolvePlayerPipeline(
        wrapperUrl: String,
        episodeUrl: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directSuccess =
            tryLoadExtractor(
                wrapperUrl,
                episodeUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )

        if (directSuccess) {
            return true
        }

        val wrapperDocument =
            fetchDocument(
                wrapperUrl,
                episodeUrl
            )
                ?: return false

        val playerUrls =
            wrapperDocument
                .collectNestedPlayerUrls(
                    wrapperUrl
                )

        return collectSuccessful(
            playerUrls,
            MAX_NESTED_CONCURRENCY
        ) { playerUrl ->

            val playerSuccess =
                tryLoadExtractor(
                    playerUrl,
                    wrapperUrl,
                    attemptedUrls,
                    emittedUrls,
                    subtitleCallback,
                    callback
                )

            if (playerSuccess) {
                true
            } else {
                val nestedDocument =
                    fetchDocument(
                        playerUrl,
                        wrapperUrl
                    )

                if (nestedDocument == null) {
                    false
                } else {
                    val nestedUrls =
                        nestedDocument
                            .collectNestedPlayerUrls(
                                playerUrl
                            )

                    collectSuccessful(
                        nestedUrls,
                        MAX_NESTED_CONCURRENCY
                    ) { nestedUrl ->
                        tryLoadExtractor(
                            nestedUrl,
                            playerUrl,
                            attemptedUrls,
                            emittedUrls,
                            subtitleCallback,
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
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {
        val document =
            withTimeoutOrNull(
                EPISODE_REQUEST_TIMEOUT_MS
            ) {
                app.get(data).document
            }
                ?: return false

        val attemptedUrls:
            MutableSet<String> =
            ConcurrentHashMap
                .newKeySet()

        val emittedUrls:
            MutableSet<String> =
            ConcurrentHashMap
                .newKeySet()

        val players =
            document
                .collectTopLevelPlayers(data)

        if (players.isEmpty()) {
            val staticPlayers =
                document
                    .collectNestedPlayerUrls(data)

            return collectTwoLane(
                staticPlayers
            ) { playerUrl ->
                tryLoadExtractor(
                    playerUrl,
                    data,
                    attemptedUrls,
                    emittedUrls,
                    subtitleCallback,
                    callback
                )
            }
        }

        return collectTwoLane(
            players
        ) { player ->
            resolvePlayerPipeline(
                player.url,
                data,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )
        }
    }

    private fun PlayerOption.priority(): Int {
        val value =
            "$label $url".lowercase()

        return when {
            value.contains("ok.ru") ||
                value.contains("okru") -> 0

            value.contains("dailymotion") -> 1

            value.contains("rumble") -> 2

            value.contains("anichin.stream") -> 3

            value.contains(
                "anichin-player.web.id"
            ) -> 4

            value.contains("streamruby") ||
                value.contains("ruby") -> 5

            value.contains("vidhide") -> 6

            else -> 20
        }
    }

    companion object {
        private const val MAX_SEARCH_PAGES = 10

        // Top priority sources get three dedicated workers. The remaining
        // sources get another three workers, giving fast first-link response
        // without sacrificing the rest of the server list.
        private const val FAST_LANE_SIZE = 3
        private const val FAST_LANE_CONCURRENCY = 3
        private const val FULL_LANE_CONCURRENCY = 3
        private const val MAX_NESTED_CONCURRENCY = 2

        private const val EPISODE_REQUEST_TIMEOUT_MS =
            10_000L

        private const val PLAYER_REQUEST_TIMEOUT_MS =
            7_000L

        private const val EXTRACTOR_TIMEOUT_MS =
            8_000L
    }
}
