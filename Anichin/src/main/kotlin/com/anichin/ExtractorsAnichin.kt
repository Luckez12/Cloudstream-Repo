package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
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
        val value = raw.trim()
        if (value.isBlank()) return null

        return try {
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") ||
                    value.startsWith("https://") -> value
                else -> URI("$mainUrl/")
                    .resolve(value)
                    .toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = try {
            app.get(
                url,
                referer = "$mainUrl/"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        response.document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .let(::resolveUrl)
            }
            .distinct()
            .forEach { playerUrl ->
                try {
                    loadExtractor(
                        playerUrl,
                        url,
                        subtitleCallback,
                        callback
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try remaining nested players.
                }
            }
    }
}
