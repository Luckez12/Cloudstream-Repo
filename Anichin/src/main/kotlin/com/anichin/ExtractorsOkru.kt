package com.anichin

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder

open class OkRuExtractor : ExtractorApi() {
    override var name = "OK.ru"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractVideoId(url)
        val embedUrl = if (videoId != null) {
            "https://ok.ru/videoembed/$videoId"
        } else {
            normalizeUrl(url)
        }

        val videos = linkedMapOf<String, OkRuVideo>()
        var adaptiveHlsUrl: String? = null
        var discoveredMetadataUrl: String? = null

        fun addVideos(items: List<OkRuVideo>) {
            items.forEach { video ->
                val normalized = normalizeMediaUrl(video.url)
                if (normalized.isNotBlank()) {
                    videos[normalized] = video.copy(url = normalized)
                }
            }
        }

        fun addMetadata(node: JsonNode) {
            if (adaptiveHlsUrl.isNullOrBlank()) {
                adaptiveHlsUrl = extractAdaptiveHls(node)
                    ?.let(::normalizeMediaUrl)
                    ?.takeIf { it.isNotBlank() }
            }

            addVideos(extractVideos(node))
        }

        val emittedVideoUrls = mutableSetOf<String>()
        suspend fun emitVideos() {
            videos.values.toList().forEach { video ->
                if (!emittedVideoUrls.add(video.url)) return@forEach
                val quality = when (video.name.uppercase()) {
                    "MOBILE" -> "144p"
                    "LOWEST" -> "240p"
                    "LOW" -> "360p"
                    "SD" -> "480p"
                    "HD" -> "720p"
                    "FULL" -> "1080p"
                    "QUAD" -> "1440p"
                    "ULTRA" -> "2160p"
                    else -> video.name
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = video.url,
                        type = INFER_TYPE
                    ) {
                        this.referer = embedUrl
                        this.quality = getQualityFromName(quality)
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl,
                            "Origin" to "https://ok.ru"
                        )
                    }
                )
            }
        }

        suspend fun emitMaster(): Boolean {
            val hlsUrl = adaptiveHlsUrl ?: return false
            callback(newExtractorLink(source = name, name = name, url = hlsUrl, type = ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = requestHeaders(embedUrl)
            })
            return true
        }

        val response = try {
            app.get(embedUrl, referer = referer ?: "https://anichin.moe/",
                headers = requestHeaders(embedUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (response != null) {
            for (element in response.document.select("[data-options]")) {
                val options = parseNode(cleanJsonString(element.attr("data-options"))) ?: continue
                val flashvars = options.get("flashvars") ?: continue
                val metadata = flashvars.get("metadata")
                when {
                    metadata == null || metadata.isNull -> Unit
                    metadata.isTextual -> parseNode(cleanJsonString(metadata.asText()))?.let(::addMetadata)
                    else -> addMetadata(metadata)
                }
                if (emitMaster()) return
                discoveredMetadataUrl = flashvars.get("metadataUrl")?.asText()
                    ?.takeIf { it.isNotBlank() }?.let(::cleanJsonString)?.let(::decodeUrl)
                    ?: discoveredMetadataUrl
            }

            // Check inline HLS even when MP4 videos were already found.
            val normalizedHtml = cleanJsonString(response.text)
            adaptiveHlsUrl = HLS_FIELD_REGEX.find(normalizedHtml)?.groupValues?.getOrNull(1)
                ?.let(::cleanJsonString)?.let(::normalizeMediaUrl)?.takeIf { it.isNotBlank() }
            if (emitMaster()) return
            if (videos.isEmpty()) {
                Regex(""""videos"\s*:\s*(\[[\s\S]*?])""", RegexOption.IGNORE_CASE)
                    .find(normalizedHtml)?.groupValues?.getOrNull(1)
                    ?.let(::parseNode)?.let(::extractVideos)?.let(::addVideos)
            }
        }

        // One shared discovery budget, not a fresh wait for each endpoint.
        // MP4 is emitted only after master discovery has finished or timed out.
        withTimeoutOrNull(
            if (videos.isEmpty()) RECOVERY_METADATA_TIMEOUT_MS else OPTIONAL_METADATA_TIMEOUT_MS
        ) {
            suspend fun readMetadata(url: String, post: Boolean = false): JsonNode? {
                return try {
                    val text = if (post) {
                        app.post(url, data = mapOf("mid" to videoId.orEmpty()),
                            referer = embedUrl, headers = requestHeaders(embedUrl)).text
                    } else {
                        app.get(url, referer = embedUrl, headers = requestHeaders(embedUrl)).text
                    }
                    parseNode(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            discoveredMetadataUrl?.let { readMetadata(it)?.let(::addMetadata) }
            if (!adaptiveHlsUrl.isNullOrBlank()) return@withTimeoutOrNull
            if (videoId != null) {
                readMetadata("https://www.ok.ru/dk?cmd=videoPlayerMetadata", true)?.let(::addMetadata)
            }
        }
        if (emitMaster()) return
        emitVideos()
    }

    private fun requestHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to referer,
            "Origin" to "https://ok.ru"
        )
    }

    private fun extractVideoId(url: String): String? {
        val clean = normalizeUrl(url)

        val patterns = listOf(
            Regex(
                """(?:videoembed|video|moviePlayer)/([\d-]+)""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """[?&](?:st\.mvId|mid)=([\d-]+)""",
                RegexOption.IGNORE_CASE
            )
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(clean)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun normalizeUrl(url: String): String {
        return url
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim()
    }

    private fun normalizeMediaUrl(url: String): String {
        val clean = url
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()

        return when {
            clean.startsWith("//") -> "https:$clean"
            else -> clean
        }
    }

    private fun cleanJsonString(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\&quot;", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
                match.groupValues[1]
                    .toIntOrNull(16)
                    ?.toChar()
                    ?.toString()
                    ?: match.value
            }
            .trim()
    }

    private fun decodeUrl(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun parseNode(value: String): JsonNode? {
        if (value.isBlank()) return null
        return AppUtils.tryParseJson<JsonNode>(value)
    }

    private fun extractAdaptiveHls(node: JsonNode): String? {
        val preferredKeys = listOf(
            "hlsMasterPlaylistUrl",
            "hlsManifestUrl",
            "ondemandHls"
        )

        for (key in preferredKeys) {
            val direct = node.get(key)
                ?.asText()
                ?.takeIf { it.isNotBlank() }

            if (direct != null) {
                return cleanJsonString(direct)
            }

            val nested = node.findValue(key)
                ?.asText()
                ?.takeIf { it.isNotBlank() }

            if (nested != null) {
                return cleanJsonString(nested)
            }
        }

        return null
    }

    private fun extractVideos(node: JsonNode): List<OkRuVideo> {
        val videosNode = when {
            node.isArray -> node

            node.has("videos") ->
                node.get("videos")

            else ->
                node.findValue("videos")
        } ?: return emptyList()

        if (!videosNode.isArray) return emptyList()

        return videosNode.mapNotNull { item ->
            val url = item.get("url")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            OkRuVideo(
                name = item.get("name")
                    ?.asText()
                    .orEmpty(),
                url = url
            )
        }
    }

    private data class OkRuVideo(
        val name: String,
        val url: String
    )

    companion object {
        private const val OPTIONAL_METADATA_TIMEOUT_MS = 1_500L
        private const val RECOVERY_METADATA_TIMEOUT_MS = 4_000L
        private val HLS_FIELD_REGEX = Regex(
            """"(?:hlsMasterPlaylistUrl|hlsManifestUrl|ondemandHls)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )
    }
}

class OkRuSSL : OkRuExtractor() {
    override var mainUrl = "https://ok.ru"
}

class OkRuWWW : OkRuExtractor() {
    override var mainUrl = "https://www.ok.ru"
}

class OkRuHTTP : OkRuExtractor() {
    override var mainUrl = "http://ok.ru"
}

class Odnoklassniki : OkRuExtractor() {
    override var mainUrl = "https://odnoklassniki.ru"
}

class OdnoklassnikiWWW : OkRuExtractor() {
    override var mainUrl = "https://www.odnoklassniki.ru"
}
