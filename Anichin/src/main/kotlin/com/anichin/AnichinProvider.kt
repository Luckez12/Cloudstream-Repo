package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
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
import org.jsoup.nodes.Element
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

    /**
     * Current Anichin series pages live directly at /{series-slug}/.
     * Homepage/archive cards often point to the newest episode post, so
     * normalize those links back to the real series page before Cloudstream
     * stores them in the catalogue.
     */
    private fun normalizeCatalogUrl(rawHref: String): String? {
        val absolute = absoluteUrl(mainUrl, rawHref) ?: return null
        val clean = absolute.substringBefore('#')
        val slug = runCatching {
            URI(clean).path.trim('/').substringAfterLast('/')
        }.getOrNull().orEmpty()

        if (slug.isBlank()) return clean

        val seriesSlug = EPISODE_SLUG_SUFFIX.replace(slug, "")
        return if (seriesSlug != slug && seriesSlug.isNotBlank()) {
            "$mainUrl/$seriesSlug/"
        } else {
            clean
        }
    }

    private fun cleanCatalogTitle(raw: String): String {
        val title = raw.trim()
        if (title.isBlank()) return title

        val withoutEpisode = title.replace(
            Regex(
                """\s+(?:Episode|Ep|Eps)\s*\d+(?:\.\d+)?(?:\s*(?:Tamat|END))?.*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()

        val cleaned = withoutEpisode.replace(
            Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        return cleaned.ifBlank { title }
    }

    private fun parseItems(
        root: Element,
        selector: String
    ): List<SearchResponse> {
        return root.select(selector).mapNotNull { item ->
            val anchor = item.selectFirst(".bsx > a, a") ?: return@mapNotNull null
            val href = normalizeCatalogUrl(anchor.attr("href")) ?: return@mapNotNull null

            val poster = item.selectFirst(".limit img, img")?.let { image ->
                image.attr("data-src")
                    .ifBlank { image.attr("data-lazy-src") }
                    .ifBlank { image.attr("src") }
                    .ifBlank { null }
            }

            val type = item.selectFirst(".typez, .type")?.text()?.trim()
            val tvType = when {
                type?.contains("Movie", ignoreCase = true) == true -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val rawTitle = item.selectFirst(".tt")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2, h2, h3")?.text()?.trim()?.ifBlank { null }
                ?: anchor.attr("title").trim().ifBlank { null }
                ?: return@mapNotNull null

            val title = cleanCatalogTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null

            val epText = item.selectFirst(".bt .epx, .epx, .ep")?.text()?.trim()
            val epNum = Regex("""(\d+(?:\.\d+)?)""")
                .find(epText.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
                ?.toInt()

            newAnimeSearchResponse(title, href, tvType) {
                posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    private fun findHomeSection(
        doc: Document,
        vararg needles: String
    ): Element? {
        val heading = doc.select(".releases").firstOrNull { release ->
            val text = release.text()
            needles.any { text.contains(it, ignoreCase = true) }
        } ?: return null

        var sibling = heading.nextElementSibling()
        repeat(3) {
            if (sibling == null) return null
            if (sibling!!.hasClass("listupd") || sibling!!.select("article.bs").isNotEmpty()) {
                return sibling
            }
            sibling = sibling!!.nextElementSibling()
        }
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val doc = app.get(url).document

        val items = if (page == 1 && request.name == "Popular Today") {
            val section = findHomeSection(doc, "Terpopuler Hari Ini", "Popular")
            if (section != null) {
                parseItems(section, "article.bs")
            } else {
                parseItems(doc, ".releases.hothome + .listupd article.bs, .listupd.popular article.bs")
            }
        } else if (page == 1) {
            val section = findHomeSection(doc, "Rilisan Terbaru", "Latest")
            if (section != null) {
                parseItems(section, "article.bs")
            } else {
                parseItems(doc, ".releases.latesthome + .listupd article.bs")
            }
        } else {
            parseItems(doc, ".listupd.normal article.bs, main article.bs, article.bs")
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.replace(" ", "+")}"
        ).document

        return parseItems(
            doc,
            "div.listupd article.bs, main article.bs, article.bs"
        )
    }

    private fun episodeNumberFrom(
        href: String,
        text: String
    ): Double? {
        val fromText = Regex(
            """(?:Episode|Ep|Eps|E)?\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        if (fromText != null) return fromText

        return Regex(
            """-episode-(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun getEpisodesFromDocument(
        doc: Document,
        poster: String?
    ): MutableList<Episode> {
        var anchors = doc.select(
            ".eplister li a[href], " +
                ".eplister a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href], " +
                ".bixbox.bxcl.epcheck a[href]"
        )

        if (anchors.isEmpty()) {
            anchors = doc.select("a[href*='-episode-']")
        }

        val episodes = anchors.mapNotNull { anchor ->
            val href = absoluteUrl(mainUrl, anchor.attr("href")) ?: return@mapNotNull null
            if (!href.contains("-episode-", ignoreCase = true)) return@mapNotNull null

            val numberText = anchor.selectFirst(
                ".epl-num, .epnum, .episode-number, [data-num]"
            )?.text()?.trim().orEmpty()

            val titleText = anchor.selectFirst(
                ".epl-title, .episode-title, .title"
            )?.text()?.trim()?.ifBlank { null }
                ?: anchor.text().trim().ifBlank { null }
                ?: return@mapNotNull null

            val epNum = episodeNumberFrom(
                href,
                numberText.ifBlank { titleText }
            )

            newEpisode(href) {
                name = titleText
                episode = epNum?.toInt()
                posterUrl = poster
            }
        }

        return episodes
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name.orEmpty() }
            )
            .toMutableList()
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = normalizeCatalogUrl(url) ?: url
        val doc = app.get(animeUrl).document

        val title = doc.selectFirst("h1.entry-title, .infox h1, .infolimit h2")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore(" - Anichin")
                ?.trim()
                ?.ifBlank { null }
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".thumb img, .bigcontent .thumb img")?.let { image ->
            image.attr("data-src")
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("src") }
                .ifBlank { null }
        } ?: doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.ifBlank { null }

        val synopsis = doc.selectFirst(
            ".synp .entry-content, .entry-content[itemprop=description], .desc"
        )?.text()?.trim()?.ifBlank { null }
            ?: doc.select(".desc p, .synp p")
                .text()
                .trim()
                .ifBlank { null }

        val tags = doc.select(".genxed a, .genx a")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()

        val infoSpans = doc.select(".spe span, .info-content .spe span")

        val status = infoSpans.firstOrNull {
            it.text().contains("Status", ignoreCase = true)
        }?.text()?.substringAfter(":")?.trim()

        val typeText = infoSpans.firstOrNull {
            it.text().contains("Tipe", ignoreCase = true) ||
                it.text().contains("Type", ignoreCase = true)
        }?.text()?.substringAfter(":")?.trim()

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(doc.select(".spe, .info-content").text())
            ?.value
            ?.toIntOrNull()

        val episodes = getEpisodesFromDocument(doc, poster)
        val isMovie = typeText?.contains("Movie", ignoreCase = true) == true

        if (!isMovie) {
            return newAnimeLoadResponse(
                title,
                animeUrl,
                TvType.Anime
            ) {
                engName = title
                posterUrl = poster
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
                plot = synopsis
                this.tags = tags
                this.year = year

                showStatus = when {
                    status?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
                    status?.contains("Ongoing", ignoreCase = true) == true -> ShowStatus.Ongoing
                    else -> null
                }

                doc.selectFirst("[data-alid], [data-anilist]")?.let { element ->
                    val id = element.attr("data-alid")
                        .ifBlank { element.attr("data-anilist") }
                        .toIntOrNull()
                    if (id != null) addAniListId(id)
                }

                doc.selectFirst("[data-malid], [data-mal]")?.let { element ->
                    val id = element.attr("data-malid")
                        .ifBlank { element.attr("data-mal") }
                        .toIntOrNull()
                    if (id != null) addMalId(id)
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
        private val EPISODE_SLUG_SUFFIX = Regex(
            """-episode-\d+(?:\.\d+)?(?:-[^/]*)?$""",
            RegexOption.IGNORE_CASE
        )

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
