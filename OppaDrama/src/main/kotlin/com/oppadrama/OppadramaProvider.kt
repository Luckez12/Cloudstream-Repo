package com.oppadrama

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OppadramaProvider : MainAPI() {

    override var mainUrl = DEFAULT_SITE_URL
    override var name = "OppaDrama 👾"
    override var lang = "id"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private var humanCookie: String? = null

    private data class ServerMirror(
        val label: String,
        val url: String
    )


    private fun browserHeaders(baseUrl: String = mainUrl): Map<String, String> {
        val base = baseUrl.trimEnd('/')
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            "Referer" to "$base/",
            "Origin" to base
        )
    }

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?status=&type=Drama&order=update" to "Drama",
        "series/?type=Movie&order=update" to "Movie",
        "series/?country%5B%5D=south-korea&type=Drama&order=update" to "Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "China",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Japan",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Thailand"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getSiteDocument(
            buildMainPageUrl(
                page,
                request.data
            )
        )

        val items = document
            .select("div.listupd article.bs, div.listupd article.stylefor")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            HomePageList(
                request.name,
                items
            ),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = getSiteDocument("$mainUrl/?s=$encoded")

        return document
            .select("div.listupd article.bs, div.listupd article.stylefor")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getSiteDocument(url)

        val title = document.titleText()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.poster()
        val plot = document.descriptionText()
        val tags = document.tagsList()

        val year = document.getInfo("Tahun")?.safeYear()
            ?: document.getInfo("Year")?.safeYear()
            ?: document.selectFirst(".year")?.text()?.safeYear()

        val status = parseStatus(
            document.getInfo("Status")
                ?: document.text()
        )

        val episodes = document.episodes()

        val isMovie = url.contains("/movie-", true) ||
            document.getInfo("Tipe")?.contains("Movie", true) == true ||
            document.getInfo("Type")?.contains("Movie", true) == true ||
            episodes.isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                plot?.let { this.plot = it }
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                plot?.let { this.plot = it }
                status?.let { showStatus = it }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSiteDocument(data)

        val serverLinks = linkedMapOf<String, ServerMirror>()
        fun addServer(
            label: String,
            rawUrl: String?
        ) {
            val fixedUrl = rawUrl
                ?.toAbsoluteUrl(data)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return

            val cleanLabel = label
                .replace("\\s+".toRegex(), " ")
                .trim()
                .ifBlank { "Server" }
            if (!isRealStreamServer(cleanLabel, fixedUrl)) {
                Log.i(TAG, "OPPA_SKIP_SERVER = $cleanLabel | $fixedUrl")
                return
            }

            if (!serverLinks.containsKey(fixedUrl)) {
                serverLinks[fixedUrl] = ServerMirror(
                    label = cleanLabel,
                    url = fixedUrl
                )
            }
        }

        document.select("#pembed iframe, div.player-embed iframe")
            .forEach { iframe ->
                addServer(
                    label = "Default",
                    rawUrl = iframe.getIframeUrl()
                )
            }

        document.select("select.mirror option[data-index], select.mirror option[value]")
            .forEach { option ->
                val optionValue = option.attr("value").trim()
                if (optionValue.isBlank()) return@forEach

                val label = option.text()
                    .replace("\\s+".toRegex(), " ")
                    .trim()
                    .ifBlank {
                        "Server ${option.attr("data-index").ifBlank { "?" }}"
                    }

                decodeMirror(
                    value = optionValue,
                    label = label,
                    referer = data
                ).forEach { link ->
                    addServer(
                        label = label,
                        rawUrl = link
                    )
                }
            }

        val sortedServers = serverLinks.values
            .distinctBy { it.url }
            .sortedWith(
                compareBy<ServerMirror> { it.url.priorityScore() }
                    .thenBy { it.label.lowercase() }
            )

        sortedServers.forEachIndexed { index, mirror ->
            Log.i(
                TAG,
                "OPPA_REAL_SERVER[$index] = ${mirror.label} | ${mirror.url}"
            )
        }

        if (sortedServers.isEmpty()) {
            Log.i(TAG, "OPPA_NO_REAL_SERVER = $data")
            return false
        }

        /*
         * Fast race strategy:
         * 1. Standard extractors start concurrently instead of waiting serially.
         * 2. Hydrax/Abyss runs in one dedicated job to avoid multiple WebViews.
         * 3. The first playable link opens a short collection window so other
         *    fast mirrors can still appear, then remaining slow work is cancelled.
         */
        return supervisorScope {
            val foundLinks = AtomicBoolean(false)
            val raceResult = CompletableDeferred<Boolean>()

            val standardServers = sortedServers.filterNot { it.isHydraxMirror() }
            val hydraxServers = sortedServers.filter { it.isHydraxMirror() }
            val totalJobs = standardServers.size + if (hydraxServers.isNotEmpty()) 1 else 0
            val remainingJobs = AtomicInteger(totalJobs)

            val fastCallback: (ExtractorLink) -> Unit = { link ->
                foundLinks.set(true)
                raceResult.complete(true)
                callback(link)
            }

            fun jobFinished() {
                if (remainingJobs.decrementAndGet() == 0) {
                    raceResult.complete(false)
                }
            }

            val jobs = buildList {
                standardServers.forEach { mirror ->
                    add(
                        launch {
                            try {
                                resolveStandardMirror(
                                    mirror = mirror,
                                    data = data,
                                    subtitleCallback = subtitleCallback,
                                    callback = fastCallback
                                )
                            } finally {
                                jobFinished()
                            }
                        }
                    )
                }

                if (hydraxServers.isNotEmpty()) {
                    add(
                        launch {
                            try {
                                for (mirror in hydraxServers) {
                                    val loaded = resolveHydraxMirror(
                                        mirror = mirror,
                                        data = data,
                                        subtitleCallback = subtitleCallback,
                                        callback = fastCallback
                                    )
                                    if (loaded) break
                                }
                            } finally {
                                jobFinished()
                            }
                        }
                    )
                }
            }

            val gotFirstLink = withTimeoutOrNull(SERVER_RACE_TIMEOUT_MS) {
                raceResult.await()
            } ?: false

            if (gotFirstLink) {
                // A playable source exists. Give other fast mirrors a short chance,
                // then stop expensive work such as a lingering WebView probe.
                delay(COLLECT_AFTER_FIRST_LINK_MS)
                jobs.forEach { job ->
                    if (job.isActive) job.cancel()
                }
                jobs.joinAll()
            } else {
                /*
                 * Reliability phase. Do NOT cancel the race just because no server
                 * answered during the fast window. Some CloudStream extractors can
                 * legitimately take longer on a cold DNS/TLS path. Let the existing
                 * jobs finish for a bounded compatibility window first.
                 */
                Log.i(TAG, "OPPA_FAST_RACE_SLOW_FALLBACK = servers=${sortedServers.size}")
                withTimeoutOrNull(SLOW_FALLBACK_WAIT_MS) {
                    jobs.joinAll()
                }

                jobs.forEach { job ->
                    if (job.isActive) job.cancel()
                }
                jobs.joinAll()
            }

            Log.i(
                TAG,
                "OPPA_FAST_RACE_DONE = first=$gotFirstLink | found=${foundLinks.get()} | " +
                    "servers=${sortedServers.size}"
            )

            foundLinks.get()
        }
    }

    private suspend fun resolveStandardMirror(
        mirror: ServerMirror,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.i(TAG, "OPPA_FAST_EXTRACTOR = ${mirror.label} | ${mirror.url}")
            loadExtractor(
                mirror.url,
                data,
                subtitleCallback,
                callback
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "OPPA_EXTRACTOR_FAILED = ${mirror.label} | ${mirror.url} | ${error.message}"
            )
            false
        }
    }

    private suspend fun resolveHydraxMirror(
        mirror: ServerMirror,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val streams = AbyssWebViewProbe.extractFast(
                url = mirror.url,
                referer = data
            )

            streams.forEach { stream ->
                val streamHeaders = stream.headers
                    .toMutableMap()
                    .cleanVideoHeaders()
                    .apply {
                        put("User-Agent", get("User-Agent") ?: USER_AGENT)
                        put("Accept", get("Accept") ?: "*/*")
                        put("Referer", get("Referer") ?: mirror.url)
                    }

                Log.i(
                    TAG,
                    "OPPA_HYDRAX_LINK = ${stream.label} | ${stream.url} | " +
                        streamHeaders.keys.joinToString(",")
                )

                callback(
                    newExtractorLink(
                        source = "Hydrax",
                        name = "Hydrax ${stream.label}",
                        url = stream.url
                    ) {
                        this.referer = mirror.url
                        this.quality = getQualityFromName(stream.label)
                        this.headers = streamHeaders
                    }
                )
            }

            if (streams.isNotEmpty()) {
                return true
            }

            Log.i(TAG, "OPPA_HYDRAX_EMPTY = ${mirror.url}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "OPPA_HYDRAX_FAILED = ${mirror.label} | ${mirror.url} | ${error.message}"
            )
        }

        // Compatibility fallback for Hydrax mirrors supported by CloudStream itself.
        return resolveStandardMirror(
            mirror = mirror,
            data = data,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private fun ServerMirror.isHydraxMirror(): Boolean {
        val value = "${label.lowercase()} ${url.lowercase()}"
        return value.contains("abyssplayer") ||
            value.contains("abyss.to") ||
            value.contains("hydrax")
    }

    private fun buildMainPageUrl(page: Int, data: String): String {
        return if (data.isBlank()) {
            if (page == 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }
        } else {
            val base = "$mainUrl/$data"

            if (page <= 1) {
                base
            } else {
                val separator = if (base.contains("?")) "&" else "?"
                "${base}${separator}page=$page"
            }
        }
    }

    private suspend fun getSiteDocument(url: String): Document {
        /*
         * Optimistic fast path: use the current known OppaDrama address directly.
         * If it has moved, resolve oppa.biz once and retry transparently.
         */
        return try {
            ensureHumanCookie()
            app.get(
                normalizeSiteUrl(url),
                headers = verifiedHeaders(),
                referer = mainUrl
            ).document
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstError: Throwable) {
            Log.w(
                TAG,
                "OPPA_SITE_FAST_PATH_FAILED = $mainUrl | ${firstError.message}"
            )

            refreshAddress()
            humanCookie = null
            ensureHumanCookie()

            app.get(
                normalizeSiteUrl(url),
                headers = verifiedHeaders(),
                referer = mainUrl
            ).document
        }
    }

    private suspend fun refreshAddress() {
        val response = app.get(
            "https://oppa.biz",
            headers = browserHeaders("https://oppa.biz"),
            allowRedirects = false
        )

        val location = response.headers["Location"]
            ?: response.headers["location"]

        val resolved = when {
            !location.isNullOrBlank() && location.startsWith("http", true) ->
                location.trimEnd('/')

            else -> Regex("""https?://(?:\d{1,3}\.){3}\d{1,3}""")
                .find(response.text)
                ?.value
                ?.trimEnd('/')
        }

        if (!resolved.isNullOrBlank() && !resolved.equals(mainUrl, true)) {
            Log.i(TAG, "OPPA_ADDRESS_REFRESH = $mainUrl -> $resolved")
            mainUrl = resolved
        }
    }

    private suspend fun ensureHumanCookie() {
        if (!humanCookie.isNullOrBlank()) return

        val response = app.get(
            "$mainUrl/?verify_human=1",
            headers = browserHeaders(),
            referer = mainUrl,
            allowRedirects = false
        )

        humanCookie = response.headers["Set-Cookie"]
            ?.substringBefore(";")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "user_is_human=true"
    }

    private fun verifiedHeaders(): Map<String, String> {
        val cookie = humanCookie
        val headers = browserHeaders()

        return if (cookie.isNullOrBlank()) {
            headers
        } else {
            headers + mapOf("Cookie" to cookie)
        }
    }

    private fun normalizeSiteUrl(url: String): String {
        val value = url.trim()
        if (value.startsWith(mainUrl, true)) return value

        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase().orEmpty()
            val isOppaHost = host == "oppa.biz" ||
                Regex("""^45\.11\.57\.\d{1,3}$""").matches(host)

            if (isOppaHost) {
                "$mainUrl${uri.rawPath ?: "/"}${uri.rawQuery?.let { "?$it" } ?: ""}"
            } else {
                value
            }
        }.getOrDefault(value)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = selectFirst(".tt")
            ?.ownText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: selectFirst("h2")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = selectFirst("img")
            ?.getImageUrl()
            ?.let(::fixUrl)

        val badge = selectFirst(".typez, .tt span")
            ?.text()
            ?.lowercase()
            ?: ""

        val isMovie = badge.contains("movie") ||
            href.contains("/movie-", true)

        return if (isMovie) {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    private fun decodeMirror(
        value: String,
        label: String,
        referer: String
    ): List<String> {
        val cleaned = value.trim()
        val cleanLabel = label.trim()

        if (cleaned.isBlank()) {
            return emptyList()
        }

        if (
            cleaned.startsWith("http", true) ||
            cleaned.startsWith("//") ||
            cleaned.startsWith("/")
        ) {
            return cleaned.toAbsoluteUrl(referer)?.let { listOf(it) }
                ?: emptyList()
        }

        val decoded = decodeBase64(cleaned)
            ?: run {
                Log.e(TAG, "OPPA_DECODE_FAILED label=$cleanLabel")
                return emptyList()
            }

        Log.i(TAG, "OPPA_DECODE_LABEL = $cleanLabel")
        Log.i(TAG, "OPPA_DECODE_HTML = ${decoded.take(500)}")

        val lowerLabel = cleanLabel.lowercase()
        val results = linkedSetOf<String>()

        Jsoup.parse(decoded)
            .select("iframe")
            .mapNotNull { it.getIframeUrl() }
            .mapNotNull { it.toAbsoluteUrl(referer) }
            .forEach { results.add(it) }

        val hydraxId = shortcodeId(decoded, "Hydrax")
            ?: if (lowerLabel.contains("hydrax")) {
                anyShortcodeId(decoded)
            } else {
                null
            }

        if (!hydraxId.isNullOrBlank()) {
            hydraxCandidates(hydraxId)
                .forEach { results.add(it) }
        }

        Regex("""https?://[^\s'"<>]+""")
            .findAll(decoded)
            .map { it.value }
            .mapNotNull { it.toAbsoluteUrl(referer) }
            .forEach { results.add(it) }

        results.forEach {
            Log.i(TAG, "OPPA_MIRROR = $it")
        }

        return results.toList()
    }

    private fun decodeBase64(text: String): String? {
        return runCatching {
            val compact = text.replace("\\s".toRegex(), "")
            val normalized = compact.padEnd(
                compact.length + (4 - compact.length % 4) % 4,
                '='
            )

            String(
                Base64.getDecoder().decode(normalized),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    private fun shortcodeId(
        text: String,
        name: String
    ): String? {
        return Regex(
            """\[$name\s+id=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun anyShortcodeId(text: String): String? {
        return Regex(
            """id=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun streamSbCandidates(id: String): List<String> {
        return listOf(
            "https://sbembed1.com/e/$id.html",
            "https://sbembed4.com/e/$id.html",
            "https://sbvideo.net/e/$id.html",
            "https://viewsb.com/e/$id",
            "https://watchsb.com/e/$id",
            "https://embedsb.com/e/$id",
            "https://playersb.com/e/$id",
            "https://streamsb.net/e/$id",
            "https://streamsb.com/e/$id",
            "https://sbembed.com/e/$id",
            "https://sbplay.org/e/$id",
            "https://streamsss.net/e/$id"
        )
    }

    private fun hydraxCandidates(id: String): List<String> {
        return listOf(
            "https://abyssplayer.com/?v=$id"
        )
    }




    private fun MutableMap<String, String>.cleanVideoHeaders(): MutableMap<String, String> {
        val blocked = setOf(
            "host",
            "connection",
            "accept-encoding"
        )

        keys.toList().forEach { key ->
            if (key.lowercase() in blocked) {
                remove(key)
            }
        }

        return this
    }

    private fun MutableMap<String, String>.cleanAbyssHeaders(): MutableMap<String, String> {
        val blocked = setOf(
            "host",
            "connection",
            "accept-encoding",
            "range",
            "origin"
        )

        keys.toList().forEach { key ->
            if (key.lowercase() in blocked) {
                remove(key)
            }
        }

        return this
    }

    private fun String.toAbsoluteStreamUrl(): String {
        val value = trim()

        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", true) -> value
            value.startsWith("/") -> "https://abyssplayer.com$value"
            else -> value
        }
    }

    private fun isRealStreamServer(
        label: String,
        url: String
    ): Boolean {
        val value = "${label.lowercase()} ${url.lowercase()}"

        return value.contains("hydrax") ||
            value.contains("abyssplayer") ||
            value.contains("abyss.to") ||
            value.contains("turbovip") ||
            value.contains("emturbovid") ||
            value.contains("turbovidhls") ||
            value.contains("filelions") ||
            value.contains("filelion") ||
            value.contains("minochinos") ||
            value.contains("filemoon")
    }

    private fun String.priorityScore(): Int {
        val value = lowercase()

        return when {
            value.contains("emturbovid") ||
                value.contains("turbovidhls") ||
                value.contains("turbovip") -> 0

            value.contains("minochinos") ||
                value.contains("filelions") ||
                value.contains("filelion") ||
                value.contains("filemoon") -> 1

            value.contains("abyss.to") ||
                value.contains("abyssplayer") ||
                value.contains("hydrax") -> 2

            else -> 9
        }
    }

    private fun Element.getImageUrl(): String? {
        return when {
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("data-lazy-src").isNotBlank() -> attr("data-lazy-src")
            attr("data-original").isNotBlank() -> attr("data-original")
            attr("src").isNotBlank() -> attr("src")
            attr("srcset").isNotBlank() ->
                attr("srcset").substringBefore(",").substringBefore(" ")
            else -> null
        }
    }

    private fun Element.getIframeUrl(): String? {
        return when {
            attr("data-litespeed-src").isNotBlank() -> attr("data-litespeed-src")
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("src").isNotBlank() -> attr("src")
            else -> null
        }
    }

    private fun String.toAbsoluteUrl(referer: String): String? {
        val value = trim()
        if (value.isBlank()) return null

        return runCatching {
            when {
                value.startsWith("http://", true) ||
                    value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                value.startsWith("/") -> "${mainUrl.trimEnd('/')}$value"
                else -> URI(referer).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun Document.getInfo(key: String): String? {
        return select("div.spe span")
            .firstOrNull {
                it.text().startsWith("$key:", true)
            }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseStatus(text: String?): ShowStatus? {
        val value = text?.lowercase() ?: return null

        return when {
            value.contains("completed") -> ShowStatus.Completed
            value.contains("ongoing") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.safeInt(): Int? {
        return Regex("\\d+")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun String.safeYear(): Int? {
        return Regex("(19|20)\\d{2}")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun Document.episodes(): List<Episode> {
        return select("div.eplister li a")
            .reversed()
            .mapIndexed { index, element ->
                val episodeNumber = element
                    .selectFirst(".epl-num")
                    ?.text()
                    ?.safeInt()
                    ?: index + 1

                newEpisode(
                    fixUrl(element.attr("href"))
                ) {
                    episode = episodeNumber
                    name = element
                        .selectFirst(".epl-title")
                        ?.text()
                        ?.trim()
                }
            }
    }

    private fun Document.poster(): String? {
        return selectFirst(".thumb img, .poster img, .bigcontent img")
            ?.getImageUrl()
            ?.let(::fixUrl)
    }

    private fun Document.titleText(): String? {
        return selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.descriptionText(): String? {
        return select(".entry-content p")
            .joinToString("\n") { it.text().trim() }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Document.tagsList(): List<String> {
        return select(".genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "OppaDrama"
        private const val DEFAULT_SITE_URL = "http://45.11.57.188"
        private const val SERVER_RACE_TIMEOUT_MS = 14000L
        private const val SLOW_FALLBACK_WAIT_MS = 18000L
        private const val COLLECT_AFTER_FIRST_LINK_MS = 2200L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
    }
}
