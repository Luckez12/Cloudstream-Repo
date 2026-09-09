package com.animexin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import java.net.URLDecoder

open class AnimexinDailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false

    private val videoIdRegex = Regex("""^[A-Za-z0-9]+$""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = getVideoId(url) ?: return
        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"

        val metadataText = try {
            app.get(metadataUrl, referer = embedUrl).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val metadata = AppUtils.tryParseJson<Metadata>(metadataText) ?: return

        val autoMaster = metadata.qualities["auto"]
            .orEmpty()
            .firstOrNull { it.url?.contains(".m3u8", ignoreCase = true) == true }
            ?.url

        val fallbackMaster = metadata.qualities.values
            .flatten()
            .firstOrNull {
                it.url?.contains(".m3u8", ignoreCase = true) == true &&
                    (it.url.contains("/master", ignoreCase = true) ||
                        it.url.contains("playlist", ignoreCase = true))
            }
            ?.url

        val streamUrl = autoMaster ?: fallbackMaster ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )

        metadata.subtitles?.data.orEmpty().values.forEach { subtitle ->
            subtitle.urls
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { subtitleUrl ->
                    subtitleCallback(
                        newSubtitleFile(
                            subtitle.label.ifBlank { "Unknown" },
                            subtitleUrl
                        )
                    )
                }
        }
    }

    private fun getVideoId(url: String): String? {
        return listOf(
            Regex("""/embed/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1),
            Regex("""/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1),
            Regex("""[?&]video=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() && it.matches(videoIdRegex) }
    }

    private data class Metadata(
        @param:JsonProperty("qualities")
        val qualities: Map<String, List<Quality>> = emptyMap(),
        @param:JsonProperty("subtitles")
        val subtitles: SubtitlesWrapper? = null
    )

    private data class Quality(
        @param:JsonProperty("url")
        val url: String? = null,
        @param:JsonProperty("type")
        val type: String? = null
    )

    private data class SubtitlesWrapper(
        @param:JsonProperty("data")
        val data: Map<String, SubtitleData> = emptyMap()
    )

    private data class SubtitleData(
        @param:JsonProperty("label")
        val label: String = "Unknown",
        @param:JsonProperty("urls")
        val urls: List<String> = emptyList()
    )
}

class AnimexinGeoDailymotion : AnimexinDailymotion() {
    override val mainUrl = "https://geo.dailymotion.com"
}

class AnimexinRumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(url, referer = referer ?: "$mainUrl/")
        }.getOrNull() ?: return

        val normalizedText = response.text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")

        val hlsUrls = HLS_URL.findAll(normalizedText)
            .map { it.value.trim() }
            .distinct()
            .sortedBy { if (it.contains("/hls-vod/", true)) 0 else 1 }
            .toList()

        for (hlsUrl in hlsUrls) {
            val playlist = runCatching {
                app.get(
                    hlsUrl,
                    referer = url,
                    headers = mapOf("User-Agent" to USER_AGENT)
                )
            }.getOrNull() ?: continue

            if (!playlist.text.contains("#EXTM3U", ignoreCase = true)) continue

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            return
        }
    }

    companion object {
        private val HLS_URL = Regex(
            """https?://[^\"'\\\s]+?\.m3u8[^\"'\\\s]*""",
            RegexOption.IGNORE_CASE
        )
    }
}

open class AnimexinOkRuExtractor : ExtractorApi() {
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
        var metadataUrl: String? = null

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

        val response = try {
            app.get(
                embedUrl,
                referer = referer ?: "https://animexin.dev/",
                headers = requestHeaders(embedUrl)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (response != null) {
            response.document.select("[data-options]").forEach { element ->
                val optionsNode = parseNode(cleanJsonString(element.attr("data-options")))
                    ?: return@forEach
                val flashvars = optionsNode.get("flashvars") ?: return@forEach
                val metadataNode = flashvars.get("metadata")

                when {
                    metadataNode == null || metadataNode.isNull -> Unit
                    metadataNode.isObject || metadataNode.isArray -> addMetadata(metadataNode)
                    metadataNode.isTextual -> parseNode(cleanJsonString(metadataNode.asText()))?.let(::addMetadata)
                }

                metadataUrl = flashvars.get("metadataUrl")
                    ?.asText()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::cleanJsonString)
                    ?.let(::decodeUrl)
                    ?: metadataUrl

                if (adaptiveHlsUrl.isNullOrBlank() && !metadataUrl.isNullOrBlank()) {
                    val metadataText = try {
                        app.get(
                            metadataUrl!!,
                            referer = embedUrl,
                            headers = requestHeaders(embedUrl)
                        ).text
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    metadataText?.let(::parseNode)?.let(::addMetadata)
                }
            }

            if (videos.isEmpty() && adaptiveHlsUrl.isNullOrBlank()) {
                val normalizedHtml = cleanJsonString(response.text)
                adaptiveHlsUrl = HLS_FIELD_REGEX.find(normalizedHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::cleanJsonString)
                    ?.let(::normalizeMediaUrl)
                    ?.takeIf { it.isNotBlank() }

                Regex(
                    """"videos"\s*:\s*(\[[\s\S]*?])""",
                    RegexOption.IGNORE_CASE
                ).find(normalizedHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::parseNode)
                    ?.let(::extractVideos)
                    ?.let(::addVideos)
            }
        }

        if (adaptiveHlsUrl.isNullOrBlank() && videoId != null) {
            val apiText = try {
                app.post(
                    "https://www.ok.ru/dk?cmd=videoPlayerMetadata",
                    data = mapOf("mid" to videoId),
                    referer = embedUrl,
                    headers = requestHeaders(embedUrl)
                ).text
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            apiText?.let(::parseNode)?.let(::addMetadata)
        }

        adaptiveHlsUrl?.let { hlsUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to "https://ok.ru"
                    )
                }
            )
            return
        }

        videos.values.forEach { video ->
            val qualityName = when (video.name.uppercase()) {
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
                    this.quality = getQualityFromName(qualityName)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to "https://ok.ru"
                    )
                }
            )
        }
    }

    private fun requestHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to referer,
        "Origin" to "https://ok.ru"
    )

    private fun extractVideoId(url: String): String? {
        val clean = normalizeUrl(url)
        return listOf(
            Regex("""(?:videoembed|video|moviePlayer)/([\d-]+)""", RegexOption.IGNORE_CASE),
            Regex("""[?&](?:st\.mvId|mid)=([\d-]+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex ->
            regex.find(clean)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
    }

    private fun normalizeUrl(url: String): String = url
        .replace("&amp;", "&")
        .replace("\\/", "/")
        .trim()

    private fun normalizeMediaUrl(url: String): String {
        val clean = url
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
        return if (clean.startsWith("//")) "https:$clean" else clean
    }

    private fun cleanJsonString(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("\\&quot;", "\"")
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
        .trim()

    private fun decodeUrl(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun parseNode(value: String): JsonNode? {
        if (value.isBlank()) return null
        return AppUtils.tryParseJson<JsonNode>(value)
    }

    private fun extractAdaptiveHls(node: JsonNode): String? {
        for (key in listOf("hlsMasterPlaylistUrl", "hlsManifestUrl", "ondemandHls")) {
            node.get(key)?.asText()?.takeIf { it.isNotBlank() }?.let { return cleanJsonString(it) }
            node.findValue(key)?.asText()?.takeIf { it.isNotBlank() }?.let { return cleanJsonString(it) }
        }
        return null
    }

    private fun extractVideos(node: JsonNode): List<OkRuVideo> {
        val videosNode = when {
            node.isArray -> node
            node.has("videos") -> node.get("videos")
            else -> node.findValue("videos")
        } ?: return emptyList()

        if (!videosNode.isArray) return emptyList()

        return videosNode.mapNotNull { item ->
            val mediaUrl = item.get("url")?.asText()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            OkRuVideo(
                name = item.get("name")?.asText().orEmpty(),
                url = mediaUrl
            )
        }
    }

    private data class OkRuVideo(val name: String, val url: String)

    companion object {
        private val HLS_FIELD_REGEX = Regex(
            """"(?:hlsMasterPlaylistUrl|hlsManifestUrl|ondemandHls)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )
    }
}

class AnimexinOkRuSSL : AnimexinOkRuExtractor() {
    override var mainUrl = "https://ok.ru"
}

class AnimexinOkRuWWW : AnimexinOkRuExtractor() {
    override var mainUrl = "https://www.ok.ru"
}

class AnimexinOkRuHTTP : AnimexinOkRuExtractor() {
    override var mainUrl = "http://ok.ru"
}

class AnimexinOdnoklassniki : AnimexinOkRuExtractor() {
    override var mainUrl = "https://odnoklassniki.ru"
}

class AnimexinOdnoklassnikiWWW : AnimexinOkRuExtractor() {
    override var mainUrl = "https://www.odnoklassniki.ru"
}
