package com.anichin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

class Geodailymotion : Dailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false

    private val baseUrl = "https://www.dailymotion.com"
    private val videoIdRegex = "^[a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val metadataUrl = "$baseUrl/player/metadata/video/$id"
        val metadata = AppUtils.tryParseJson<Metadata>(
            app.get(metadataUrl, referer = embedUrl).text
        ) ?: return

        metadata.qualities
            .values
            .flatten()
            .mapNotNull { it.url }
            .filter { it.contains(".m3u8", ignoreCase = true) }
            .distinct()
            .forEach { streamUrl ->
                generateM3u8(name, streamUrl, embedUrl).forEach(callback)
            }

        metadata.subtitles
            ?.data
            .orEmpty()
            .values
            .forEach { subtitle ->
                subtitle.urls.distinct().forEach { subtitleUrl ->
                    subtitleCallback(newSubtitleFile(subtitle.label, subtitleUrl))
                }
            }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (!url.contains("geo.dailymotion.com")) return null

        val videoId = url
            .substringAfter("video=", missingDelimiterValue = "")
            .substringBefore("&")
            .substringBefore("#")

        return videoId
            .takeIf { it.matches(videoIdRegex) }
            ?.let { "$baseUrl/embed/video/$it" }
    }

    private fun getVideoId(url: String): String? {
        val id = url
            .substringAfter("/video/", missingDelimiterValue = "")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore("/")

        return if (id.matches(videoIdRegex)) id else null
    }

    private data class Metadata(
        @param:JsonProperty("qualities")
        val qualities: Map<String, List<Quality>> = emptyMap(),
        @param:JsonProperty("subtitles")
        val subtitles: SubtitlesWrapper? = null
    )

    private data class Quality(
        @param:JsonProperty("url")
        val url: String? = null
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
