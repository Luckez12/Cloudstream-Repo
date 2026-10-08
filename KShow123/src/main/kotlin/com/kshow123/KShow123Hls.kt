package com.kshow123

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal class KShow123Hls {
    internal data class Candidate(val link: ExtractorLink, val original: Boolean = true)
    private val slots = Semaphore(3)

    private fun isHls(url: String): Boolean =
        Regex("""\.m3u8(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    // A filename is only a discovery hint. Every possible master must pass
    // an HTTP/content check before it can replace a working source.
    internal fun siblingUrls(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val file = uri.path.orEmpty().substringAfterLast('/')
        if (!Regex("""(?:chunklist(?:_[\w-]+)?|index|playlist)\.m3u8""", RegexOption.IGNORE_CASE).matches(file)) {
            return emptyList()
        }
        val base = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.substringBeforeLast('/')}/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return listOf("master.m3u8", "playlist.m3u8", "index.m3u8")
            .filterNot { it.equals(file, ignoreCase = true) }
            .map { base + it + query }
    }

    internal fun isMaster(text: String): Boolean {
        val lines = text.trimStart('\uFEFF', ' ', '\n', '\r').lineSequence().map { it.trim() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return false
        // Also require a following variant URI: a tag alone is not sufficient.
        return lines.indices.any { index ->
            lines[index].startsWith("#EXT-X-STREAM-INF:") &&
                lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?.let { it.isNotBlank() && !it.startsWith('<') } == true
        }
    }

    private suspend fun confirmedMaster(link: ExtractorLink): Boolean = slots.withPermit {
        try {
            withTimeoutOrNull(4_000L) {
                val response = app.get(link.url, referer = link.referer, headers = link.headers)
                response.code in 200..299 && isMaster(response.text)
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // Extractors often return one link per quality after expanding a master.
    // Recover the original source URL from the same player page when exposed.
    suspend fun pageCandidates(url: String, referer: String): List<Candidate> {
        return try {
            withTimeoutOrNull(5_000L) {
                val response = app.get(url, referer = referer)
                val scripts = response.document.select("script").joinToString("\n") { it.data() }
                val unpacked = runCatching { getAndUnpack(scripts) }.getOrDefault("")
                Regex("""['"]((?:https?:)?//[^'"\s<>]+\.m3u8[^'"\s<>]*)['"]""", RegexOption.IGNORE_CASE)
                    .findAll((scripts + "\n" + unpacked).replace("\\/", "/"))
                    .map { it.groupValues[1] }.distinct().take(4).toList()
                    .mapNotNull { raw ->
                        val resolved = runCatching { URI(url).resolve(raw).toString() }.getOrNull()
                            ?: return@mapNotNull null
                        if (!isHls(resolved)) return@mapNotNull null
                        Candidate(newExtractorLink("KShow123", "KShow123", resolved, ExtractorLinkType.M3U8) {
                            this.referer = url
                            headers = mapOf("Referer" to url)
                            quality = Qualities.Unknown.value
                        }, original = false)
                    }.distinctBy { it.link.url }.take(4).toList()
            }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun select(label: String, candidates: List<Candidate>): List<ExtractorLink> = coroutineScope {
        val unique = candidates.distinctBy { it.link.url }
        val originals = unique.filter { it.original }.map { it.link }
        val hls = unique.filter { isHls(it.link.url) }.take(8)
        val directMasters = hls.map { candidate ->
            async { candidate.link.takeIf { confirmedMaster(it) } }
        }.awaitAll().filterNotNull()
        // If a source page already exposes a master, don't probe siblings.
        val discovered = if (directMasters.isEmpty()) {
            hls.filter { it.original }.take(2).flatMap { candidate ->
                siblingUrls(candidate.link.url).map { candidate.link.copy(url = it) }
            }.distinctBy { it.url }.take(4).map { possible ->
                async { possible.takeIf { confirmedMaster(it) } }
            }.awaitAll().filterNotNull()
        } else emptyList()
        val masters = (directMasters + discovered).distinctBy { it.url }
        Log.i("KShow123", "KSHOW123_HLS server=$label originals=${originals.size} masters=${masters.size} fallback=${(masters.size - 1).coerceAtLeast(0)}")
        if (masters.isEmpty()) return@coroutineScope originals
        masters.mapIndexed { index, link ->
            link.copy(
                name = "KShow123 · $label · " + if (index == 0) "Master HLS" else "Fallback Master $index",
                quality = Qualities.Unknown.value
            )
        } + originals.filterNot { isHls(it.url) }
    }
}
