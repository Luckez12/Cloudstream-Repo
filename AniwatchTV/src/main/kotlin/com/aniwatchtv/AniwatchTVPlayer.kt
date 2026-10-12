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
    private data class Server(val url: String, val choice: String, val index: Int) {
        val key: String get() = "$index|$choice|$url"
    }
    private fun site(url: String): String = when (URI(url).host?.lowercase()) {
        "megaplay.su" -> "MegaPlay.su"
        "megaplay.buzz" -> "MegaPlay.buzz"
        "zokoanime.video" -> "ZokoAnime"
        "gogoanime.me.uk" -> "GogoAnime.me.uk"
        "vidtube.site" -> "VidTube"
        "vidmoly.org", "vidmoly.net", "vidmoly.to" -> "Vidmoly"
        else -> URI(url).host.orEmpty()
    }
    private suspend fun megaBuzz(url: String, referer: String, subs: (SubtitleFile) -> Unit): List<ExtractorLink> {
        val page = app.get(url, referer = referer)
        Log.i("AniwatchTV.ro", "ANIWATCH_EMBED server=MegaPlay.buzz status=${page.code}")
        if (page.code !in 200..299) return emptyList()
        val id = page.document.selectFirst("#megaplay-player[data-id]")?.attr("data-id")
            ?.takeIf { it.matches(Regex("[0-9]+")) } ?: return emptyList()
        val response = app.get("https://megaplay.buzz/stream/getSources?id=$id&platform=OTHER", referer = url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
        if (response.code !in 200..299) return emptyList()
        val root = mapper.readTree(response.text)
        if (root.has("error")) return emptyList()
        val source = if (root.path("enc").asText("").isNotBlank()) mapper.readTree(AniwatchTVCrypto.decrypt(root.path("enc").asText()))
            else root.path("sources")
        val file = if (source.isTextual) source.asText() else source.path("file").asText("")
        if (runCatching { URI(file).scheme in setOf("http", "https") }.getOrDefault(false).not()) return emptyList()
        val transport = mapOf("Referer" to url, "Origin" to "https://megaplay.buzz", "User-Agent" to USER_AGENT)
        root.path("tracks").filter { it.path("kind").asText("captions") in setOf("captions", "subtitles") }.forEach { track ->
            val raw = track.path("file").asText("")
            if (raw.isNotBlank()) subs(SubtitleFile(track.path("label").asText("").ifBlank { track.path("lang").asText("") }, URI(url).resolve(raw).toString()).apply { headers = transport })
        }
        return listOf(newExtractorLink("MegaPlay.buzz", "MegaPlay.buzz", AniwatchTVCrypto.signed(file), ExtractorLinkType.M3U8) {
            this.referer = url; headers = transport; quality = Qualities.Unknown.value
        })
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
        if (host == "megaplay.buzz") return megaBuzz(url, referer, subs)
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
            .mapIndexedNotNull { index, el ->
                val url = runCatching { String(Base64.decode(el.attr("data-hash"), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                    ?.takeIf { runCatching { URI(it).scheme in setOf("http", "https") && !URI(it).host.isNullOrBlank() }.getOrDefault(false) }
                    ?: return@mapIndexedNotNull null
                Server(url, el.attr("data-server-name").ifBlank { "Server ${index + 1}" }, index + 1)
            }
        Log.i("AniwatchTV.ro", "ANIWATCH_SERVERS episode_id=$id sub=${servers.size}")
        val lock = Any()
        val emitted = mutableSetOf<String>()
        val captions = Channel<SubtitleFile>(Channel.UNLIMITED)
        val validator = AniwatchTVSubtitles(subtitleCallback)
        val workers = List(3) { launch { for (sub in captions) validator.accept(sub) } }
        val jobs = servers.groupBy { it.url }.map { (url, aliases) -> launch {
            val server = aliases.first()
            val choice = server.choice.replace(Regex("\\s+"), " ").trim()
            val display = "${site(url)} · $choice"
            Log.i("AniwatchTV.ro", "ANIWATCH_SERVER_START index=${server.index} choice=${choice.replace(' ', '_')} server=${site(url)}")
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
                for (alias in aliases) {
                    val label = "${site(url)} · ${alias.choice}"
                    val output = newExtractorLink(label, "$label · Sub · $quality", link.url, link.type) {
                        this.referer = link.referer; headers = link.headers; this.quality = if (isMaster) Qualities.Unknown.value else link.quality
                        extractorData = link.extractorData; audioTracks = link.audioTracks
                    }
                    synchronized(lock) { if (emitted.add("${alias.key}|${link.url}")) callback(output) }
                }
            }
            suspend fun accept(link: ExtractorLink): Boolean {
                if (found) return true
                val old = candidates[link.url]
                if (old != null && old.headers == link.headers && old.referer == link.referer) return false
                if (master(link)) {
                    found = true; candidates.clear(); emit(link, true)
                    Log.i("AniwatchTV.ro", "ANIWATCH_MASTER index=${server.index} choice=${choice.replace(' ', '_')} server=${site(url)} action=stop_server")
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
            if (!found) {
                try {
                    // Capture the actual embedded player's requests for every returned Sub route.
                    // The callback cancels only this WebView after a verified master.
                    val complete = withTimeoutOrNull(45_000L) {
                        AniwatchTVWeb.streams(url, referer) { media ->
                            if (media.kind == "subtitle") {
                                subs(SubtitleFile(media.language, media.url).apply { headers = media.headers })
                                false
                            } else {
                                val path = runCatching { URI(media.url).path }.getOrNull().orEmpty().lowercase()
                                val link = newExtractorLink(display, display, media.url,
                                    if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                    this.referer = media.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: url
                                    headers = media.headers; quality = Qualities.Unknown.value
                                }
                                accept(link)
                            }
                        }
                        true
                    }
                    if (complete != true) Log.w("AniwatchTV.ro", "ANIWATCH_WEB_TIMEOUT index=${server.index} server=${site(url)}")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w("AniwatchTV.ro", "ANIWATCH_WEB_FAILED index=${server.index} server=${site(url)} reason=${e.javaClass.simpleName}") }
            }
            if (!found) candidates.values.forEach { emit(it, false) }
            aliases.forEach { alias -> Log.i("AniwatchTV.ro", "ANIWATCH_SERVER_RESULT index=${alias.index} choice=${alias.choice.replace(' ', '_')} server=${site(url)} master=$found fallbacks=${candidates.size}") }
        } }
        try { jobs.joinAll() } finally { captions.close() }
        workers.joinAll()
        Log.i("AniwatchTV.ro", "ANIWATCH_DONE links=${emitted.size}")
        emitted.isNotEmpty()
    }
}
