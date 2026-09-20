package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI

object FilteredHlsMaster {

    suspend fun create(
        link: ExtractorLink,
        sourceName: String,
        minimumHeight: Int
    ): ExtractorLink? {
        if (link.type != ExtractorLinkType.M3U8) return null
        if (link.url.startsWith("data:", ignoreCase = true)) return null

        val requestHeaders = link.headers.toMutableMap()
        if (requestHeaders.keys.none { it.equals("User-Agent", true) }) {
            requestHeaders["User-Agent"] = USER_AGENT
        }

        val response = try {
            app.get(
                link.url,
                referer = link.referer,
                headers = requestHeaders,
                timeout = 5L
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val filteredText = filterPlaylist(
            playlist = response.text,
            baseUrl = response.url,
            minimumHeight = minimumHeight
        ) ?: return null

        val encoded = Base64.encodeToString(
            filteredText.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        return newExtractorLink(
            source = sourceName,
            name = sourceName,
            url = "data:application/vnd.apple.mpegurl;base64,$encoded",
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = link.referer
            this.headers = requestHeaders
            this.quality = Qualities.Unknown.value
            this.extractorData = link.extractorData
            this.audioTracks = link.audioTracks
        }
    }

    internal fun filterPlaylist(
        playlist: String,
        baseUrl: String,
        minimumHeight: Int
    ): String? {
        if (!playlist.contains("#EXTM3U", ignoreCase = true)) return null
        if (!playlist.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return null

        val lines = playlist
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()

        val variants = mutableListOf<Variant>()
        val mediaLines = linkedSetOf<String>()
        val globalLines = linkedSetOf<String>()

        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()

            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    var uriIndex = index + 1
                    while (uriIndex < lines.size && lines[uriIndex].isBlank()) {
                        uriIndex++
                    }

                    val uri = lines.getOrNull(uriIndex)?.trim().orEmpty()
                    val height = RESOLUTION_HEIGHT
                        .find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                    if (height != null && uri.isNotBlank() && !uri.startsWith("#")) {
                        variants += Variant(
                            infoLine = line,
                            url = absoluteUrl(baseUrl, uri),
                            height = height,
                            bandwidth = BANDWIDTH
                                .find(line)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toLongOrNull()
                                ?: 0L
                        )
                        index = uriIndex
                    }
                }

                line.startsWith("#EXT-X-MEDIA", ignoreCase = true) -> {
                    mediaLines += absoluteUriAttribute(baseUrl, line)
                }

                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true) -> Unit
                line.startsWith("#") -> {
                    globalLines += absoluteUriAttribute(baseUrl, line)
                }
            }

            index++
        }

        val selectedVariants = variants
            .filter { it.height >= minimumHeight }
            .groupBy { it.height }
            .values
            .mapNotNull { sameResolution ->
                sameResolution.maxByOrNull { it.bandwidth }
            }
            .sortedByDescending { it.height }

        if (selectedVariants.isEmpty()) return null

        return buildString {
            appendLine("#EXTM3U")
            globalLines.forEach { appendLine(it) }
            mediaLines.forEach { appendLine(it) }
            selectedVariants.forEach { variant ->
                appendLine(variant.infoLine)
                appendLine(variant.url)
            }
        }
    }

    private fun absoluteUriAttribute(
        baseUrl: String,
        line: String
    ): String {
        return URI_ATTRIBUTE.replace(line) { match ->
            "URI=\"${absoluteUrl(baseUrl, match.groupValues[1])}\""
        }
    }

    private fun absoluteUrl(baseUrl: String, value: String): String {
        if (value.startsWith("data:", ignoreCase = true)) return value

        return try {
            URI(baseUrl).resolve(value).toString()
        } catch (_: Exception) {
            value
        }
    }

    private data class Variant(
        val infoLine: String,
        val url: String,
        val height: Int,
        val bandwidth: Long
    )

    private val RESOLUTION_HEIGHT = Regex(
        """RESOLUTION\s*=\s*\d+x(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private val BANDWIDTH = Regex(
        """(?:^|,)\s*BANDWIDTH\s*=\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private val URI_ATTRIBUTE = Regex(
        """URI\s*=\s*"([^"]+)""",
        RegexOption.IGNORE_CASE
    )
}
