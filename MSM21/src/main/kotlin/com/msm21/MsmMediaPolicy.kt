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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

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
            path.contains("/manifest/") ||
            path.endsWith("/master.m3u") || path.endsWith("/playlist.m3u")
    }

    private data class Checked(val link: ExtractorLink, val state: String)

    private suspend fun check(link: ExtractorLink): Checked {
        if (isRejected(link.url)) return Checked(link, "rejected")
        if (link.type == ExtractorLinkType.VIDEO) return checkVideo(link)
        if (link.type != ExtractorLinkType.M3U8) return Checked(link, "other")
        return try {
            withTimeoutOrNull(3_500L) {
                val response = app.get(link.url, referer = link.referer,
                    headers = link.headers, timeout = 3L)
                val lines = response.text.trim().trimStart('\uFEFF').lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                val state = when {
                    response.code in listOf(400, 401, 403, 404, 410) || isRejected(response.url) -> "rejected"
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
        catch (error: Exception) {
            val dnsFailed = generateSequence<Throwable>(error) { it.cause }.any { it is java.net.UnknownHostException }
            Log.w("MSM21", "MSM21_V23_PROBE_FAILED host=${runCatching { URI(link.url).host }.getOrNull()} error=${error.javaClass.simpleName} rejected=$dnsFailed")
            Checked(link, if (dnsFailed) "rejected" else "unverified")
        }
    }

    private suspend fun checkVideo(link: ExtractorLink): Checked = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(link.url).openConnection() as HttpURLConnection
            connection = conn
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            link.headers.filterKeys { !it.equals("Range", true) }.forEach { (key, value) ->
                conn.setRequestProperty(key, value)
            }
            if (link.referer.isNotBlank()) conn.setRequestProperty("Referer", link.referer)
            conn.setRequestProperty("Range", "bytes=0-511")
            val status = conn.responseCode
            if (isRejected(conn.url.toString()) || status in listOf(400, 401, 403, 404, 410)) {
                Log.w("MSM21", "MSM21_V14_VIDEO_REJECT host=${URI(link.url).host} status=$status")
                return@withContext Checked(link, "rejected")
            }
            if (status !in 200..299) return@withContext Checked(link, "unverified")
            val bytes = ByteArray(512)
            var count = 0
            conn.inputStream.use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read <= 0) break
                    count += read
                }
            }
            val box = if (count >= 8) String(bytes, 4, 4, Charsets.US_ASCII) else ""
            val video = box in listOf("ftyp", "moov", "mdat", "moof", "styp", "sidx", "free", "wide") ||
                (count >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
                    bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) ||
                (count > 188 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte())
            val finalUrl = conn.url.toString()
            Log.i("MSM21", "MSM21_V25_VIDEO_PROBE host=${URI(link.url).host} " +
                "final_host=${URI(finalUrl).host} redirected=${finalUrl != link.url} status=$status " +
                "content_range=${conn.getHeaderField("Content-Range").orEmpty()} " +
                "accept_ranges=${conn.getHeaderField("Accept-Ranges").orEmpty()} video=$video")
            // Reuse the exact URL verified by this request, including signed query bytes.
            // Avoid forwarding a caller-supplied Cookie/Authorization to a different origin.
            val sensitiveHeaders = link.headers.keys.any {
                it.equals("Cookie", true) || it.equals("Authorization", true)
            }
            val sameOrigin = URI(link.url).let { original -> URI(finalUrl).let { final ->
                original.scheme.equals(final.scheme, true) && original.host.equals(final.host, true) &&
                    original.port == final.port
            } }
            val resolved = if (video && finalUrl != link.url && (sameOrigin || !sensitiveHeaders)) {
                newExtractorLink(source = link.source, name = link.name, url = finalUrl, type = link.type) {
                    referer = link.referer
                    headers = link.headers
                    quality = link.quality
                    extractorData = link.extractorData
                    audioTracks = link.audioTracks
                }
            } else link
            Checked(resolved, if (video) "video" else "rejected")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val dnsFailed = generateSequence<Throwable>(error) { it.cause }.any { it is java.net.UnknownHostException }
            Log.w("MSM21", "MSM21_V23_PROBE_FAILED host=${runCatching { URI(link.url).host }.getOrNull()} error=${error.javaClass.simpleName} rejected=$dnsFailed")
            Checked(link, if (dnsFailed) "rejected" else "unverified")
        }
        finally { connection?.disconnect() }
    }

    suspend fun select(links: List<ExtractorLink>, label: String,
        requireVerified: Boolean = true): List<ExtractorLink> = coroutineScope {
        val probes = Semaphore(3)
        val unique = links.distinctBy { it.url }
        val completed = kotlinx.coroutines.channels.Channel<Checked>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val jobs = unique.map { link ->
            async {
                val result = probes.withPermit { check(link) }
                completed.send(result)
            }
        }
        val observed = mutableListOf<Checked>()
        var selected = emptyList<Checked>()
        try {
            while (selected.isEmpty() && observed.size < unique.size) {
                if (selected.isEmpty()) {
                    observed.add(completed.receive())
                    // Drain already completed checks so a ready master wins over a rendition.
                    while (true) {
                        val ready = completed.tryReceive().getOrNull() ?: break
                        observed.add(ready)
                    }
                    val valid = observed.filter { it.state in listOf("master", "media", "video") }
                    val winner = valid.firstOrNull { it.state == "master" } ?: valid.firstOrNull()
                    if (winner != null) selected = listOf(winner)
                    else if (observed.size == unique.size && !requireVerified) {
                        selected = observed.firstOrNull { it.state != "rejected" }?.let { listOf(it) }.orEmpty()
                    }
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            completed.close()
        }
        Log.i("MSM21", "MSM21_V23_SELECT label=$label candidates=${unique.size} " +
            "checked=${observed.size} rejected=${observed.count { it.state == "rejected" }} emitted=${selected.size}")
        selected.map { result ->
            val link = result.link
            newExtractorLink(source = MsmServerLabels.display(label, link.source, link.name),
                name = MsmServerLabels.linkName(label, link.name, result.state == "master", link.quality, link.source),
                url = link.url, type = link.type) {
                referer = link.referer
                headers = link.headers
                quality = if (result.state == "master") Qualities.Unknown.value else link.quality
                extractorData = link.extractorData
                audioTracks = link.audioTracks
            }
        }
    }
}
