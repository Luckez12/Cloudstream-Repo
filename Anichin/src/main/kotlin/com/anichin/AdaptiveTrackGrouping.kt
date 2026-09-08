package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

object AdaptiveTrackGrouping {

    suspend fun findMasterFromPage(
        pageUrl: String,
        referer: String?,
        sourceName: String
    ): ExtractorLink? {
        val response = try {
            app.get(
                pageUrl,
                referer = referer ?: pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 5L
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val raw = normalize(response.text)
        val unpacked = runCatching {
            normalize(getAndUnpack(raw))
        }.getOrDefault(raw)

        val candidates = linkedSetOf<String>()

        HLS_URL.findAll(raw).forEach {
            candidates += cleanUrl(it.value)
        }

        HLS_URL.findAll(unpacked).forEach {
            candidates += cleanUrl(it.value)
        }

        for (candidate in candidates) {
            verifiedNativeMaster(
                url = candidate,
                referer = pageUrl,
                sourceName = sourceName
            )?.let { return it }
        }

        return null
    }

    suspend fun preferMaster(
        links: List<ExtractorLink>,
        sourceName: String
    ): List<ExtractorLink> {
        if (links.isEmpty()) return emptyList()

        val uniqueLinks = links.distinctBy {
            "${it.url}\u0000${it.referer}"
        }

        /*
         * First choice: a real host-provided master HLS.
         */
        for (link in uniqueLinks.filter {
            it.type == ExtractorLinkType.M3U8
        }) {
            verifiedNativeMaster(
                url = link.url,
                referer = link.referer,
                sourceName = sourceName,
                headers = link.headers
            )?.let {
                return listOf(it)
            }
        }

        /*
         * Second choice: some hosts expose one media playlist per quality
         * instead of a master. When at least two valid HLS media playlists
         * exist, build a synthetic HLS master. ExoPlayer then exposes these
         * renditions in Video Tracks under one source.
         */
        buildSyntheticMaster(
            links = uniqueLinks,
            sourceName = sourceName
        )?.let {
            return listOf(it)
        }

        /*
         * Safety fallback: do not lose direct MP4 or non-HLS sources.
         */
        return uniqueLinks
    }

    private suspend fun buildSyntheticMaster(
        links: List<ExtractorLink>,
        sourceName: String
    ): ExtractorLink? {
        val hlsLinks = links.filter {
            it.type == ExtractorLinkType.M3U8
        }

        if (hlsLinks.size < 2) return null

        /*
         * Synthetic variants need one common request context. Extractors for
         * Morencius/Emturbovid normally return the same referer and headers
         * for all qualities. If they differ, keep original links instead.
         */
        val first = hlsLinks.first()
        val commonReferer = first.referer
        val commonHeaders = first.headers

        if (
            hlsLinks.any {
                it.referer != commonReferer ||
                    !sameHeaders(it.headers, commonHeaders)
            }
        ) {
            return null
        }

        val variants = mutableListOf<Variant>()

        for (link in hlsLinks) {
            val playlist = fetchPlaylist(
                url = link.url,
                referer = link.referer,
                headers = link.headers
            ) ?: continue

            if (!playlist.contains("#EXTM3U", ignoreCase = true)) {
                continue
            }

            /*
             * A media playlist is suitable as a child variant.
             * If it is already a master, preferMaster() would have returned it.
             */
            if (
                playlist.contains(
                    "#EXT-X-STREAM-INF",
                    ignoreCase = true
                )
            ) {
                continue
            }

            val height = normalizedHeight(link)
            variants += Variant(
                url = link.url,
                height = height,
                bandwidth = estimatedBandwidth(height)
            )
        }

        val distinctVariants = variants
            .distinctBy { it.url }
            .sortedByDescending { it.height }

        if (distinctVariants.size < 2) return null

        val masterText = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")

            distinctVariants.forEach { variant ->
                val width = estimatedWidth(variant.height)

                append("#EXT-X-STREAM-INF:")
                append("BANDWIDTH=${variant.bandwidth}")

                if (variant.height > 0) {
                    append(",RESOLUTION=${width}x${variant.height}")
                }

                appendLine()
                appendLine(variant.url)
            }
        }

        val encoded = Base64.encodeToString(
            masterText.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val dataUrl =
            "data:application/vnd.apple.mpegurl;base64,$encoded"

        return newExtractorLink(
            source = sourceName,
            name = sourceName,
            url = dataUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = commonReferer
            this.headers = commonHeaders
            this.quality = Qualities.Unknown.value
        }
    }

    private suspend fun verifiedNativeMaster(
        url: String,
        referer: String,
        sourceName: String,
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink? {
        val playlist = fetchPlaylist(
            url = url,
            referer = referer,
            headers = headers
        ) ?: return null

        if (!playlist.contains("#EXTM3U", ignoreCase = true)) {
            return null
        }

        if (
            !playlist.contains(
                "#EXT-X-STREAM-INF",
                ignoreCase = true
            )
        ) {
            return null
        }

        return newExtractorLink(
            source = sourceName,
            name = sourceName,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.headers = headers
            this.quality = Qualities.Unknown.value
        }
    }

    private suspend fun fetchPlaylist(
        url: String,
        referer: String,
        headers: Map<String, String>
    ): String? {
        val requestHeaders = headers.toMutableMap()

        if (
            requestHeaders.keys.none {
                it.equals("User-Agent", true)
            }
        ) {
            requestHeaders["User-Agent"] = USER_AGENT
        }

        return try {
            app.get(
                url,
                referer = referer,
                headers = requestHeaders,
                timeout = 4L
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizedHeight(
        link: ExtractorLink
    ): Int {
        if (link.quality > 0) {
            return link.quality
        }

        val text = "${link.name} ${link.url}"

        return Regex(
            """(?<!\d)(2160|1440|1080|900|720|576|540|480|432|360|270|240|144)p?(?!\d)""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun estimatedWidth(
        height: Int
    ): Int {
        if (height <= 0) return 0

        /*
         * Width is only a player-track label hint. The child media playlist
         * remains the original untouched stream.
         */
        return ((height * 16.0 / 9.0) / 2)
            .toInt()
            .times(2)
    }

    private fun estimatedBandwidth(
        height: Int
    ): Int {
        return when {
            height >= 2160 -> 12_000_000
            height >= 1440 -> 8_000_000
            height >= 1080 -> 5_000_000
            height >= 900 -> 4_000_000
            height >= 720 -> 3_000_000
            height >= 576 -> 2_000_000
            height >= 480 -> 1_500_000
            height >= 360 -> 900_000
            height >= 240 -> 500_000
            height > 0 -> 300_000
            else -> 1_000_000
        }
    }

    private fun sameHeaders(
        first: Map<String, String>,
        second: Map<String, String>
    ): Boolean {
        fun normalized(
            source: Map<String, String>
        ): Map<String, String> {
            return source.entries.associate {
                it.key.lowercase() to it.value
            }
        }

        return normalized(first) == normalized(second)
    }

    private fun normalize(
        value: String
    ): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun cleanUrl(
        value: String
    ): String {
        return normalize(value)
            .trim()
            .trimEnd(')', ']', '}', ',', ';')
    }

    private data class Variant(
        val url: String,
        val height: Int,
        val bandwidth: Int
    )

    private val HLS_URL = Regex(
        """https?://[^"'\\\s<>]+?\.m3u8(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    )
}
