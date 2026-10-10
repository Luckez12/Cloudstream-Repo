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
import java.util.Locale

internal class AnimeXTVPlayer(private val mainUrl: String) {
    internal fun servers(ani: Int, mal: Int, episode: Int): List<Pair<String, String>> {
        val mega = if (mal > 0) "mal/$mal" else "ani/$ani"
        return listOf(
            "MegaPlay" to "https://megaplay.buzz/stream/$mega/$episode/sub",
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
            if (raw.isNotBlank()) subtitles(newSubtitleFile(track.path("label").asText("").ifBlank { track.path("lang").asText("") }, URI(url).resolve(raw).toString()))
        }
        return listOf(newExtractorLink("AnimeXTV · MegaPlay", "MegaPlay", AnimeXTVCrypto.signed(file), ExtractorLinkType.M3U8) {
            referer = "https://megaplay.buzz/"
            quality = Qualities.Unknown.value
            headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
        })
    }

    internal data class Inspected(val link: ExtractorLink, val masterRank: Int, val label: String)

    internal suspend fun inspect(link: ExtractorLink): Inspected {
        if (link.type != ExtractorLinkType.M3U8) return Inspected(link, 0, resolution(link))
        val manifest = try {
            withTimeoutOrNull(2_000L) {
                val response = app.get(link.url, referer = link.referer, headers = link.headers)
                response.text.trimStart('\uFEFF', ' ', '\r', '\n').takeIf {
                    response.code in 200..299 && it.startsWith("#EXTM3U")
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val master = manifest?.contains("#EXT-X-STREAM-INF:") == true || manifest?.contains("#EXT-X-MEDIA:") == true
        val hint = manifest == null && Regex("(?:^|[/=?&])master(?:\\.m3u8|[/&?])", RegexOption.IGNORE_CASE)
            .containsMatchIn(runCatching { java.net.URLDecoder.decode(link.url, "UTF-8") }.getOrDefault(link.url))
        return Inspected(link, if (master) 2 else if (hint) 1 else 0, if (master || hint) "Auto" else resolution(link))
    }
    private fun resolution(link: ExtractorLink) = link.quality.takeIf { it > 0 && it != Qualities.Unknown.value }?.let { "${it}p" } ?: "Unknown"

    internal fun serverLabel(server: String): String = server.removePrefix("Vidnest · ")

    internal fun subtitleLanguage(label: String, url: String): String? {
        val normalized = label.trim().lowercase(Locale.ROOT).replace('_', '-')
        fun language(value: String): String? = when {
            Regex("\\b(english|eng|en)\\b").containsMatchIn(value) -> "English"
            Regex("\\b(malay|melayu|msa|may|ms)\\b").containsMatchIn(value) -> "Malay"
            Regex("\\b(indonesian|indonesia|indo|ind|id)\\b").containsMatchIn(value) -> "Indo"
            else -> null
        }
        language(normalized)?.let { return it }
        // Only infer from a filename when the source supplies no language label.
        if (normalized !in setOf("", "subtitles", "subtitle", "captions", "unknown")) return null
        val filename = runCatching { URI(url).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
            .lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replace('.', ' ')
        return language(filename)
    }

    private suspend fun rename(item: Inspected, server: String, fallback: Int = 0): ExtractorLink {
        val link = item.link
        val label = serverLabel(server)
        val suffix = if (fallback > 0) " · Fallback $fallback" else ""
        return newExtractorLink("AnimeXTV · $label", "$label$suffix · Sub · ${item.label}", link.url, link.type) {
            referer = link.referer
            quality = if (item.masterRank > 0) Qualities.Unknown.value else link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }
    internal suspend fun named(link: ExtractorLink, server: String): ExtractorLink = rename(inspect(link), server)

    internal fun identity(link: ExtractorLink): String {
        val url = runCatching {
            val uri = URI(link.url)
            if (!Regex("/[a-f0-9]{32}/[a-f0-9]{32}/", RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty())) link.url
            else {
                // Our MegaPlay HMAC token renews access to the same path, not the content identity.
                val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && !it.startsWith("token=") }.joinToString("&")
                "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}" + if (query.isBlank()) "" else "?$query"
            }
        }.getOrDefault(link.url)
        return url + "|" + link.referer
    }

    internal suspend fun select(links: List<ExtractorLink>): List<Inspected> = coroutineScope {
        val slots = Semaphore(3)
        val result = links.distinctBy { identity(it) }.take(16).map { async { slots.withPermit { inspect(it) } } }.awaitAll()
        val masters = result.filter { it.masterRank > 0 }.sortedByDescending { it.masterRank }
        // Keep complete master playlists; their individual renditions remain in Auto.
        masters.ifEmpty { result }
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
            val language = subtitleLanguage(sub.lang, sub.url)
            if (language != null && synchronized(subSeen) { subSeen.add(sub.url) }) {
                subtitleCallback(newSubtitleFile(language, sub.url))
            }
        }
        val mirrors = AnimeXTVMirrors()
        val jobs = listOf(
            async { listOf(AnimeXTVBatch("MegaPlay", guarded("MegaPlay") { nativeMega(servers(ani,mal,episode).first().second, subtitles) })) },
            async { mirrors.vidnest(ani, episode, subtitles) },
            async { mirrors.frame(ani, episode, subtitles) },
            async {
                val native = AnimeXTVTryEmbed(mainUrl).load(ani, episode, subtitles)
                if (native.any { it.links.isNotEmpty() }) native else {
                    val links = guarded("TryEmbed") {
                        val result = mutableListOf<ExtractorLink>()
                        loadExtractor(servers(ani,mal,episode)[3].second, "$mainUrl/", subtitles) { synchronized(result) { result.add(it) } }
                        result.toList()
                    }
                    listOf(AnimeXTVBatch("TryEmbed", links))
                }
            }
        )
        // Emit MegaPlay promptly while other servers continue extracting.
        var batches = 0
        for (job in jobs) {
            val group = job.await().groupBy { it.server }.map { (server, list) -> AnimeXTVBatch(server, list.flatMap { it.links }) }
            for (batch in group) {
                batches++
                val links = select(batch.links)
                var emittedMasters = 0
                var emitted = 0
                for (item in links) {
                    val key = identity(item.link)
                    if (!seen.add(key)) continue
                    val fallback = if (item.masterRank > 0) emittedMasters++ else 0
                    callback(rename(item, batch.server, fallback))
                    emitted++
                }
                Log.i("AnimeXTV", "ANIMEXTV_SERVER server=${batch.server.replace(' ', '_')} candidates=${batch.links.size} selected=${links.size} emitted=$emitted masters=${links.count { it.masterRank > 0 }} verified_masters=${links.count { it.masterRank == 2 }}")
            }
        }
        Log.i("AnimeXTV", "ANIMEXTV_LINKS audio=sub groups=$batches links=${seen.size} subtitles=${subSeen.size}")
        seen.isNotEmpty()
    }
    private suspend fun guarded(server: String, block: suspend () -> List<ExtractorLink>): List<ExtractorLink> = try {
        withTimeoutOrNull(15_000L) { block() }.orEmpty()
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w("AnimeXTV", "ANIMEXTV_SERVER_FAILED server=$server reason=${e.javaClass.simpleName}")
        emptyList()
    }
}
