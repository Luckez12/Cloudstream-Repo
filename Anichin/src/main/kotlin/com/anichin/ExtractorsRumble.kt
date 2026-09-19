package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

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
        val response = runCatching {
            app.get(url, referer = referer ?: "$mainUrl/")
        }.getOrNull() ?: return

        val normalizedText = response.text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")

        val hlsUrls = HLS_URL
            .findAll(normalizedText)
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

            val streamHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url
            )

            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = hlsUrl,
                referer = url,
                headers = streamHeaders,
                name = name
            ).forEach(callback)

            // One verified playlist is enough; generateM3u8 expands its
            // 720p/1080p variants and also keeps the adaptive master.
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
