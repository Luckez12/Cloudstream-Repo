package com.msm21

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

class msm21 : MainAPI() {
    override var mainUrl = "https://movisubmalay.org"
    override var name = "MSM21 👾"
    override var lang = "ms"

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val loadLinksTimeoutMs = 90_000L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "movies" to "Filem Terkini",
        "tvshows" to "Siri TV Terkini",
        "group_movie/malaysia" to "Filem Malaysia",
        "group_movie/indonesia" to "Filem Indonesia",
        "group_movie/india" to "Filem India",
        "group_movie/japan" to "Filem Jepun",
        "group_movie/thailand" to "Filem Thailand",
        "group_movie/china" to "Filem China"
    )

    @Volatile
    private var mainUrlResolved = false

    private suspend fun loadMainUrlIfNeeded() {
        if (mainUrlResolved) return

        val candidate = mainUrl.removeSuffix("/")
        mainUrl = try {
            val response = app.get(candidate, timeout = 30L)
            getOrigin(response.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            candidate
        }
        mainUrlResolved = true
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        loadMainUrlIfNeeded()

        val path = request.data.trim('/')
        val pageUrl = if (page <= 1) {
            "$mainUrl/$path/"
        } else {
            "$mainUrl/$path/page/$page/"
        }

        val document = app.get(pageUrl, timeout = 50L).document
        val items = document.select("div.display-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            data = request,
            list = items,
            hasNext = document.hasNextPage(page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadMainUrlIfNeeded()

        val encoded = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query
        }

        return app.get("$mainUrl/?s=$encoded", timeout = 50L)
            .document
            .select("div.display-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "div.item-box > a[href], a.ml-mask[href], a[href]"
        ) ?: return null

        val href = rewriteToCurrentDomain(
            resolveUrl(mainUrl, anchor.attr("href"))
        )
        if (href.isBlank()) return null

        val rawTitle = cleanText(
            anchor.attr("title").ifBlank {
                selectFirst(".item-desc-title h3, .item-data h3, h3")
                    ?.text()
                    .orEmpty()
            }
        )
        if (rawTitle.isBlank()) return null

        val year = YEAR_AT_END.find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val title = rawTitle.replace(YEAR_AT_END, "")
            .trim()
            .ifBlank { rawTitle }

        val poster = selectFirst("img")
            ?.getImageAttr()
            ?.let(::fixUrlNull)
        val badge = cleanText(
            selectFirst(".item-quality, .data-quality")
                ?.text()
                .orEmpty()
        )
        val itemType = anchor.attr("data-ptype")
        val isSeries = itemType.contains("tv", ignoreCase = true) ||
            href.contains("/tvshows/", ignoreCase = true)

        return if (isSeries) {
            val episodeCount = EPISODE_BADGE.find(badge)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                episodes = episodeCount
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                if (badge.isNotBlank()) addQuality(badge)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()

        val pageUrl = rewriteToCurrentDomain(url)
        val document = app.get(
            pageUrl,
            headers = mapOf("Referer" to mainUrl),
            timeout = 50L
        ).document

        val title = cleanText(
            document.selectFirst(".details-title h3")
                ?.text()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    .orEmpty()
        )
        if (title.isBlank()) {
            throw ErrorLoadingException("MSM21: tajuk tidak dijumpai")
        }

        val poster = document.selectFirst(".content-poster img.poster-img")
            ?.getImageAttr()
            ?.let(::fixUrlNull)
            ?: fixUrlNull(
                document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
            )
        val plot = cleanText(
            document.selectFirst(".details-desc p")
                ?.text()
                ?.substringBefore("Original title:")
                .orEmpty()
        ).ifBlank { null }

        val genres = document.select(".details-genre a")
            .map { cleanText(it.text()) }
            .filter { it.isNotBlank() }
        val actors = document.findInfoRow("Stars")
            ?.select("a")
            ?.map { cleanText(it.text()) }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val year = document.findInfoRow("Year")
            ?.selectFirst("a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
        val duration = document.selectFirst("[itemprop=duration]")
            ?.text()
            ?.let { DIGITS.find(it)?.value?.toIntOrNull() }
        val rating = document.selectFirst(".data-imdb")
            ?.text()
            ?.let { RATING.find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()
        val trailer = document.selectFirst(".btn-trailer[data-tid]")
            ?.attr("data-tid")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=$it" }

        val recommendations = document
            .select(".similar-module .module-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val episodeLists = document.select("ul.episodes-list")
        val isSeries = pageUrl.contains("/tvshows/", ignoreCase = true) ||
            episodeLists.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeLists.flatMap { list ->
                val season = list.id()
                    .substringAfterLast('-', "")
                    .toIntOrNull()

                list.select("li a[href]").mapNotNull { episodeElement ->
                    val episodeUrl = rewriteToCurrentDomain(
                        resolveUrl(pageUrl, episodeElement.attr("href"))
                    )
                    if (episodeUrl.isBlank()) return@mapNotNull null

                    val episodeNumber = episodeElement
                        .selectFirst(".ep-num")
                        ?.text()
                        ?.let { DIGITS.find(it)?.value?.toIntOrNull() }
                    val episodeName = cleanText(
                        episodeElement.selectFirst(".ep-title")
                            ?.text()
                            .orEmpty()
                    ).ifBlank {
                        episodeNumber?.let { "Episod $it" } ?: "Episod"
                    }
                    val episodePoster = episodeElement.selectFirst("img")
                        ?.getImageAttr()
                        ?.let(::fixUrlNull)
                        ?: poster

                    newEpisode(episodeUrl) {
                        this.name = episodeName
                        this.season = season
                        this.episode = episodeNumber
                        posterUrl = episodePoster
                    }
                }
            }.sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )

            newTvSeriesLoadResponse(
                title,
                pageUrl,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                tags = genres
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
                if (actors.isNotEmpty()) addActors(actors)
                if (trailer != null) addTrailer(trailer)
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                tags = genres
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
                if (actors.isNotEmpty()) addActors(actors)
                if (trailer != null) addTrailer(trailer)
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadMainUrlIfNeeded()

        val pageUrl = rewriteToCurrentDomain(data)
        val response = app.get(
            pageUrl,
            headers = mapOf("Referer" to mainUrl),
            timeout = 50L
        )
        Log.i(TAG, "MSM21_LOADLINKS page=${urlForLog(pageUrl)} code=${response.code}")

        val options = response.document
            .select(
                "li.zetaflix_player_option[data-post][data-nume][data-type], " +
                    "li[data-post][data-nume][data-type], " +
                    "[class*=player_option][data-post][data-nume][data-type], " +
                    "[data-post][data-nume][data-type]"
            )
            .mapNotNull { element ->
                val nume = element.attr("data-nume").trim()
                if (nume.isBlank() || nume.equals("fake", true)) {
                    return@mapNotNull null
                }

                val post = element.attr("data-post").trim()
                val type = element.attr("data-type").trim()
                if (post.isBlank() || type.isBlank()) return@mapNotNull null

                val fullOptionLabel = cleanText(element.text())
                val titleOnlyLabel = cleanText(
                    element.selectFirst(".opt-titl")?.text()
                        ?: element.selectFirst(".opt-name")?.text()
                        .orEmpty()
                )

                PlayerOption(
                    post = post,
                    nume = nume,
                    type = type,
                    // Current MSM pages expose the server slug separately from
                    // the visible MalaySub title. Using only .opt-titl loses
                    // values such as abyss, rpmpl, upns, byses and mixdr.
                    label = fullOptionLabel
                        .ifBlank { titleOnlyLabel }
                        .ifBlank { "Server $nume" }
                )
            }

        Log.i(
            TAG,
            "MSM21_OPTIONS count=${options.size} " +
                options.joinToString(" || ") { option ->
                    "${option.label}[nume=${option.nume},type=${option.type}]"
                }
        )

        val emittedUrls = ConcurrentHashMap.newKeySet<String>()

        if (options.isEmpty()) {
            val staticMirrors = collectStaticMirrors(
                response.document,
                pageUrl
            ).distinctBy { it.url }
            if (staticMirrors.isEmpty()) return false

            val standard = loadStandardMirrors(
                mirrors = staticMirrors,
                pageUrl = pageUrl,
                subtitleCallback = subtitleCallback,
                callback = callback,
                emittedUrls = emittedUrls
            )

            val webViewFound = probeWithWebView(
                mirrors = standard.unresolved,
                pageUrl = pageUrl,
                callback = callback,
                emittedUrls = emittedUrls,
                maxMirrors = MAX_WEBVIEW_MIRRORS
            )

            return standard.foundStream || webViewFound
        }

        // Each enabled option retains its own pipeline. Successful native mirrors
        // do not suppress unresolved JavaScript mirrors.
        val uniqueOptions = options.distinctBy { it.optionKey() }
        val ajaxSemaphore = Semaphore(AJAX_BATCH_SIZE)
        val nativeSemaphore = Semaphore(NATIVE_CONCURRENCY)
        val webViewSemaphore = Semaphore(WEBVIEW_CONCURRENCY)
        val foundStream = coroutineScope {
            uniqueOptions.map { option ->
                async {
                    val mirrors = ajaxSemaphore.withPermit {
                        fetchMirror(option, pageUrl).distinctBy { it.url }
                    }
                    val standard = nativeSemaphore.withPermit {
                        loadStandardMirrors(mirrors, pageUrl, subtitleCallback, callback, emittedUrls)
                    }
                    val webViewFound = probeWithWebView(
                        mirrors = standard.unresolved,
                        pageUrl = pageUrl,
                        callback = callback,
                        emittedUrls = emittedUrls,
                        maxMirrors = Int.MAX_VALUE,
                        semaphore = webViewSemaphore
                    )
                    Log.i(TAG, "MSM21_V12_OPTION_DONE label=${option.label} mirrors=${mirrors.size} " +
                        "success=${standard.foundStream || webViewFound}")
                    standard.foundStream || webViewFound
                }
            }.awaitAll().any { it }
        }

        if (!foundStream) {
            invalidateMirrorCache(pageUrl)
        }
        return foundStream
    }

    private suspend fun loadStandardMirrors(
        mirrors: List<EmbedMirror>,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emittedUrls: MutableSet<String>
    ): ExtractionBatchResult = coroutineScope {
        val foundStream = AtomicBoolean(false)
        val unresolved = mirrors.distinctBy { it.url }.map { mirror ->
            async {
                val candidates = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                var apiSelected = emptyList<ExtractorLink>()
                val collect: (ExtractorLink) -> Unit = { link ->
                    if (MsmMediaPolicy.isRejected(link.url)) {
                        Log.w(TAG, "MSM21_V12_REJECT label=${mirror.label} target=${urlForLog(link.url)}")
                    } else if (link.url.isNotBlank()) {
                        candidates.add(link)
                    }
                }
                try {
                    withTimeoutOrNull(MIRROR_PIPELINE_TIMEOUT_MS) {
                        Log.i(TAG, "MSM21_V23_ROUTE host=${runCatching { URI(mirror.url).host }.getOrNull()} abyss=${MsmAbyssApi.supports(mirror.url)}")
                        val apiLinks = MsmPlayerApi.extract(mirror.url, pageUrl)
                        if (apiLinks.isNotEmpty()) apiSelected = MsmMediaPolicy.select(apiLinks, mirror.label)
                        if (apiSelected.isEmpty() && MsmAbyssApi.supports(mirror.url)) {
                            // Use the existing standard extractor budget, not an Abyss extension.
                            val abyssSelected = withTimeoutOrNull(STANDARD_EXTRACTOR_TIMEOUT_MS) {
                                MsmMediaPolicy.select(MsmAbyssApi.extract(mirror.url, mirror.label), mirror.label,
                                    requireVerified = true).take(1)
                            }
                            apiSelected = abyssSelected.orEmpty()
                            Log.i(TAG, "MSM21_V24_ABYSS_RESULT label=${mirror.label} emitted=${apiSelected.size} " +
                                "reason=${if (abyssSelected == null) "standard_budget_exhausted" else if (apiSelected.isEmpty()) "no_verified_source" else "verified_source"}")
                        }
                        val native = if (apiSelected.isEmpty())
                            MsmNativeHlsDiscovery.discover(mirror.url, pageUrl) else null
                        if (native != null) {
                            native.masters.forEach(collect)
                            native.media.forEach(collect)
                        }
                        if (apiSelected.isEmpty() && native?.masters?.isEmpty() != false) {
                            withTimeoutOrNull(STANDARD_EXTRACTOR_TIMEOUT_MS) {
                                loadExtractor(mirror.url, pageUrl, subtitleCallback, collect)
                            }
                            if (candidates.isEmpty()) {
                                val finalUrl = followRedirect(mirror.url, maxHops = 4).ifBlank { mirror.url }
                                if (finalUrl != mirror.url) {
                                    withTimeoutOrNull(STANDARD_EXTRACTOR_TIMEOUT_MS) {
                                        loadExtractor(finalUrl, pageUrl, subtitleCallback, collect)
                                    }
                                }
                                if (candidates.isEmpty()) {
                                    val nested = findNestedEmbed(finalUrl, pageUrl)
                                    if (!nested.isNullOrBlank() && nested != finalUrl) {
                                        val nestedFinal = followRedirect(nested, maxHops = 3).ifBlank { nested }
                                        withTimeoutOrNull(STANDARD_EXTRACTOR_TIMEOUT_MS) {
                                            loadExtractor(nestedFinal, finalUrl, subtitleCallback, collect)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.w(TAG, "MSM21_V12_EXTRACT_FAILED label=${mirror.label} error=${error.javaClass.simpleName}")
                }
                val selected = apiSelected.ifEmpty { MsmMediaPolicy.select(candidates.toList(), mirror.label) }
                selected.forEach { link ->
                    if (emittedUrls.add("${mirror.label}\u0000${link.url}")) callback(link)
                }
                if (selected.isNotEmpty()) foundStream.set(true)
                mirror.takeIf { selected.isEmpty() }
            }
        }.awaitAll().filterNotNull()
        ExtractionBatchResult(foundStream.get(), unresolved)
    }

    private suspend fun probeWithWebView(
        mirrors: List<EmbedMirror>,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
        emittedUrls: MutableSet<String>,
        maxMirrors: Int,
        semaphore: Semaphore = Semaphore(WEBVIEW_CONCURRENCY)
    ): Boolean = coroutineScope {
        val candidates = mirrors
            .distinctBy { it.url }
            .take(maxMirrors)

        if (candidates.isEmpty()) return@coroutineScope false

        // Bound live WebViews while trying every unresolved mirror. Each completed
        // mirror publishes its result without waiting for the rest of this batch.
        Log.i(TAG, "MSM21_WEBVIEW_BATCH count=${candidates.size} labels=${candidates.joinToString { it.label }}")
        val foundAny = AtomicBoolean(false)
        candidates.map { mirror ->
            async {
                Log.i(TAG, "MSM21_WEBVIEW_START label=${mirror.label} mirror=${urlForLog(mirror.url)}")
                val streams = try {
                    semaphore.withPermit {
                        MsmWebViewProbe.extractFast(url = mirror.url, referer = pageUrl)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.w(TAG, "MSM21_V12_WEBVIEW_FAILED label=${mirror.label} error=${error.javaClass.simpleName}")
                    emptyList()
                }
                val links = streams.filterNot { MsmMediaPolicy.isRejected(it.url) }.map { stream ->
                    val headers = stream.headers.filterKeys { it.lowercase() !in BLOCKED_VIDEO_HEADERS }
                        .toMutableMap().apply {
                            put("User-Agent", get("User-Agent") ?: USER_AGENT)
                            put("Accept", get("Accept") ?: "*/*")
                            put("Referer", get("Referer") ?: mirror.url)
                        }
                    val mime = stream.mimeType.orEmpty().lowercase()
                    val path = runCatching { URI(stream.url).path }.getOrNull().orEmpty()
                    val type = when {
                        path.endsWith(".m3u8", true) || mime.contains("mpegurl") -> ExtractorLinkType.M3U8
                        path.endsWith(".mpd", true) || mime.contains("dash+xml") -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    newExtractorLink(source = mirror.label, name = stream.label,
                        url = stream.url, type = type) {
                        referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }
                            ?.value.orEmpty().ifBlank { mirror.url }
                        quality = getQualityFromName(stream.label)
                        this.headers = headers
                    }
                }
                val selected = MsmMediaPolicy.select(links, mirror.label, requireVerified = true)
                selected.forEach { link ->
                    if (emittedUrls.add("${mirror.label}\u0000${link.url}")) callback(link)
                }
                if (selected.isNotEmpty()) foundAny.set(true)
                Log.i(TAG, "MSM21_WEBVIEW_DONE label=${mirror.label} captures=${streams.size} emitted=${selected.size}")
            }
        }.awaitAll()
        foundAny.get()
    }

    private suspend fun fetchMirror(
        option: PlayerOption,
        pageUrl: String
    ): List<EmbedMirror> {
        val cacheKey = mirrorCacheKey(option, pageUrl)
        getCachedMirrors(cacheKey)?.let { return it }

        return try {
            Log.i(TAG, "MSM21_AJAX_START label=${option.label} nume=${option.nume} type=${option.type}")
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "Referer" to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                ),
                data = mapOf(
                    "action" to "zeta_player_ajax",
                    "post" to option.post,
                    "nume" to option.nume,
                    "type" to option.type
                ),
                timeout = 10L
            )
            Log.i(TAG, "MSM21_AJAX_HTTP label=${option.label} code=${response.code} bytes=${response.text.length}")

            val payload = tryParseJson<ZetaPlayerResponse>(response.text)
            val found = linkedSetOf<String>()

            extractEmbedUrls(
                payload?.embedUrl.orEmpty(),
                pageUrl
            ).forEach(found::add)

            // Some Zeta/player versions return raw HTML or a slightly different
            // JSON envelope. Scan the full response as a fallback instead of
            // treating it as a dead server.
            if (found.isEmpty()) {
                extractEmbedUrls(response.text, pageUrl)
                    .forEach(found::add)
            }

            val mirrors = found.map { EmbedMirror(it, option.label) }
            Log.i(
                TAG,
                "MSM21_AJAX_RESULT label=${option.label} mirrors=${mirrors.size} " +
                    mirrors.joinToString { urlForLog(it.url) }
            )
            if (mirrors.isNotEmpty()) cacheMirrors(cacheKey, mirrors)
            mirrors
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "MSM21_AJAX_ERROR label=${option.label} error=${error.javaClass.simpleName}:${error.message}")
            emptyList()
        }
    }

    private fun mirrorCacheKey(
        option: PlayerOption,
        pageUrl: String
    ): String = listOf(
        pageUrl,
        option.post,
        option.nume,
        option.type
    ).joinToString("|")

    private fun getCachedMirrors(key: String): List<EmbedMirror>? {
        val cached = MIRROR_CACHE[key] ?: return null
        if (cached.expiresAt > System.currentTimeMillis()) {
            return cached.mirrors
        }

        MIRROR_CACHE.remove(key, cached)
        return null
    }

    private fun cacheMirrors(
        key: String,
        mirrors: List<EmbedMirror>
    ) {
        val now = System.currentTimeMillis()

        if (MIRROR_CACHE.size >= MAX_MIRROR_CACHE_ENTRIES) {
            MIRROR_CACHE.entries
                .filter { it.value.expiresAt <= now }
                .forEach { MIRROR_CACHE.remove(it.key, it.value) }
        }
        if (MIRROR_CACHE.size >= MAX_MIRROR_CACHE_ENTRIES) {
            MIRROR_CACHE.clear()
        }

        MIRROR_CACHE[key] = CachedMirrors(
            expiresAt = now + MIRROR_CACHE_TTL_MS,
            mirrors = mirrors
        )
    }

    private fun invalidateMirrorCache(pageUrl: String) {
        val prefix = "$pageUrl|"
        MIRROR_CACHE.keys
            .filter { it.startsWith(prefix) }
            .forEach(MIRROR_CACHE::remove)
    }

    private fun collectStaticMirrors(
        document: Document,
        pageUrl: String
    ): List<EmbedMirror> {
        return document.select(
            ".player-display iframe[src], .player-display iframe[data-src], " +
                "iframe.metaframe[src], iframe.metaframe[data-src], " +
                "video[src], video source[src]"
        ).mapNotNull { element ->
            val raw = element.attr("data-src")
                .ifBlank { element.attr("src") }
            normaliseEmbedUrl(raw, pageUrl)
                ?.let { EmbedMirror(it, "MSM21") }
        }
    }

    private fun extractEmbedUrls(
        embedHtml: String,
        pageUrl: String
    ): List<String> {
        val html = embedHtml.trim()
        if (html.isBlank()) return emptyList()

        val found = linkedSetOf<String>()
        fun add(raw: String) {
            normaliseEmbedUrl(raw, pageUrl)?.let(found::add)
        }

        if (html.startsWith("http://", true) ||
            html.startsWith("https://", true)
        ) {
            add(html)
        }

        Jsoup.parse(html, pageUrl)
            .select("iframe[src], iframe[data-src], video[src], source[src]")
            .forEach { element ->
                add(
                    element.attr("data-src")
                        .ifBlank { element.attr("src") }
                )
            }

        if (found.isEmpty()) {
            URL_IN_HTML.findAll(html.replace("\\/", "/"))
                .forEach { add(it.value) }
        }

        return found.toList()
    }

    private suspend fun findNestedEmbed(
        url: String,
        referer: String
    ): String? {
        return try {
            val response = app.get(
                url,
                headers = mapOf("Referer" to referer),
                timeout = 15L
            )

            val candidate = response.document.selectFirst(
                "iframe[data-src], iframe[src], " +
                    "[data-video], [data-url], [data-embed], [data-link]"
            )?.let { element ->
                listOf(
                    element.attr("data-src"),
                    element.attr("src"),
                    element.attr("data-video"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-link")
                ).firstOrNull { it.isNotBlank() }
            }

            val direct = candidate
                ?.let { normaliseEmbedUrl(it, url) }
            if (!direct.isNullOrBlank() && direct != url) {
                direct
            } else {
                extractEmbedUrls(response.text, url)
                    .firstOrNull { it != url }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun followRedirect(
        url: String,
        maxHops: Int = 4
    ): String {
        var current = url.trim()
        if (current.isBlank()) return current

        repeat(maxHops) {
            val next = try {
                val response = app.get(
                    current,
                    allowRedirects = false,
                    timeout = 10L
                )
                val location = response.headers["Location"]
                    ?: response.headers["location"]

                when {
                    !location.isNullOrBlank() -> resolveUrl(current, location)
                    else -> {
                        val metaRefresh = response.document
                            .selectFirst("meta[http-equiv~=(?i)refresh]")
                            ?.attr("content")
                            ?.let(::extractMetaRefreshUrl)

                        if (!metaRefresh.isNullOrBlank()) {
                            resolveUrl(current, metaRefresh)
                        } else {
                            extractJavascriptRedirect(response.text)
                                ?.let { resolveUrl(current, it) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            if (next.isNullOrBlank() || next == current) {
                return current
            }
            current = next
        }

        return current
    }

    private fun extractMetaRefreshUrl(content: String): String? {
        return Regex(
            """(?i)url\s*=\s*['"]?([^'";]+)"""
        ).find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun extractJavascriptRedirect(html: String): String? {
        val patterns = listOf(
            Regex(
                """(?i)window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""
            ),
            Regex(
                """(?i)location\.href\s*=\s*['"]([^'"]+)['"]"""
            ),
            Regex(
                """(?i)location\.replace\(\s*['"]([^'"]+)['"]\s*\)"""
            )
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
        }
    }

    private fun normaliseEmbedUrl(
        raw: String,
        baseUrl: String
    ): String? {
        val cleaned = raw.trim()
            .trim('"', '\'', ' ')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")

        if (cleaned.isBlank() || cleaned.startsWith("#")) return null
        if (cleaned.startsWith("javascript:", true)) return null

        val resolved = resolveUrl(baseUrl, cleaned)
        if (!resolved.startsWith("http://", true) &&
            !resolved.startsWith("https://", true)
        ) return null

        val lower = resolved.lowercase()
        if (BLOCKED_EMBED_PARTS.any(lower::contains)) return null
        return resolved
    }

    private fun PlayerOption.optionKey(): String {
        return "$post\u0000$nume\u0000$type"
    }

    private fun Document.hasNextPage(currentPage: Int): Boolean {
        val totalPages = selectFirst(".pagination .total")
            ?.text()
            ?.let { PAGE_TOTAL.find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()

        if (totalPages != null) return currentPage < totalPages
        return selectFirst(".pagination a .fa-chevron-right") != null ||
            selectFirst(".pagination a.arrow_pag") != null
    }

    private fun Document.findInfoRow(label: String): Element? {
        return select(".details-info p").firstOrNull { row ->
            row.selectFirst("strong")
                ?.text()
                ?.trim()
                ?.trimEnd(':')
                ?.equals(label, ignoreCase = true) == true
        }
    }

    private fun rewriteToCurrentDomain(url: String): String {
        val target = url.trim()
        if (target.isBlank()) return target

        return try {
            val targetUri = URI(target)
            val currentUri = URI(mainUrl)
            val targetHost = targetUri.host ?: return target
            val isOldSiteHost = targetHost.contains("pencurimovie", true) ||
                targetHost.contains("movisubmalay", true)

            if (!isOldSiteHost || currentUri.host.isNullOrBlank()) {
                target
            } else {
                URI(
                    currentUri.scheme ?: targetUri.scheme,
                    targetUri.userInfo,
                    currentUri.host,
                    currentUri.port,
                    targetUri.path,
                    targetUri.query,
                    targetUri.fragment
                ).toString()
            }
        } catch (_: Exception) {
            target
        }
    }

    private fun resolveUrl(baseUrl: String, value: String): String {
        val target = value.trim()
        if (target.isBlank()) return ""
        if (target.startsWith("//")) return "https:$target"

        return try {
            URI(baseUrl).resolve(target).toString()
        } catch (_: Exception) {
            target
        }
    }

    private fun getOrigin(url: String): String {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return url.removeSuffix("/")
            val host = uri.host ?: return url.removeSuffix("/")
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port"
        } catch (_: Exception) {
            url.removeSuffix("/")
        }
    }

    private fun Element.getImageAttr(): String {
        return listOf(
            attr("data-original"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("src")
        ).firstOrNull { value ->
            value.isNotBlank() && !value.startsWith("data:image", true)
        }.orEmpty()
    }

    private fun urlForLog(value: String): String {
        return try {
            val uri = URI(value)
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty().take(90)
            if (host.isBlank()) value.substringBefore('?').take(120) else "$host$path"
        } catch (_: Exception) {
            value.substringBefore('?').take(120)
        }
    }

    private fun cleanText(value: String): String {
        return value
            .replace(INVISIBLE_CHARS, "")
            .replace(WHITESPACE, " ")
            .trim()
    }

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val label: String
    )

    private data class EmbedMirror(
        val url: String,
        val label: String
    )

    private data class ExtractionBatchResult(
        val foundStream: Boolean,
        val unresolved: List<EmbedMirror>
    )

    private data class CachedMirrors(
        val expiresAt: Long,
        val mirrors: List<EmbedMirror>
    )

    private data class ZetaPlayerResponse(
        @param:JsonProperty("embed_url") val embedUrl: String? = null
    )

    companion object {
        private const val TAG = "MSM21"
        private const val AJAX_BATCH_SIZE = 8
        private const val MAX_WEBVIEW_MIRRORS = Int.MAX_VALUE
        private const val NATIVE_CONCURRENCY = 4
        private const val WEBVIEW_CONCURRENCY = 2
        private const val STANDARD_EXTRACTOR_TIMEOUT_MS = 5_000L
        private const val MIRROR_PIPELINE_TIMEOUT_MS = 18_000L
        private const val MIRROR_CACHE_TTL_MS = 90_000L
        private const val MAX_MIRROR_CACHE_ENTRIES = 80

        private val MIRROR_CACHE = ConcurrentHashMap<String, CachedMirrors>()

        private val YEAR_AT_END = Regex("\\s*\\(((?:19|20)\\d{2})\\)\\s*$")
        private val EPISODE_BADGE = Regex("(?i)EP\\s*(\\d+)")
        private val PAGE_TOTAL = Regex("(?i)of\\s+(\\d+)")
        private val DIGITS = Regex("\\d+")
        private val RATING = Regex("(?i)IMDb\\s*:\\s*(\\d+(?:\\.\\d+)?)")
        private val URL_IN_HTML = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")
        private val INVISIBLE_CHARS = Regex("[\\p{Cf}\\p{Cc}]")

        private val BLOCKED_EMBED_PARTS = listOf(
            "youtube.com",
            "youtu.be",
            "googlesyndication",
            "googletagmanager",
            "doubleclick.net",
            "google-analytics",
            "facebook.com",
            "telegram.me",
            "t.me/",
            "algiersreests",
            "morestamping",
            "decafeligiblyhad"
        )

        private val BLOCKED_VIDEO_HEADERS = setOf(
            "host",
            "connection",
            "accept-encoding",
            "range"
        )
    }
}
