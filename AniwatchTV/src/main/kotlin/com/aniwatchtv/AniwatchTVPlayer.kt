package com.aniwatchtv

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jsoup.Jsoup
import java.net.URI

internal class AniwatchTVPlayer(private val base: String) {
    private fun site(url: String): String = when (URI(url).host?.lowercase()) {
        "megaplay.su" -> "MegaPlay.su"
        "vidmoly.org", "vidmoly.net", "vidmoly.to" -> "Vidmoly"
        else -> URI(url).host.orEmpty()
    }
    private suspend fun master(link: ExtractorLink): Boolean {
        if (link.type != ExtractorLinkType.M3U8) return false
        val response = try { withTimeoutOrNull(4_000L) { app.get(link.url, referer = link.referer, headers = link.headers) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val body = response?.text?.trimStart('\uFEFF', ' ', '\n', '\r').orEmpty()
        return response != null && response.code in 200..299 && body.startsWith("#EXTM3U") &&
            (body.contains("#EXT-X-STREAM-INF:") || body.contains("#EXT-X-I-FRAME-STREAM-INF:") ||
                body.contains("#EXT-X-MEDIA:") && !body.contains("#EXTINF:"))
    }
    private suspend fun native(url: String, referer: String, subs: (SubtitleFile) -> Unit): List<ExtractorLink> {
        val host = URI(url).host.orEmpty()
        if (host !in setOf("megaplay.su", "vidmoly.org", "vidmoly.net", "vidmoly.to")) return emptyList()
        val response = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT))
        Log.i("AniwatchTV.ro", "ANIWATCH_EMBED server=${site(url)} status=${response.code}")
        if (response.code !in 200..299) return emptyList()
        val html = if (response.text.contains("eval(function(p,a,c,k,e")) getAndUnpack(response.text) else response.text
        val files = Regex("(?:file|src)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']").findAll(html).mapNotNull { match ->
            runCatching { URI(url).resolve(match.groupValues[1].replace("\\/", "/")) }.getOrNull()
                ?.takeIf { it.scheme in setOf("http", "https") && it.path.lowercase().endsWith(".m3u8") }?.toString()
        }.distinct().toList()
        val transport = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
        Regex("tracks\\s*:\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { match ->
            runCatching { mapper.readTree(match.groupValues[1]) }.getOrNull()?.forEach { track ->
                val raw = track.path("file").asText("")
                if (track.path("kind").asText() in setOf("captions", "subtitles") && raw.isNotBlank()) {
                    val target = runCatching { URI(url).resolve(raw).toString() }.getOrNull()
                    if (target != null) subs(SubtitleFile(track.path("label").asText("").ifBlank { track.path("lang").asText() }, target).apply { headers = transport })
                }
            }
        }
        return files.map { file -> newExtractorLink(site(url), site(url), file, ExtractorLinkType.M3U8) {
            this.referer = url; headers = transport; quality = Qualities.Unknown.value
        } }
    }

    suspend fun load(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = coroutineScope {
        val episode = runCatching { mapper.readTree(data) }.getOrNull() ?: return@coroutineScope false
        val id = episode.path("id").asInt()
        val referer = episode.path("url").asText()
        if (id <= 0 || runCatching { URI(referer).host }.getOrNull() != URI(base).host) return@coroutineScope false
        val response = withTimeout(15_000L) { app.get("$base/wp-json/v1/otakuthemes/episode/servers?episodeId=$id", referer = referer) }
        if (response.code !in 200..299) throw ErrorLoadingException("AniwatchTV: server HTTP ${response.code}")
        val root = mapper.readTree(response.text)
        if (!root.path("status").asBoolean()) throw ErrorLoadingException("AniwatchTV: server list unavailable")
        val servers = Jsoup.parse(root.path("html").asText()).select(".server-item[data-type=sub][data-hash]")
            .mapNotNull { el -> runCatching { String(Base64.decode(el.attr("data-hash"), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                ?.takeIf { runCatching { URI(it).scheme in setOf("http", "https") && !URI(it).host.isNullOrBlank() }.getOrDefault(false) } }
            .distinct()
        Log.i("AniwatchTV.ro", "ANIWATCH_SERVERS episode_id=$id sub=${servers.size}")
        val lock = Any()
        val emitted = mutableSetOf<String>()
        val captions = Channel<SubtitleFile>(Channel.UNLIMITED)
        val validator = AniwatchTVSubtitles(subtitleCallback)
        val workers = List(3) { launch { for (sub in captions) validator.accept(sub) } }
        val jobs = servers.map { url -> launch {
            val candidates = linkedMapOf<String, ExtractorLink>()
            var found = false
            val subs: (SubtitleFile) -> Unit = { sub ->
                val raw = sub.lang.lowercase().replace('_', '-')
                val language = when {
                    Regex("\\b(english|eng|en)\\b").containsMatchIn(raw) -> "English"
                    Regex("\\b(malay|melayu|msa|may|ms)\\b").containsMatchIn(raw) -> "Malay"
                    Regex("\\b(indonesian|indonesia|indo|ind|id)\\b").containsMatchIn(raw) -> "Indo"
                    else -> null
                }
                if (language != null) captions.trySend(SubtitleFile(language, sub.url).apply {
                    headers = sub.headers?.toMap() ?: mapOf("Referer" to url, "User-Agent" to USER_AGENT)
                })
            }
            suspend fun emit(link: ExtractorLink, isMaster: Boolean) {
                val quality = if (isMaster) "Auto" else link.quality.takeIf { it > 0 && it != Qualities.Unknown.value }?.let { "${it}p" } ?: "Unknown"
                val output = newExtractorLink(site(url), "${site(url)} · Sub · $quality", link.url, link.type) {
                    this.referer = link.referer; headers = link.headers; this.quality = if (isMaster) Qualities.Unknown.value else link.quality
                    extractorData = link.extractorData; audioTracks = link.audioTracks
                }
                synchronized(lock) { if (emitted.add("${site(url)}|${link.url}")) callback(output) }
            }
            suspend fun accept(link: ExtractorLink): Boolean {
                if (found) return true
                val old = candidates[link.url]
                if (old != null && old.headers == link.headers && old.referer == link.referer) return false
                if (master(link)) {
                    found = true; candidates.clear(); emit(link, true)
                    Log.i("AniwatchTV.ro", "ANIWATCH_MASTER server=${site(url)} action=stop_server")
                    return true
                }
                candidates[link.url] = link
                return false
            }
            try {
                val nativeLinks = withTimeoutOrNull(12_000L) { native(url, referer, subs) }.orEmpty()
                for (link in nativeLinks) if (accept(link)) break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AniwatchTV.ro", "ANIWATCH_NATIVE_FAILED server=${site(url)} reason=${e.javaClass.simpleName}") }
            if (!found) coroutineScope {
                val links = Channel<ExtractorLink>(Channel.UNLIMITED)
                val producer = launch {
                    try { withTimeoutOrNull(15_000L) { loadExtractor(url, referer, subs) { links.trySend(it) } } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.w("AniwatchTV.ro", "ANIWATCH_EXTRACTOR_FAILED server=${site(url)} reason=${e.javaClass.simpleName}") }
                    finally { links.close() }
                }
                try { for (link in links) if (accept(link)) break }
                finally { producer.cancelAndJoin(); links.cancel() }
            }
            if (!found) candidates.values.forEach { emit(it, false) }
            Log.i("AniwatchTV.ro", "ANIWATCH_SERVER_RESULT server=${site(url)} master=$found fallbacks=${candidates.size}")
        } }
        try { jobs.joinAll() } finally { captions.close() }
        workers.joinAll()
        Log.i("AniwatchTV.ro", "ANIWATCH_DONE links=${emitted.size}")
        emitted.isNotEmpty()
    }
}
