package com.anichin

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val directVideoId = extractEmbedVideoId(url)
        val sourcePage = if (directVideoId == null) {
            fetchText(url, referer ?: "$mainUrl/") ?: return
        } else {
            null
        }

        val videoId = directVideoId
            ?: sourcePage?.let(::extractMainPlayerVideoId)
            ?: return

        val embedUrl = "$mainUrl/embed/$videoId"
        val embedPage = fetchText(embedUrl, referer ?: url)

        /*
         * The object embedded under m.f[videoId] belongs to this exact video.
         * Older Rumble uploads often expose only ua.mp4 and no ua.hls.
         */
        val metadata = embedPage
            ?.let { extractExactVideoMetadata(it, videoId) }
            ?: fetchApiMetadata(videoId, embedUrl)
            ?: return

        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl
        )

        if (
            emitExactHls(
                metadata = metadata,
                embedUrl = embedUrl,
                headers = streamHeaders,
                callback = callback
            )
        ) {
            return
        }

        emitExactMp4(
            metadata = metadata,
            embedUrl = embedUrl,
            headers = streamHeaders,
            callback = callback
        )
    }

    private suspend fun fetchText(url: String, referer: String): String? {
        return try {
            app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 6L
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchApiMetadata(
        videoId: String,
        embedUrl: String
    ): JsonNode? {
        val metadataText = fetchText(
            "$mainUrl/embedJS/u3/?request=video&ver=2&v=$videoId",
            embedUrl
        ) ?: return null

        return AppUtils.tryParseJson<JsonNode>(metadataText)
    }

    private suspend fun emitExactHls(
        metadata: JsonNode,
        embedUrl: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = mutableListOf<MediaCandidate>()
        collectMediaCandidates(
            node = metadata.path("ua").path("hls"),
            inheritedHeight = null,
            extension = ".m3u8",
            output = candidates
        )

        val verifiedMedia = mutableListOf<MediaCandidate>()
        val orderedCandidates = candidates
            .distinctBy { it.url }
            .sortedWith(
                compareBy<MediaCandidate> { candidate ->
                    if (
                        candidate.url.contains("master", true) ||
                        candidate.url.contains("/hls-vod/", true)
                    ) 0 else 1
                }.thenBy { it.height ?: Int.MAX_VALUE }
            )

        for (candidate in orderedCandidates) {
            val playlist = fetchText(candidate.url, embedUrl) ?: continue
            if (!playlist.contains("#EXTM3U", ignoreCase = true)) continue

            if (playlist.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = candidate.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            verifiedMedia += candidate
        }

        val fixedHls = verifiedMedia
            .filter { (it.height ?: 0) >= MINIMUM_HEIGHT }
            .distinctBy { it.height }
            .sortedByDescending { it.height }

        fixedHls.forEach { candidate ->
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = candidate.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = headers
                    this.quality = candidate.height ?: Qualities.Unknown.value
                }
            )
        }

        return fixedHls.isNotEmpty()
    }

    private suspend fun emitExactMp4(
        metadata: JsonNode,
        embedUrl: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val candidates = mutableListOf<MediaCandidate>()
        collectMediaCandidates(
            node = metadata.path("ua").path("mp4"),
            inheritedHeight = null,
            extension = ".mp4",
            output = candidates
        )

        candidates
            .filter { (it.height ?: 0) >= MINIMUM_HEIGHT }
            .distinctBy { it.height }
            .sortedByDescending { it.height }
            .forEach { candidate ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = candidate.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.headers = headers
                        this.quality = candidate.height ?: Qualities.Unknown.value
                    }
                )
            }
    }

    private fun extractEmbedVideoId(url: String): String? {
        return EMBED_VIDEO_ID
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractMainPlayerVideoId(pageText: String): String? {
        val normalized = pageText
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        return PLAY_VIDEO_ID
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?: PLAY_VIDEO_OBJECT_ID
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
            ?: EMBED_VIDEO_ID
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun extractExactVideoMetadata(
        pageText: String,
        videoId: String
    ): JsonNode? {
        val markers = listOf(
            "m.f[\"$videoId\"]=",
            "m.f['$videoId']="
        )

        for (marker in markers) {
            val markerIndex = pageText.indexOf(marker)
            if (markerIndex < 0) continue

            val objectStart = pageText.indexOf('{', markerIndex + marker.length)
            if (objectStart < 0) continue

            val objectEnd = findJsonObjectEnd(pageText, objectStart)
            if (objectEnd <= objectStart) continue

            val json = pageText.substring(objectStart, objectEnd + 1)
            AppUtils.tryParseJson<JsonNode>(json)?.let { return it }
        }

        return null
    }

    private fun findJsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val character = text[index]

            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }

            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }

        return -1
    }

    private fun collectMediaCandidates(
        node: JsonNode,
        inheritedHeight: Int?,
        extension: String,
        output: MutableList<MediaCandidate>
    ) {
        when {
            node.isObject -> {
                val ownHeight = node.path("meta").path("h")
                    .asInt(0)
                    .takeIf { it > 0 }
                    ?: inheritedHeight

                node.path("url")
                    .takeIf { it.isTextual }
                    ?.asText()
                    ?.takeIf { it.contains(extension, ignoreCase = true) }
                    ?.let { output += MediaCandidate(it, ownHeight) }

                node.fields().forEach { (key, child) ->
                    if (key != "url" && key != "meta") {
                        collectMediaCandidates(
                            node = child,
                            inheritedHeight = key.toIntOrNull() ?: ownHeight,
                            extension = extension,
                            output = output
                        )
                    }
                }
            }

            node.isArray -> {
                node.forEach { child ->
                    collectMediaCandidates(
                        node = child,
                        inheritedHeight = inheritedHeight,
                        extension = extension,
                        output = output
                    )
                }
            }

            node.isTextual && node.asText().contains(extension, true) -> {
                output += MediaCandidate(node.asText(), inheritedHeight)
            }
        }
    }

    private data class MediaCandidate(
        val url: String,
        val height: Int?
    )

    companion object {
        private const val MINIMUM_HEIGHT = 720

        private val EMBED_VIDEO_ID = Regex(
            """rumble\.com/embed/(?:[0-9a-z]+\.)?([0-9a-z]+)""",
            RegexOption.IGNORE_CASE
        )

        private val PLAY_VIDEO_ID = Regex(
            """Rumble\(\s*["']play["']\s*,\s*\{[\s\S]{0,1500}?["']?video["']?\s*:\s*["']([0-9a-z]+)["']""",
            RegexOption.IGNORE_CASE
        )

        private val PLAY_VIDEO_OBJECT_ID = Regex(
            """Rumble\(\s*["']play["']\s*,\s*\{[\s\S]{0,1500}?["']?video["']?\s*:\s*\{[\s\S]{0,300}?["']?id["']?\s*:\s*["']([0-9a-z]+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
