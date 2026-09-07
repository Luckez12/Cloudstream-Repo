package com.anichin

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
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

        fun addVideos(items: List<OkRuVideo>) {
            items.forEach { video ->
                val normalized = normalizeMediaUrl(video.url)
                if (normalized.isNotBlank()) {
                    videos[normalized] = video.copy(url = normalized)
                }
            }
        }

        /*
         * Fast path: read the embed page first. In the working V17 cases this
         * already contains data-options/metadata with every quality, so avoid
         * paying for an extra metadata API request before playback can appear.
         */
        val response = try {
            app.get(
                embedUrl,
                referer = referer ?: "https://anichin.moe/",
                headers = requestHeaders(embedUrl)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (response != null) {
            response.document
                .select("[data-options]")
                .forEach { element ->
                    if (videos.isNotEmpty()) return@forEach

                    val optionsNode = parseNode(
                        cleanJsonString(
                            element.attr("data-options")
                        )
                    ) ?: return@forEach

                    val flashvars = optionsNode.get("flashvars")
                        ?: return@forEach

                    val metadataNode = flashvars.get("metadata")

                    when {
                        metadataNode == null || metadataNode.isNull -> Unit

                        metadataNode.isObject || metadataNode.isArray -> {
                            addVideos(
                                extractVideos(metadataNode)
                            )
                        }

                        metadataNode.isTextual -> {
                            parseNode(
                                cleanJsonString(
                                    metadataNode.asText()
                                )
                            )?.let(::extractVideos)
                                ?.let(::addVideos)
                        }
                    }

                    /*
                     * Only hit metadataUrl when inline metadata did not contain
                     * any renditions.
                     */
                    if (videos.isEmpty()) {
                        val metadataUrl = flashvars
                            .get("metadataUrl")
                            ?.asText()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::cleanJsonString)
                            ?.let(::decodeUrl)

                        if (!metadataUrl.isNullOrBlank()) {
                            val metadataText = try {
                                app.get(
                                    metadataUrl,
                                    referer = embedUrl,
                                    headers = requestHeaders(embedUrl)
                                ).text
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }

                            metadataText
                                ?.let(::parseNode)
                                ?.let(::extractVideos)
                                ?.let(::addVideos)
                        }
                    }
                }

            /*
             * HTML fallback before the separate metadata API.
             */
            if (videos.isEmpty()) {
                val normalizedHtml = cleanJsonString(response.text)

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

        /*
         * Slow fallback only. Keep V17's direct metadata endpoint because it
         * recovered OK.ru on pages where the embed markup was incomplete.
         */
        if (videos.isEmpty() && videoId != null) {
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

            apiText
                ?.let(::parseNode)
                ?.let(::extractVideos)
                ?.let(::addVideos)
        }

        videos.values.forEach { video ->
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
