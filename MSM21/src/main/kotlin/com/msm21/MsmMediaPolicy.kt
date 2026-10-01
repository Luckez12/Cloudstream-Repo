package com.msm21

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/** Apply the same rules to native extractors, WebView requests and JS bridge captures. */
internal object MsmMediaPolicy {
    private val blockedHosts = listOf(
        "mc.yandex.ru", "mc.yandex.com", "google-analytics.com", "googletagmanager.com",
        "googlesyndication.com", "doubleclick.net", "pixel.morphify.com", "pixel.morphify.net", "static.cloudflareinsights.com"
    )

    fun blockedHostsJson(): String = blockedHosts.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    fun isRejected(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return true
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return true
        val host = uri.host?.lowercase() ?: return true
        return blockedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isMediaPath(raw: String): Boolean {
        if (isRejected(raw)) return false
        // A tracker query may contain the entire movie URL. Only inspect the path.
        val path = runCatching { URI(raw).path }.getOrNull().orEmpty().lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm").any(path::endsWith) ||
            path.contains("/sora/") || path.contains("/manifest/") ||
            path.endsWith("/master.m3u") || path.endsWith("/playlist.m3u")
    }

    private data class Checked(val link: ExtractorLink, val state: String)

    private suspend fun check(link: ExtractorLink): Checked {
        if (isRejected(link.url)) return Checked(link, "rejected")
        if (link.type != ExtractorLinkType.M3U8) return Checked(link, "other")
        return try {
            withTimeoutOrNull(3_500L) {
                val response = app.get(link.url, referer = link.referer,
                    headers = link.headers, timeout = 3L)
                val lines = response.text.trim().trimStart('\uFEFF').lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                val state = when {
                    response.code == 404 || response.code == 410 || isRejected(response.url) -> "rejected"
                    response.code !in 200..299 -> "unverified"
                    lines.firstOrNull() != "#EXTM3U" -> "rejected"
                    lines.indices.any { i ->
                        lines[i].startsWith("#EXT-X-STREAM-INF:") &&
                            lines.getOrNull(i + 1)?.takeUnless { it.startsWith("#") }
                                ?.let { runCatching { URI(response.url).resolve(it).scheme }
                                    .getOrNull() in listOf("http", "https") } == true
                    } -> "master"
                    lines.any { it.startsWith("#EXTINF:") } -> "media"
                    else -> "unverified"
                }
                Checked(link, state)
            } ?: Checked(link, "unverified")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Checked(link, "unverified") }
    }

    suspend fun select(links: List<ExtractorLink>, label: String): List<ExtractorLink> = coroutineScope {
        val probes = Semaphore(3)
        val checked = links.distinctBy { it.url }.map { link ->
            async { probes.withPermit { check(link) } }
        }.awaitAll()
        val masters = checked.filter { it.state == "master" }
        val selected = if (masters.isNotEmpty()) listOf(masters.maxBy { it.link.quality })
            else checked.filter { it.state != "rejected" }
        Log.i("MSM21", "MSM21_V12_SELECT label=$label candidates=${checked.size} " +
            "masters=${masters.size} rejected=${checked.count { it.state == "rejected" }} emitted=${selected.size}")
        selected.map { result ->
            val link = result.link
            newExtractorLink(source = label, name = if (result.state == "master") "$label Auto"
                else "$label ${link.name}".trim(), url = link.url, type = link.type) {
                referer = link.referer
                headers = link.headers
                quality = if (result.state == "master") Qualities.Unknown.value else link.quality
                extractorData = link.extractorData
                audioTracks = link.audioTracks
            }
        }
    }
}
