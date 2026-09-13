package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class AnichinStream : ExtractorApi() {
    override var name = "AnichinStream"
    override var mainUrl = "https://anichin.stream"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("[?&]id=([^&]+)")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val videoUrl = "$mainUrl/hls/$id.m3u8"

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }
}

class AnichinPlayer : ExtractorApi() {
    override var name = "AnichinPlayer"
    override var mainUrl = "https://anichin-player.web.id"
    override val requiresReferer = true

    private fun resolveUrl(raw: String): String? {
        val value = raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        if (value.isBlank()) return null

        return try {
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> URI("$mainUrl/")
                    .resolve(value)
                    .toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate"
    )

    private fun mediaType(url: String): ExtractorLinkType? = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        url.contains(".mp4", true) -> ExtractorLinkType.VIDEO
        else -> null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val referers = listOfNotNull(
            referer?.takeIf { it.isNotBlank() },
            "https://anichin.moe/",
            "https://anichin.care/"
        ).distinct()

        for (effectiveReferer in referers) {
            val response = runCatching {
                app.get(
                    url,
                    referer = effectiveReferer,
                    headers = browserHeaders()
                )
            }.getOrNull() ?: continue

            val nestedUrls = mutableListOf<String>()

            response.document
                .select(
                    "iframe[src], iframe[data-src], video[src], " +
                        "video source[src], source[src], " +
                        "[data-video], [data-src], [data-embed], [data-url], [data-player]"
                )
                .forEach { element ->
                    listOf(
                        element.attr("src"),
                        element.attr("data-src"),
                        element.attr("data-video"),
                        element.attr("data-embed"),
                        element.attr("data-url"),
                        element.attr("data-player")
                    ).forEach { raw ->
                        resolveUrl(raw)?.let(nestedUrls::add)
                    }
                }

            val normalizedText = response.text
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("&amp;", "&")

            Regex(
                """https?://[^\s\"'<>\\]+""",
                RegexOption.IGNORE_CASE
            ).findAll(normalizedText).forEach { match ->
                val candidate = match.value
                    .replace("\\/", "/")
                    .trimEnd(')', ']', '}', ',', ';')

                if (
                    mediaType(candidate) != null ||
                    listOf(
                        "ok.ru", "dailymotion", "rumble", "dood", "streamwish",
                        "streamruby", "vidhide", "morencius", "emturbovid",
                        "odysee", "odycdn", "mixdrop", "filemoon", "streamtape"
                    ).any { candidate.contains(it, true) }
                ) {
                    resolveUrl(candidate)?.let(nestedUrls::add)
                }
            }

            var emitted = false

            nestedUrls
                .distinct()
                .forEach { playerUrl ->
                    val directType = mediaType(playerUrl)

                    if (directType != null) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = playerUrl,
                                type = directType
                            ) {
                                this.referer = url
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        emitted = true
                    } else {
                        runCatching {
                            loadExtractor(
                                playerUrl,
                                url,
                                subtitleCallback
                            ) { link ->
                                emitted = true
                                callback(link)
                            }
                        }
                    }
                }

            if (emitted) return
        }
    }
}
