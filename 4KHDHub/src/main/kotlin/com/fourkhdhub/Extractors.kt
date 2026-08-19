package com.fourkhdhub

// KCS12 4KHDHub Extractors - bounded parallel scan v4

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private val extractorBrowserHeaders = mapOf(
    "User-Agent" to USER_AGENT,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
)

class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override var mainUrl = "https://hubcloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val target = try {
            resolveHubCloudPage(url, referer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log4k("HubCloud landing failed host=${hostOf(url)}: ${error.message}")
            return
        }

        if (target.isBlank()) {
            log4k("HubCloud landing returned blank host=${hostOf(url)}")
            return
        }

        val response = try {
            app.get(
                target,
                headers = extractorBrowserHeaders,
                referer = referer ?: url,
                timeout = 20L,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log4k("HubCloud page failed host=${hostOf(target)}: ${error.message}")
            return
        }

        val document = response.document
        val html = response.text
        val title = sequenceOf(
            document.selectFirst("div.card-header")?.text(),
            document.title(),
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val quality = Regex("""(?i)(\d{3,4})p""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { getQualityFromName("${it}p") }
            ?: Qualities.Unknown.value

        val emitted = ConcurrentHashMap.newKeySet<String>()
        val collectedLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val collectCallback: (ExtractorLink) -> Unit = { link -> collectedLinks.add(link) }
        val buttons = collectHubCloudLinks(document, html)

        if (buttons.isEmpty()) {
            log4k("HubCloud no server buttons targetHost=${hostOf(target)} title=$title")
            return
        }

        // HubCloud pages often expose several mirrors. Scan them concurrently,
        // but cap active requests so one page cannot flood the same host.
        val buttonSemaphore = Semaphore(3)
        log4k("HubCloud scanning buttons=${buttons.size} concurrency=3")
        buttons.amap { item ->
            buttonSemaphore.withPermit {
                val rawHref = item.first
                val label = item.second.ifBlank { "Server" }
                val lower = label.lowercase()
                val hrefHost = hostOf(rawHref)

                try {
                    when {
                    rawHref.contains("workers.dev", ignoreCase = true) -> {
                        emitDirect(
                            url = rawHref,
                            label = "Direct",
                            referer = "",
                            quality = quality,
                            size = size,
                            emitted = emitted,
                            callback = collectCallback,
                        )
                    }

                    rawHref.contains("hubcdn.fans", ignoreCase = true) -> {
                        emitDirect(
                            url = rawHref,
                            label = "Fast 10Gbps",
                            referer = "",
                            quality = quality,
                            size = size,
                            emitted = emitted,
                            callback = collectCallback,
                        )
                    }

                    lower.contains("buzzserver") -> {
                        val downloadUrl = if (rawHref.endsWith("/download", true)) {
                            rawHref
                        } else {
                            rawHref.trimEnd('/') + "/download"
                        }

                        val buzzResponse = app.get(
                            downloadUrl,
                            headers = extractorBrowserHeaders,
                            referer = rawHref,
                            allowRedirects = false,
                            timeout = 15L,
                        )

                        val redirect = buzzResponse.headers["hx-redirect"]
                            ?: buzzResponse.headers["HX-Redirect"]
                            ?: buzzResponse.headers["Location"]
                            ?: buzzResponse.headers["location"]

                        if (!redirect.isNullOrBlank()) {
                            emitDirect(
                                url = absoluteUrl(rawHref, redirect),
                                label = "BuzzServer",
                                referer = rawHref,
                                quality = quality,
                                size = size,
                                emitted = emitted,
                                callback = collectCallback,
                            )
                        }
                    }

                    lower.contains("pixeldra") ||
                        lower.contains("pixelserver") ||
                        lower.contains("pixel server") ||
                        lower.contains("pixeldrain") -> {
                        emitDirect(
                            url = toPixelDownloadUrl(rawHref),
                            label = "PixelDrain",
                            referer = target,
                            quality = quality,
                            size = size,
                            emitted = emitted,
                            callback = collectCallback,
                        )
                    }

                    lower.contains("10gbps") -> {
                        // Some HubCloud 10Gbps buttons now point to a gamerxyt
                        // dl.php wrapper whose `link` parameter is the real media URL.
                        // Sending the wrapper itself to ExoPlayer returns HTML and causes
                        // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
                        val unwrapped = unwrapDownloadWrapper(rawHref) ?: rawHref
                        val finalUrl = resolveFinalUrl(unwrapped, target) ?: unwrapped
                        emitDirect(
                            url = finalUrl,
                            label = "10Gbps",
                            referer = target,
                            quality = quality,
                            size = size,
                            emitted = emitted,
                            callback = collectCallback,
                        )
                    }

                    isDirectButton(lower) || looksLikeMedia(rawHref) -> {
                        emitDirect(
                            url = rawHref,
                            label = label,
                            referer = target,
                            quality = quality,
                            size = size,
                            emitted = emitted,
                            callback = collectCallback,
                        )
                    }

                    rawHref.contains("hubdrive", ignoreCase = true) -> {
                        HubDriveExtractor().getUrl(
                            rawHref,
                            target,
                            subtitleCallback,
                            collectCallback,
                        )
                    }

                    rawHref.contains("hubcdn", ignoreCase = true) -> {
                        HubCdnExtractor().getUrl(
                            rawHref,
                            target,
                            subtitleCallback,
                            collectCallback,
                        )
                    }

                    rawHref.contains("hdstream4u", ignoreCase = true) -> {
                        HdStream4uExtractor().getUrl(
                            rawHref,
                            target,
                            subtitleCallback,
                            collectCallback,
                        )
                    }

                    rawHref.contains("hubstream", ignoreCase = true) -> {
                        HubstreamExtractor().getUrl(
                            rawHref,
                            target,
                            subtitleCallback,
                            collectCallback,
                        )
                    }

                    else -> {
                        when {
                            isHubCloudNavigationLink(rawHref, label) -> {
                                log4k(
                                    "HubCloud skip navigation label=$label host=$hrefHost",
                                )
                            }

                            else -> {
                                // Avoid CloudStream's fuzzy extractor fallback here.
                                // HubCloud pages contain branding/navigation links and
                                // fuzzy matching can route an unrelated domain into a
                                // wrong extractor such as StreamSB.
                                val loaded = loadExactRegisteredExtractor(
                                    rawHref,
                                    target,
                                    subtitleCallback,
                                    collectCallback,
                                )

                                if (!loaded) {
                                    log4k(
                                        "HubCloud unsupported button label=$label host=$hrefHost",
                                    )
                                }
                            }
                        }
                    }
                }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log4k("HubCloud button '$label' host=$hrefHost failed: ${error.message}")
                }
            }
        }

        val orderedLinks = collectedLinks
            .distinctBy { it.url }
            .sortedBy { codecPriority("${it.name} ${it.url}") }

        val avcCount = orderedLinks.count { codecPriority("${it.name} ${it.url}") == 0 }
        val hevcCount = orderedLinks.count { codecPriority("${it.name} ${it.url}") == 2 }
        val unknownCount = orderedLinks.size - avcCount - hevcCount
        log4k("HubCloud sources total=${orderedLinks.size} avc=$avcCount unknown=$unknownCount hevc=$hevcCount")

        orderedLinks.forEach(callback)
    }

    private suspend fun resolveHubCloudPage(url: String, referer: String?): String {
        if (url.contains("hubcloud.php", ignoreCase = true)) return url

        val response = app.get(
            url,
            headers = extractorBrowserHeaders,
            referer = referer ?: "",
            timeout = 15L,
        )
        val document = response.document
        val html = response.text

        val downloadElement = document.selectFirst("#download[href]")
        val download = downloadElement?.let { element ->
            element.absUrl("href")
                .ifBlank { element.attr("href") }
                .trim()
        }.orEmpty()
        if (download.isNotBlank()) return absoluteUrl(url, download)

        val hubCloudAnchor = document.selectFirst("a[href*='hubcloud.php']")
        val anchorUrl = hubCloudAnchor?.let { element ->
            element.absUrl("href")
                .ifBlank { element.attr("href") }
                .trim()
        }.orEmpty()
        if (anchorUrl.isNotBlank()) return absoluteUrl(url, anchorUrl)

        val scripted = listOf(
            Regex("""(?is)\bvar\s+url\s*=\s*['\"]([^'\"]+)['\"]"""),
            Regex("""(?is)location(?:\.href)?\s*=\s*['\"]([^'\"]+)['\"]"""),
        ).firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.trim()
        }
        if (!scripted.isNullOrBlank()) return absoluteUrl(url, scripted)

        val alreadyFinal = document.select(
            "a[href*='workers.dev'], a[href*='hubcdn.fans'], a.btn[href]",
        ).isNotEmpty()
        return if (alreadyFinal) url else ""
    }

    private fun collectHubCloudLinks(
        document: org.jsoup.nodes.Document,
        html: String,
    ): List<Pair<String, String>> {
        val links = LinkedHashMap<String, String>()

        document.select(
            "div.card-body a[href], div.card a[href], " +
                "a.btn[href], a[download][href], " +
                "a[href*='workers.dev'], a[href*='hubcdn.fans']",
        ).forEach { anchor ->
            val href = anchor.absUrl("href")
                .ifBlank { anchor.attr("href") }
                .trim()
            if (href.startsWith("http", ignoreCase = true)) {
                val label = anchor.text()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { anchor.attr("download").trim() }
                    .ifBlank { "Server" }
                links.putIfAbsent(href, label)
            }
        }

        Regex("""https?://[^\s'\"<>\\]+""")
            .findAll(html)
            .map { it.value.replace("\\/", "/").trimEnd(')', ',', ';') }
            .filter {
                it.contains("workers.dev", true) ||
                    it.contains("hubcdn.fans", true)
            }
            .forEach { links.putIfAbsent(it, "Direct") }

        return links.map { it.key to it.value }
    }

    private fun isDirectButton(label: String): Boolean {
        return listOf(
            "fsl server",
            "download file",
            "s3 server",
            "fslv2",
            "mega server",
            "pdl server",
        ).any { it in label }
    }

    private fun looksLikeMedia(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") ||
            clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd")
    }

    private fun toPixelDownloadUrl(url: String): String {
        if (url.contains("download", ignoreCase = true)) return url

        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val id = uri.path.orEmpty().trim('/').substringAfterLast('/')
        if (id.isBlank()) return url

        return "${uri.scheme}://${uri.host}/api/file/$id?download"
    }

    private suspend fun emitDirect(
        url: String,
        label: String,
        referer: String,
        quality: Int,
        size: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.isBlank() || !url.startsWith("http", true) || !emitted.add(url)) return

        val codec = codecLabel("$label $url")
        val displayName = buildString {
            append("HubCloud • ")
            if (codec != null) append(codec).append(" • ")
            append(label)
            if (size.isNotBlank()) append(" • ").append(size)
        }

        callback(
            newExtractorLink(
                source = "HubCloud",
                name = displayName,
                url = url,
                type = INFER_TYPE,
            ) {
                this.referer = referer
                this.quality = quality
            },
        )
    }
}

class HubDriveExtractor : ExtractorApi() {
    override val name = "HubDrive"
    override var mainUrl = "https://hubdrive"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = try {
            app.get(
                url,
                headers = extractorBrowserHeaders,
                referer = referer ?: "https://4khdhub.one/",
                timeout = 15L,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log4k("HubDrive page failed host=${hostOf(url)}: ${error.message}")
            return
        }

        val document = response.document
        val html = response.text
        val candidates = LinkedHashSet<String>()

        document.select(
            "a.btn[href], a[href*='hubcloud'], a[href*='hubcdn'], " +
                "a[href*='hblinks'], a[href*='hdstream4u'], a[href*='hubstream']",
        ).forEach { anchor ->
            val next = anchor.absUrl("href")
                .ifBlank { anchor.attr("href") }
                .trim()
            if (next.startsWith("http", true) && next != url) candidates.add(next)
        }

        Regex("""https?://[^\s'\"<>\\]+""")
            .findAll(html)
            .map { it.value.replace("\\/", "/").trimEnd(')', ',', ';') }
            .filter { isKnownIntermediateHost(it) || looksLikePlayableUrl(it) }
            .filter { it != url }
            .forEach { candidates.add(it) }

        if (candidates.isEmpty()) {
            log4k("HubDrive no candidate links host=${hostOf(url)}")
            return
        }

        for (next in candidates) {
            try {
                dispatchKnownExtractor(
                    next,
                    url,
                    subtitleCallback,
                    callback,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log4k("HubDrive candidate host=${hostOf(next)} failed: ${error.message}")
            }
        }
    }
}

open class HblinksExtractor : ExtractorApi() {
    override val name = "Hblinks"
    override var mainUrl = "https://hblinks"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(
            url,
            headers = extractorBrowserHeaders,
            referer = referer ?: "",
            timeout = 15L,
        )
        val document = response.document
        val html = response.text
        val links = LinkedHashSet<String>()

        document.select(
            "h3 a[href], h5 a[href], div.entry-content a[href], a.btn[href]",
        ).forEach { anchor ->
            val next = anchor.absUrl("href")
                .ifBlank { anchor.attr("href") }
                .trim()
            if (next.startsWith("http", true)) links.add(next)
        }

        Regex("""https?://[^\s'\"<>\\]+""")
            .findAll(html)
            .map { it.value.replace("\\/", "/").trimEnd(')', ',', ';') }
            .filter { isKnownIntermediateHost(it) || looksLikePlayableUrl(it) }
            .forEach { links.add(it) }

        for (next in links) {
            try {
                dispatchKnownExtractor(next, url, subtitleCallback, callback)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log4k("Hblinks candidate host=${hostOf(next)} failed: ${error.message}")
            }
        }
    }
}

class HubCdnExtractor : ExtractorApi() {
    override val name = "HubCDN"
    override var mainUrl = "https://hubcdn"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.contains("hubcdn.fans", ignoreCase = true)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "HubCloud • Fast 10Gbps",
                    url = url,
                    type = INFER_TYPE,
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                },
            )
            return
        }

        val response = app.get(
            url,
            headers = extractorBrowserHeaders,
            referer = referer ?: "",
            timeout = 15L,
        )
        val document = response.document
        val html = response.text
        val script = document
            .selectFirst("script:containsData(var reurl)")
            ?.data()
            .orEmpty()

        val reurl = Regex("""reurl\s*=\s*"([^"]+)"""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)

        val fromReurl = reurl
            ?.substringAfter("?r=", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { base64Decode(it) }.getOrNull() }
            ?.substringAfterLast("link=", "")
            ?.takeIf { it.isNotBlank() }

        val fromInline = Regex("""r=([A-Za-z0-9+/=]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { base64Decode(it) }.getOrNull() }
            ?.substringAfterLast("link=", "")
            ?.takeIf { it.isNotBlank() }

        val media = fromReurl ?: fromInline ?: return
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = media,
                type = INFER_TYPE,
            ) {
                this.referer = url
            },
        )
    }
}

class HdStream4uExtractor : ExtractorApi() {
    override val name = "HDStream4u"
    override var mainUrl = "https://hdstream4u"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = baseOrigin(url)
        if (origin.isBlank()) return
        val extractor = object : VidHidePro() {
            override var mainUrl = origin
        }
        extractor.getUrl(
            url,
            referer,
            subtitleCallback,
            callback,
        )
    }
}

class HubstreamExtractor : ExtractorApi() {
    override val name = "Hubstream"
    override var mainUrl = "https://hubstream"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = baseOrigin(url)
        if (origin.isBlank()) return
        VidStack().apply { mainUrl = origin }.getUrl(
            url,
            referer,
            subtitleCallback,
            callback,
        )
    }
}

class PixelDrainDevExtractor : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

private suspend fun dispatchKnownExtractor(
    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    return when {
        url.contains("hubcloud", true) -> {
            HubCloudExtractor().getUrl(url, referer, subtitleCallback, callback)
            true
        }
        url.contains("hubdrive", true) -> {
            HubDriveExtractor().getUrl(url, referer, subtitleCallback, callback)
            true
        }
        url.contains("hubcdn", true) -> {
            HubCdnExtractor().getUrl(url, referer, subtitleCallback, callback)
            true
        }
        url.contains("hblinks", true) -> {
            HblinksExtractor().getUrl(url, referer, subtitleCallback, callback)
            true
        }
        url.contains("hdstream4u", true) -> {
            HdStream4uExtractor().getUrl(url, referer, subtitleCallback, callback)
            true
        }
        url.contains("hubstream", true) -> {
            HubstreamExtractor().getUrl(url, referer, subtitleCallback, callback)
            true
        }
        url.contains("workers.dev", true) || url.contains("hubcdn.fans", true) || looksLikePlayableUrl(url) -> {
            callback(
                newExtractorLink(
                    source = "4KHDHub Direct",
                    name = "4KHDHub Direct",
                    url = url,
                    type = INFER_TYPE,
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                },
            )
            true
        }
        else -> loadExtractor(url, referer, subtitleCallback, callback)
    }
}

private fun isKnownIntermediateHost(url: String): Boolean {
    val host = hostOf(url)
    return listOf(
        "hubcloud",
        "hubdrive",
        "hubcdn",
        "hblinks",
        "hdstream4u",
        "hubstream",
        "workers.dev",
        "pixeldrain",
    ).any { host.contains(it, ignoreCase = true) }
}

private fun looksLikePlayableUrl(url: String): Boolean {
    val clean = url.substringBefore('?').lowercase()
    return clean.endsWith(".mp4") ||
        clean.endsWith(".mkv") ||
        clean.endsWith(".m3u8") ||
        clean.endsWith(".mpd")
}


private fun extractorMatchKey(url: String): String {
    return url
        .trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
}

private suspend fun loadExactRegisteredExtractor(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val currentUrl = unshortenLinkSafe(url)
    val compareUrl = extractorMatchKey(currentUrl)

    if (compareUrl.isBlank()) return false

    for (index in extractorApis.lastIndex downTo 0) {
        val extractor = extractorApis[index]
        val main = extractorMatchKey(extractor.mainUrl)
        if (main.isNotBlank() && compareUrl.startsWith(main)) {
            extractor.getUrl(
                currentUrl,
                referer,
                subtitleCallback,
                callback,
            )
            return true
        }
    }

    return false
}

private fun isHubCloudNavigationLink(url: String, label: String): Boolean {
    val host = hostOf(url)
    val lowerLabel = label.lowercase()

    if (
        host == "hdhub4u.ms" ||
        host.endsWith(".hdhub4u.ms") ||
        host == "4khdhub.one" ||
        host.endsWith(".4khdhub.one")
    ) {
        return true
    }

    val looksLikeBrandLink =
        lowerLabel == host ||
            lowerLabel == "hdhub4u" ||
            lowerLabel == "hdhub4u.ms"

    val looksLikeServerAction = listOf(
        "server",
        "download",
        "direct",
        "stream",
        "mirror",
        "watch",
    ).any { it in lowerLabel }

    return looksLikeBrandLink && !looksLikeServerAction
}

private fun hostOf(url: String): String {
    return runCatching {
        URI(url).host?.removePrefix("www.")?.lowercase()
    }.getOrNull().orEmpty().ifBlank { "unknown" }
}

private fun baseOrigin(url: String): String {
    return runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching ""
        val host = uri.host ?: return@runCatching ""
        "$scheme://$host"
    }.getOrDefault("")
}

private fun absoluteUrl(base: String, value: String): String {
    if (value.startsWith("http", ignoreCase = true)) return value
    return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
}


private fun unwrapDownloadWrapper(input: String): String? {
    val lower = input.lowercase()
    val marker = when {
        "?link=" in lower -> "?link="
        "&link=" in lower -> "&link="
        else -> return null
    }

    val index = lower.indexOf(marker)
    if (index < 0) return null

    val raw = input.substring(index + marker.length).trim()
    if (raw.isBlank()) return null

    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }
        .getOrDefault(raw)
        .trim()

    return decoded.takeIf { it.startsWith("http", ignoreCase = true) }
}

private suspend fun resolveFinalUrl(
    input: String,
    referer: String,
): String? {
    var current = input

    repeat(7) {
        val response = try {
            app.head(
                current,
                allowRedirects = false,
                timeout = 6000L,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            try {
                app.get(
                    current,
                    headers = extractorBrowserHeaders,
                    referer = referer,
                    allowRedirects = false,
                    timeout = 8000L,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return current
            }
        }

        val location = response.headers["Location"] ?: response.headers["location"]
        if (location.isNullOrBlank()) return current
        current = absoluteUrl(current, location)
    }

    return current
}

private fun codecLabel(value: String): String? {
    val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }
        .getOrDefault(value)
        .lowercase()

    return when {
        Regex("""(?:\bhevc\b|\bx265\b|\bh[\s._-]?265\b)""")
            .containsMatchIn(decoded) -> "HEVC/H.265"

        Regex("""(?:\bavc\b|\bx264\b|\bh[\s._-]?264\b)""")
            .containsMatchIn(decoded) -> "AVC/H.264"

        else -> null
    }
}

private fun codecPriority(value: String): Int {
    return when (codecLabel(value)) {
        "AVC/H.264" -> 0
        "HEVC/H.265" -> 2
        else -> 1
    }
}

private fun log4k(message: String) {
    println("[4KHDHub] $message")
}
