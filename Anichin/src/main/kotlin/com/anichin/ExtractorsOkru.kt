package com.anichin

import com.fasterxml.jackson.annotation.JsonProperty
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

class OkRuSSL : Odnoklassniki() {
    override var name = "OK.ru"
    override var mainUrl = "https://ok.ru"
}

class OkRuHTTP : Odnoklassniki() {
    override var name = "OK.ru"
    override var mainUrl = "http://ok.ru"
}

open class Odnoklassniki : ExtractorApi() {
    override var name = "OK.ru"
    override var mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeEmbedUrl(url)
        val origin = when {
            embedUrl.contains("odnoklassniki.ru", ignoreCase = true) ->
                "https://odnoklassniki.ru"
            else -> "https://ok.ru"
        }

        val response = try {
            app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val videos = linkedMapOf<String, OkRuVideo>()

        fun addVideos(items: List<OkRuVideo>) {
            items.forEach { video ->
                if (video.url.isNotBlank()) {
                    videos[video.url] = video
                }
            }
        }

        // Current OK.ru player embeds JSON in data-options.
        response.document
            .select("[data-options]")
            .forEach { element ->
                val rawOptions = element.attr("data-options")
                if (rawOptions.isBlank()) return@forEach

                val options = AppUtils.tryParseJson<PlayerOptions>(rawOptions)
                    ?: return@forEach

                val flashvars = options.flashvars ?: return@forEach

                flashvars.metadata
                    ?.let(::cleanJsonString)
                    ?.let { AppUtils.tryParseJson<OkMetadata>(it) }
                    ?.videos
                    ?.let(::addVideos)

                if (videos.isEmpty()) {
                    val metadataUrl = flashvars.metadataUrl
                        ?.let(::cleanJsonString)
                        ?.let(::decodeUrl)

                    if (!metadataUrl.isNullOrBlank()) {
                        val metadataText = try {
                            app.get(
                                metadataUrl,
                                referer = embedUrl,
                                headers = mapOf("User-Agent" to USER_AGENT)
                            ).text
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }

                        metadataText
                            ?.let { AppUtils.tryParseJson<OkMetadata>(it) }
                            ?.videos
                            ?.let(::addVideos)
                    }
                }
            }

        // Fallback for older/current variants that expose "videos" directly
        // in page source instead of a parseable data-options object.
        if (videos.isEmpty()) {
            val normalized = cleanJsonString(response.text)
            val videosJson = Regex(
                """"videos"\s*:\s*(\[[\s\S]*?])""",
                RegexOption.IGNORE_CASE
            ).find(normalized)
                ?.groupValues
                ?.getOrNull(1)

            AppUtils.tryParseJson<List<OkRuVideo>>(videosJson.orEmpty())
                ?.let(::addVideos)
        }

        videos.values.forEach { video ->
            val videoUrl = video.url
                .replace("\\/", "/")
                .let {
                    if (it.startsWith("//")) "https:$it" else it
                }

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
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = "$origin/"
                    this.quality = getQualityFromName(qualityName)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$origin/"
                    )
                }
            )
        }
    }

    private fun normalizeEmbedUrl(url: String): String {
        val fixed = url
            .replace("&amp;", "&")
            .replace("\\/", "/")

        return when {
            fixed.contains("/videoembed/", ignoreCase = true) -> fixed
            fixed.contains("/video/", ignoreCase = true) ->
                fixed.replace("/video/", "/videoembed/")
            else -> fixed
        }
    }

    private fun cleanJsonString(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\&quot;", "\"")
            .replace("\\/", "/")
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

    private data class PlayerOptions(
        @param:JsonProperty("flashvars")
        val flashvars: FlashVars? = null
    )

    private data class FlashVars(
        @param:JsonProperty("metadata")
        val metadata: String? = null,
        @param:JsonProperty("metadataUrl")
        val metadataUrl: String? = null
    )

    private data class OkMetadata(
        @param:JsonProperty("videos")
        val videos: List<OkRuVideo> = emptyList()
    )

    data class OkRuVideo(
        @param:JsonProperty("name")
        val name: String = "",
        @param:JsonProperty("url")
        val url: String = ""
    )
}
