package com.anichin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

class Geodailymotion : Dailymotion() {
    override val name = "Dailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false

    private val baseUrl = "https://www.dailymotion.com"
    private val videoIdRegex = Regex("""^[A-Za-z0-9]+$""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = getVideoId(url) ?: return
        val embedUrl = "$baseUrl/embed/video/$videoId"
        val metadataUrl = "$baseUrl/player/metadata/video/$videoId"

        val metadataText = try {
            app.get(
                metadataUrl,
                referer = embedUrl
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val metadata = AppUtils.tryParseJson<Metadata>(metadataText)
            ?: return

        /*
         * Dailymotion exposes an "auto" master HLS playlist. Keep the master
         * intact instead of expanding individual fMP4 video renditions.
         * Those rendition manifests can be video-only and cause silent
         * playback in ExoPlayer.
         */
        val autoMaster = metadata.qualities["auto"]
            .orEmpty()
            .firstOrNull { quality ->
                quality.url?.contains(".m3u8", ignoreCase = true) == true
            }
            ?.url

        val fallbackMaster = metadata.qualities
            .values
            .flatten()
            .firstOrNull { quality ->
                quality.url?.contains(".m3u8", ignoreCase = true) == true &&
                    (
                        quality.url.contains("/master", ignoreCase = true) ||
                            quality.url.contains("playlist", ignoreCase = true)
                    )
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

        metadata.subtitles
            ?.data
            .orEmpty()
            .values
            .forEach { subtitle ->
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
        val candidates = listOf(
            Regex("""/embed/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1),
            Regex("""/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1),
            Regex("""[?&]video=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
        )

        return candidates
            .firstOrNull { !it.isNullOrBlank() && it.matches(videoIdRegex) }
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
