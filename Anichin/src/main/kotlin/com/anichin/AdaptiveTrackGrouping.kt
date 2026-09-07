package com.anichin

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
        HLS_URL.findAll(raw).forEach { candidates += cleanUrl(it.value) }
        HLS_URL.findAll(unpacked).forEach { candidates += cleanUrl(it.value) }

        for (candidate in candidates) {
            verifiedMaster(
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

        for (link in links.filter { it.type == ExtractorLinkType.M3U8 }) {
            verifiedMaster(
                url = link.url,
                referer = link.referer,
                sourceName = sourceName,
                headers = link.headers
            )?.let { return listOf(it) }
        }

        return links
    }

    private suspend fun verifiedMaster(
        url: String,
        referer: String,
        sourceName: String,
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink? {
        val requestHeaders = headers.toMutableMap()
        if (requestHeaders.keys.none { it.equals("User-Agent", true) }) {
            requestHeaders["User-Agent"] = USER_AGENT
        }

        val playlist = try {
            app.get(
                url,
                referer = referer,
                headers = requestHeaders,
                timeout = 4L
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        if (!playlist.contains("#EXTM3U", ignoreCase = true)) return null
        if (!playlist.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return null

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

    private fun normalize(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun cleanUrl(value: String): String {
        return normalize(value)
            .trim()
            .trimEnd(')', ']', '}', ',', ';')
    }

    private val HLS_URL = Regex(
        """https?://[^"'\\\s<>]+?\.m3u8(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    )
}
