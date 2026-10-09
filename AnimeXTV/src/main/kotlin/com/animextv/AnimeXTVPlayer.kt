package com.animextv

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal class AnimeXTVPlayer(private val mainUrl: String) {
    internal fun servers(ani: Int, mal: Int, episode: Int): List<Pair<String, String>> {
        val mega = if (mal > 0) "mal/$mal" else "ani/$ani"
        return listOf(
            "Megaplay" to "https://megaplay.buzz/stream/$mega/$episode/sub",
            "Vidnest AnimePahe" to "https://vidnest.fun/animepahe/$ani/$episode/sub",
            "Vidnest" to "https://vidnest.fun/anime/$ani/$episode/sub",
            "TryEmbed" to "https://tryembed.us.cc/embed/anime/$ani/$episode/sub",
            "FrameXTV" to "https://framextv.tech/embed/anime?id=$ani&type=tv&season=1&episode=$episode&sub_type=sub"
        )
    }

    internal fun sourceFile(node: JsonNode): String? {
        val sources = if (node.path("enc").isTextual && node.path("enc").asText().isNotBlank()) {
            mapper.readTree(AnimeXTVCrypto.decrypt(node.path("enc").asText()))
        } else node.path("sources")
        val file = if (sources.isTextual) sources.asText() else sources.path("file").asText("")
        return file.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private suspend fun nativeMega(url: String, subtitles: (SubtitleFile) -> Unit): List<ExtractorLink> {
        val page = app.get(url, referer = "$mainUrl/")
        val id = page.document.selectFirst("#megaplay-player[data-id]")?.attr("data-id")
            ?.takeIf { it.matches(Regex("[0-9]+")) } ?: return emptyList()
        val root = mapper.readTree(app.get("https://megaplay.buzz/stream/getSources?id=$id&platform=OTHER", referer = url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text)
        if (root.has("error")) return emptyList()
        val file = sourceFile(root) ?: return emptyList()
        root.path("tracks").filter { it.path("kind").asText("captions") in setOf("captions", "subtitles") }.forEach { track ->
            val raw = track.path("file").asText("")
            if (raw.isNotBlank()) subtitles(newSubtitleFile(track.path("label").asText("English"), URI(url).resolve(raw).toString()))
        }
        return listOf(newExtractorLink("AnimeXTV · Megaplay", "Megaplay", AnimeXTVCrypto.signed(file), ExtractorLinkType.M3U8) {
            referer = "https://megaplay.buzz/"
            quality = Qualities.Unknown.value
            headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
        })
    }

    internal suspend fun named(link: ExtractorLink, server: String): ExtractorLink {
        var qualityName = link.quality.takeIf { it > 0 && it != Qualities.Unknown.value }?.let { "${it}p" }
        if (qualityName == null && link.type == ExtractorLinkType.M3U8) {
            qualityName = try {
                withTimeoutOrNull(2_000L) {
                    val response = app.get(link.url, referer = link.referer, headers = link.headers)
                    if (response.code !in 200..299 || !response.text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) return@withTimeoutOrNull null
                    val heights = Regex("#EXT-X-STREAM-INF:[^\\r\\n]*RESOLUTION=[0-9]+x([0-9]+)", RegexOption.IGNORE_CASE)
                        .findAll(response.text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted().toList()
                    when {
                        heights.size > 1 -> "Auto (${heights.first()}–${heights.last()}p)"
                        heights.size == 1 -> "Auto (${heights.first()}p)"
                        else -> null
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
        val resolution = qualityName ?: if (link.type == ExtractorLinkType.M3U8) "Auto (HLS)" else "Unknown"
        return newExtractorLink("AnimeXTV · $server", "$server · Sub · $resolution", link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }

    suspend fun load(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = coroutineScope {
        val node = runCatching { mapper.readTree(data) }.getOrNull() ?: return@coroutineScope false
        if (node.path("audio").asText() != "sub") return@coroutineScope false
        val ani = node.path("ani").asInt()
        val mal = node.path("mal").asInt()
        val episode = node.path("episode").asInt()
        if (ani <= 0 || mal < 0 || episode <= 0) return@coroutineScope false
        val seen = mutableSetOf<String>()
        val subSeen = mutableSetOf<String>()
        val subtitles: (SubtitleFile) -> Unit = { sub ->
            if (synchronized(subSeen) { subSeen.add(sub.url) }) subtitleCallback(sub)
        }
        val slots = Semaphore(3)
        servers(ani, mal, episode).map { (server, url) -> async {
            slots.withPermit {
                try {
                    withTimeoutOrNull(15_000L) {
                        val links = if (server == "Megaplay") nativeMega(url, subtitles) else {
                            // The other website mirrors use CloudStream's available extractors.
                            // Native Megaplay extraction remains independent of those mirrors.
                            val result = mutableListOf<ExtractorLink>()
                            loadExtractor(url, "$mainUrl/", subtitles) { synchronized(result) { result.add(it) } }
                            result.toList()
                        }
                        links.forEach { link ->
                            val result = named(link, server)
                            if (synchronized(seen) { seen.add(link.url + "|" + link.referer) }) callback(result)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w("AnimeXTV", "ANIMEXTV_SERVER_FAILED server=$server reason=${e.javaClass.simpleName}") }
            }
        } }.awaitAll()
        Log.i("AnimeXTV", "ANIMEXTV_LINKS audio=sub servers=5 links=${seen.size} subtitles=${subSeen.size}")
        seen.isNotEmpty()
    }
}
