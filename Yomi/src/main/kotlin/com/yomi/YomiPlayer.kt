package com.yomi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale

internal class YomiPlayer(private val mainUrl: String) {
    private suspend fun mega(url: String, subtitles: MutableList<SubtitleFile>): List<ExtractorLink> {
        val page = app.get(url, referer = "$mainUrl/")
        val id = page.document.selectFirst("#megaplay-player[data-id]")?.attr("data-id")
            ?.takeIf { it.matches(Regex("[0-9]+")) } ?: return emptyList()
        val response = app.get("https://megaplay.buzz/stream/getSources?id=$id&platform=OTHER", referer = url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
        if (response.code !in 200..299) return emptyList()
        val root = mapper.readTree(response.text)
        if (root.has("error")) return emptyList()
        val sources = if (root.path("enc").isTextual && root.path("enc").asText().isNotBlank())
            mapper.readTree(YomiCrypto.decrypt(root.path("enc").asText())) else root.path("sources")
        val file = if (sources.isTextual) sources.asText() else sources.path("file").asText("")
        if (!file.startsWith("https://") && !file.startsWith("http://")) return emptyList()
        root.path("tracks").filter { it.path("kind").asText("captions") in setOf("captions", "subtitles") }.forEach { track ->
            val raw = track.path("file").asText("")
            if (raw.isNotBlank()) subtitles.add(SubtitleFile(track.path("label").asText("").ifBlank { track.path("lang").asText("") }, URI(url).resolve(raw).toString()))
        }
        return listOf(newExtractorLink("Yomi", "MegaPlay", YomiCrypto.signed(file), ExtractorLinkType.M3U8) {
            referer = "https://megaplay.buzz/"
            quality = Qualities.Unknown.value
            headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
        })
    }

    private fun label(url: String): String = when (URI(url).host?.lowercase()) {
        "ani.pm" -> "Ani.pm"
        "megaplay.buzz" -> "MegaPlay"
        "tryembed.us.cc" -> "TryEmbed"
        "flixera.co" -> "Flixera"
        "cinextream.cc" -> "Cinextream"
        "nontongo.win" -> "Nontongo"
        else -> URI(url).host.orEmpty()
    }

    private fun language(sub: SubtitleFile): String? {
        val raw = sub.lang.trim().lowercase(Locale.ROOT).replace('_', '-')
        val value = if (raw in setOf("", "subtitles", "subtitle", "captions", "unknown"))
            URI(sub.url).path.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT).replace(Regex("[_.-]"), " ") else raw
        return when {
            Regex("\\b(english|eng|en)\\b").containsMatchIn(value) -> "English"
            Regex("\\b(malay|melayu|msa|may|ms)\\b").containsMatchIn(value) -> "Malay"
            Regex("\\b(indonesian|indonesia|indo|ind|id)\\b").containsMatchIn(value) -> "Indo"
            else -> null
        }
    }

    private suspend fun isMaster(link: ExtractorLink): Boolean {
        if (link.type != ExtractorLinkType.M3U8) return false
        return try {
            val response = withTimeoutOrNull(2_000L) { app.get(link.url, referer = link.referer, headers = link.headers) }
            val body = response?.text?.trimStart('\uFEFF', ' ', '\r', '\n').orEmpty()
            response != null && response.code in 200..299 && body.startsWith("#EXTM3U") &&
                (body.contains("#EXT-X-STREAM-INF:") || body.contains("#EXT-X-I-FRAME-STREAM-INF:") ||
                    (body.contains("#EXT-X-MEDIA:") && !body.contains("#EXTINF:")))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    /** Consume extractor callbacks as they arrive; stop just this producer on a master. */
    private suspend fun registered(url: String, referer: String, subtitles: (SubtitleFile) -> Unit,
                                   accept: suspend (ExtractorLink) -> Boolean) = coroutineScope {
        val events = Channel<ExtractorLink>(Channel.UNLIMITED)
        val producer = launch {
            try {
                withTimeoutOrNull(12_000L) {
                    loadExtractor(url, referer, subtitles) { events.trySend(it) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w("Yomi", "YOMI_NATIVE_FALLBACK server=${label(url)} reason=${e.javaClass.simpleName}")
            } finally { events.close() }
        }
        try {
            for (link in events) if (accept(link)) break
        } finally {
            producer.cancelAndJoin()
            events.cancel()
        }
    }

    suspend fun load(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = coroutineScope {
        val uri = runCatching { URI(data) }.getOrNull() ?: return@coroutineScope false
        if (uri.host != URI(mainUrl).host || !uri.path.matches(Regex("/watch/[a-z0-9-]+-[0-9]+/[0-9]+/?"))) return@coroutineScope false
        val seen = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        val started = mutableSetOf<String>()
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        val outputLock = Any()
        val subtitles: (SubtitleFile) -> Unit = { sub ->
            language(sub)?.let { lang -> synchronized(outputLock) {
                if (seenSubs.add(sub.url)) subtitleCallback(SubtitleFile(lang, sub.url))
            } }
        }
        suspend fun emit(embed: YomiWeb.Embed, link: ExtractorLink, master: Boolean) {
            val qualityLabel = if (master) "Auto" else link.quality.takeIf { it > 0 && it != Qualities.Unknown.value }?.let { "${it}p" } ?: "Unknown"
            val renamed = newExtractorLink("Yomi", "${label(embed.url)} · Sub · $qualityLabel", link.url, link.type) {
                referer = link.referer
                headers = link.headers
                quality = if (master) Qualities.Unknown.value else link.quality
                extractorData = link.extractorData
                audioTracks = link.audioTracks
            }
            synchronized(outputLock) {
                if (seen.add("${label(embed.url)}|${link.url}")) callback(renamed)
            }
        }
        suspend fun resolve(embed: YomiWeb.Embed) {
            val candidates = linkedMapOf<String, ExtractorLink>()
            var masterFound = false
            suspend fun accept(link: ExtractorLink): Boolean {
                if (masterFound) return true
                if (candidates.containsKey(link.url)) return false
                if (isMaster(link)) {
                    masterFound = true
                    candidates.clear()
                    emit(embed, link, true)
                    Log.i("Yomi", "YOMI_SERVER_MASTER server=${label(embed.url)} action=emitted_stop_server")
                    return true
                }
                candidates[link.url] = link
                return false
            }
            try {
                if (URI(embed.url).host == "megaplay.buzz") {
                    val tracks = mutableListOf<SubtitleFile>()
                    val native = try { withTimeoutOrNull(12_000L) { mega(embed.url, tracks) }.orEmpty() }
                        catch (e: CancellationException) { throw e } catch (e: Exception) {
                            Log.w("Yomi", "YOMI_NATIVE_FALLBACK server=${label(embed.url)} reason=${e.javaClass.simpleName}")
                            emptyList()
                        }
                    tracks.forEach(subtitles)
                    for (link in native) if (accept(link)) break
                }
                if (!masterFound) registered(embed.url, data, subtitles, ::accept)
                if (!masterFound) YomiWeb.streams(embed.url, data) { media ->
                    if (media.kind == "subtitle") {
                        subtitles(SubtitleFile(media.language, media.url))
                        false
                    } else {
                        val hls = URI(media.url).path.orEmpty().lowercase().endsWith(".m3u8")
                        val link = newExtractorLink("Yomi", label(embed.url), media.url,
                            if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            referer = media.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: embed.url
                            headers = media.headers
                            quality = Qualities.Unknown.value
                        }
                        accept(link)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w("Yomi", "YOMI_SERVER_FAILED server=${label(embed.url)} reason=${e.javaClass.simpleName}")
            }
            if (!masterFound) {
                // Fallbacks stay private until this server's master search finishes.
                candidates.values.forEach { emit(embed, it, false) }
            }
            Log.i("Yomi", "YOMI_SERVER_RESULT server=${label(embed.url)} audio=sub master=$masterFound fallbacks=${candidates.size}")
        }
        // Start each server at discovery time, without waiting for the other embeds.
        YomiWeb.watch(data, onEmbed = { embed ->
            if (embed.audio == "sub" && started.add(embed.url)) jobs.add(launch { resolve(embed) })
        })
        Log.i("Yomi", "YOMI_PLAYER embeds=${started.size}")
        // Joining only closes the operation; server callbacks have already emitted independently.
        jobs.joinAll()
        Log.i("Yomi", "YOMI_PLAYER_DONE links=${seen.size} subtitles=${seenSubs.size}")
        seen.isNotEmpty()
    }
}
