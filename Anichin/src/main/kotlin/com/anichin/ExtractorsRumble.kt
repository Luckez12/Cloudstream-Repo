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
        val pageText = if (directVideoId == null) {
            try {
                app.get(
                    url,
                    referer = referer ?: "$mainUrl/",
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).text
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        } else {
            null
        }

        val videoId = directVideoId
            ?: pageText?.let(::extractMainPlayerVideoId)
            ?: return

        val embedUrl = "$mainUrl/embed/$videoId"
        val metadataText = try {
            app.get(
                "$mainUrl/embedJS/u3/?request=video&ver=2&v=$videoId",
                referer = embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val metadata = AppUtils.tryParseJson<JsonNode>(metadataText)
            ?: return

        val candidates = mutableListOf<HlsCandidate>()
        collectHlsCandidates(
            metadata.path("ua").path("hls"),
            inheritedHeight = null,
            output = candidates
        )

        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl
        )

        val verifiedMedia = mutableListOf<HlsCandidate>()

        candidates
            .distinctBy { it.url }
            .sortedWith(
                compareBy<HlsCandidate> { candidate ->
                    if (
                        candidate.url.contains("master", true) ||
                        candidate.url.contains("/hls-vod/", true)
                    ) 0 else 1
                }.thenBy { it.height ?: Int.MAX_VALUE }
            )
            .forEach { candidate ->
                val playlist = try {
                    app.get(
                        candidate.url,
                        referer = embedUrl,
                        headers = streamHeaders,
                        timeout = 5L
                    ).text
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@forEach
                }

                if (!playlist.contains("#EXTM3U", ignoreCase = true)) {
                    return@forEach
                }

                if (playlist.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = candidate.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedUrl
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

                verifiedMedia += candidate
            }

        verifiedMedia
            .filter { (it.height ?: 0) >= 720 }
            .sortedByDescending { it.height }
            .forEach { candidate ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = candidate.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.headers = streamHeaders
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
            ?: EMBED_VIDEO_ID
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun collectHlsCandidates(
        node: JsonNode,
        inheritedHeight: Int?,
        output: MutableList<HlsCandidate>
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
                    ?.takeIf { it.contains(".m3u8", ignoreCase = true) }
                    ?.let { output += HlsCandidate(it, ownHeight) }

                node.fields().forEach { (key, child) ->
                    if (key != "url" && key != "meta") {
                        collectHlsCandidates(
                            child,
                            key.toIntOrNull() ?: ownHeight,
                            output
                        )
                    }
                }
            }

            node.isArray -> {
                node.forEach { child ->
                    collectHlsCandidates(child, inheritedHeight, output)
                }
            }

            node.isTextual && node.asText().contains(".m3u8", true) -> {
                output += HlsCandidate(node.asText(), inheritedHeight)
            }
        }
    }

    private data class HlsCandidate(
        val url: String,
        val height: Int?
    )

    companion object {
        private val EMBED_VIDEO_ID = Regex(
            """rumble\.com/embed/(?:[0-9a-z]+\.)?([0-9a-z]+)""",
            RegexOption.IGNORE_CASE
        )

        private val PLAY_VIDEO_ID = Regex(
            """Rumble\(\s*["']play["']\s*,\s*\{[\s\S]{0,1500}?["']?video["']?\s*:\s*["']([0-9a-z]+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
