package com.msm21

import android.util.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal data class MsmNativeHlsResult(
    val masters: List<ExtractorLink>,
    val media: List<ExtractorLink>
)

/** Only follow source URLs actually advertised by the player. Never guess master paths. */
internal object MsmNativeHlsDiscovery {
    private val sourceField = Regex(
        """(?i)["']?(?:hls\d*|file|src|source|url)["']?\s*[:=]\s*["']([^"'\s<>]+)["']"""
    )

    private fun clean(value: String): String = value.replace("\\/", "/")
        .replace("\\u0026", "&").replace("\\u003d", "=").replace("&amp;", "&")

    private fun sourceUrls(text: String, base: String): List<String> =
        sourceField.findAll(text).mapNotNull { match ->
            val raw = clean(match.groupValues[1])
            runCatching { URI(base).resolve(raw) }.getOrNull()?.takeIf {
                it.scheme in listOf("http", "https") &&
                    (it.path.orEmpty().endsWith(".m3u8", true) ||
                        match.value.trimStart().startsWith("hls", true) ||
                        match.value.trimStart(' ', '\"', '\'').startsWith("hls", true))
            }?.toString()
        }.distinct().take(8).toList()

    suspend fun discover(url: String, referer: String): MsmNativeHlsResult {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val name = when {
            host.contains("dood") || host in listOf("dsvplay.com", "vide0.net") -> "DoodStream"
            host == "voe.sx" -> "Voe"
            host.contains("hgcloud") || host in listOf("hglink.to", "dhcplay.com", "gradehgplus.com",
                "hanerix.com", "audinifer.com", "vibuxer.com") -> "HGCloud"
            host.contains("streamtape") -> "StreamTape"
            else -> host
        }
        val masters = mutableListOf<ExtractorLink>()
        val media = mutableListOf<ExtractorLink>()
        try {
            withTimeoutOrNull(8_000L) {
                val response = app.get(url, referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT), timeout = 4L)
                Log.i("MSM21", "MSM21_V12_NATIVE_PAGE host=$host http=${response.code}")
                if (response.text.contains("_cf_chl_opt") ||
                    response.text.contains("challenges.cloudflare.com")) {
                    Log.w("MSM21", "MSM21_V12_NATIVE_BLOCKED host=$host kind=cloudflare")
                    return@withTimeoutOrNull
                }
                if (response.code !in 200..299) return@withTimeoutOrNull
                val scripts = response.document.select("script").joinToString("\n") { it.data() }
                val unpacked = runCatching { getAndUnpack(response.text) }.getOrDefault("")
                val text = response.text + "\n" + scripts + "\n" + unpacked
                val candidates = sourceUrls(text, response.url).toMutableSet()
                response.document.select("video[src], video source[src], source[type*=mpegurl]").forEach {
                    val raw = it.attr("src")
                    val absolute = runCatching { URI(response.url).resolve(clean(raw)) }.getOrNull()
                    if (absolute?.scheme in listOf("http", "https") &&
                        absolute?.path.orEmpty().endsWith(".m3u8", true)) {
                        candidates.add(absolute.toString())
                    }
                }
                Log.i("MSM21", "MSM21_V12_NATIVE_SCAN host=$host candidates=${candidates.size}")
                val limiter = Semaphore(3)
                // Complete each probe independently; already verified results survive a local timeout.
                coroutineScope {
                    candidates.filterNot { MsmMediaPolicy.isRejected(it) }.take(8).map { candidate -> async {
                        limiter.withPermit {
                            try {
                                withTimeoutOrNull(3_000L) {
                                    val headers = mapOf("User-Agent" to USER_AGENT,
                                        "Origin" to URI(response.url).let { "${it.scheme}://${it.authority}" })
                                    val playlist = app.get(candidate, referer = response.url,
                                        headers = headers, timeout = 3L)
                                    val lines = playlist.text.trim().trimStart('\uFEFF').lineSequence()
                                        .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                                    val master = lines.firstOrNull() == "#EXTM3U" &&
                                        lines.indices.any { i ->
                                            lines[i].startsWith("#EXT-X-STREAM-INF:") &&
                                                lines.getOrNull(i + 1)?.takeUnless { it.startsWith("#") }
                                                    ?.let { runCatching { URI(playlist.url).resolve(it).scheme }
                                                        .getOrNull() in listOf("http", "https") } == true
                                        }
                                    val validMedia = lines.firstOrNull() == "#EXTM3U" &&
                                        lines.any { it.startsWith("#EXTINF:") }
                                    if (playlist.code in 200..299 && (master || validMedia)) {
                                        val link = newExtractorLink(source = name, name = name,
                                            url = candidate, type = ExtractorLinkType.M3U8) {
                                            this.referer = response.url
                                            this.headers = headers
                                            this.quality = Qualities.Unknown.value
                                        }
                                        synchronized(masters) {
                                            if (master) masters.add(link) else media.add(link)
                                        }
                                    }
                                    Log.i("MSM21", "MSM21_V12_NATIVE_PROBE host=$host http=${playlist.code} master=$master media=$validMedia")
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                Log.w("MSM21", "MSM21_V12_NATIVE_PROBE_FAILED host=$host error=${e.javaClass.simpleName}")
                            }
                        }
                    } }.awaitAll()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w("MSM21", "MSM21_V12_NATIVE_FAILED host=$host error=${e.javaClass.simpleName}")
        }
        Log.i("MSM21", "MSM21_V12_NATIVE_DONE host=$host masters=${masters.size} media=${media.size}")
        return MsmNativeHlsResult(masters.toList(), media.toList())
    }
}
