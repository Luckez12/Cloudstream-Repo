package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

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

        val scriptData = response.document
            .selectFirst("script:containsData(mp4)")
            ?.data()
            ?.substringAfter("{\"mp4", missingDelimiterValue = "")
            ?.substringBefore("\"evt\":{")
            .orEmpty()

        if (scriptData.isBlank()) return

        val urlRegex = """\"url\":\"(.*?)\""".toRegex()
        val processedUrls = mutableSetOf<String>()

        for (match in urlRegex.findAll(scriptData)) {
            val rawUrl = match.groupValues.getOrNull(1).orEmpty()
            if (rawUrl.isBlank()) continue

            val cleanedUrl = rawUrl
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .trim()

            if (!cleanedUrl.startsWith("http://", ignoreCase = true) &&
                !cleanedUrl.startsWith("https://", ignoreCase = true)
            ) continue

            // Rumble HLS can be served from CDN domains and can include query strings.
            if (!cleanedUrl.contains(".m3u8", ignoreCase = true)) continue
            if (!processedUrls.add(cleanedUrl)) continue

            val playlist = runCatching {
                app.get(cleanedUrl, referer = url)
            }.getOrNull() ?: continue

            if (!playlist.text.contains("#EXTM3U", ignoreCase = true)) continue

            callback(
                newExtractorLink(
                    this@Rumble.name,
                    "Rumble",
                    cleanedUrl,
                    ExtractorLinkType.M3U8
                )
            )

            // One verified HLS master/media playlist is enough for this Rumble page.
            return
        }
    }
}
