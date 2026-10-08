package com.kshow123

import android.os.SystemClock
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class KShow123Player(private val mainUrl: String) {
    private val json = jacksonObjectMapper()
    private val seenLinks = ConcurrentHashMap.newKeySet<String>()
    private val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
    private val linkCount = AtomicInteger(0)
    private val extractionSlots = Semaphore(3)

    private fun variable(html: String, name: String): String? =
        Regex("""\b(?:var|let|const)\s+${Regex.escape(name)}\s*=\s*(['"])(.*?)\1\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(2)

    private fun resolve(raw: String, base: String): String? = runCatching {
        URI(base).resolve(raw.trim()).takeIf {
            it.scheme in listOf("http", "https") && it.host != null
        }?.toString()
    }.getOrNull()

    private fun media(url: String): Boolean =
        Regex("""\.(?:m3u8|mp4)(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private suspend fun emit(url: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (!media(url) || !seenLinks.add(url)) return
        callback(newExtractorLink("KShow123", "KShow123 · $label", url, INFER_TYPE) {
            this.referer = referer
            quality = Qualities.Unknown.value
            headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
        })
        linkCount.incrementAndGet()
    }

    private suspend fun subtitle(raw: String?, base: String, callback: (SubtitleFile) -> Unit) {
        val url = raw?.let { resolve(it, base) } ?: return
        // The JWPlayer response often includes only the site's intro caption,
        // which is not an English translation of the episode.
        if (URI(url).path.endsWith("/intro.vtt", ignoreCase = true)) return
        if (seenSubtitles.add(url)) callback(newSubtitleFile("English", url))
    }

    private suspend fun guarded(stage: String, timeout: Long, block: suspend () -> Unit) {
        try {
            val finished = withTimeoutOrNull(timeout) { block(); true }
            if (finished == null) Log.w("KShow123", "KSHOW123_TIMEOUT stage=$stage")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KShow123", "KSHOW123_ERROR stage=$stage type=${e.javaClass.simpleName}")
        }
    }

    suspend fun load(
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        val response = app.get(episodeUrl)
        val html = response.text
        val videoList = variable(html, "videoList")
            ?: throw ErrorLoadingException("KShow123: data video episod tidak ditemui.")
        val servers = runCatching { json.readTree(videoList) }.getOrNull()
            ?.takeIf { it.isArray && it.size() > 0 }
            ?: throw ErrorLoadingException("KShow123: senarai server tidak sah.")
        val labels = mutableMapOf<Int, String>()
        response.document.select("#server_list .server_item").forEach { item ->
            val label = item.selectFirst("strong")?.text()?.trim()?.trimEnd(':').orEmpty()
            item.select("a[video-id]").forEach { anchor ->
                anchor.attr("video-id").toIntOrNull()?.let { labels[it] = label }
            }
        }
        val cover = variable(html, "imageCover").orEmpty()
        val eToken = variable(html, "eToken").orEmpty()
        val domain = URI(episodeUrl).host
        Log.i("KShow123", "KSHOW123_SERVERS count=${servers.size()}")
        coroutineScope {
            // The site currently has two servers. Bound work if it grows.
            (0 until minOf(servers.size(), 8)).map { index ->
                async {
                    guarded("api-$index", 60_000L) {
                        val server = servers[index]
                        val hash = server.get(0)?.asText()?.takeIf { it.isNotBlank() } ?: return@guarded
                        val data = mutableMapOf("streamUrlHash" to hash, "imageCover" to cover, "eToken" to eToken)
                        server.get(1)?.takeUnless { it.isNull }?.asText()?.let { data["subtitlesUrlHash"] = it }
                        val api = withTimeoutOrNull(18_000L) {
                            app.post(
                                "https://api.kshow123.tv/ajax/",
                                data = data,
                                referer = episodeUrl,
                                headers = mapOf("Origin" to mainUrl, "X-Requested-With" to "XMLHttpRequest")
                            )
                        } ?: run {
                            Log.w("KShow123", "KSHOW123_TIMEOUT stage=api-request-$index")
                            return@guarded
                        }
                        val label = labels[index]?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}"
                        Log.i("KShow123", "KSHOW123_API server=$label status=${api.code} bytes=${api.text.length}")
                        if (api.code !in 200..299) return@guarded
                        val fragment = Jsoup.parse(api.text, episodeUrl)
                        val decoded = Regex("""decodeLink\(\s*(['"])(.*?)\1\s*,\s*(['"]?)([0-9]+)\3\s*\)""")
                            .findAll(api.text).mapNotNull { match ->
                                KShow123Crypto.decodeLink(match.groupValues[2], domain, match.groupValues[4])
                            }.toList()
                        decoded.forEach { value ->
                            val url = resolve(value, episodeUrl) ?: return@forEach
                            if (media(url)) emit(url, label, episodeUrl, callback)
                            else if (Regex("""\.(?:vtt|srt)(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                                subtitle(url, episodeUrl, subtitleCallback)
                            }
                        }
                        fragment.select("iframe[src]").mapNotNull { resolve(it.attr("src"), episodeUrl) }
                            .distinct().take(4).forEach { embed ->
                                val host = URI(embed).host.orEmpty()
                                if (host == "vidbasic.top" || host.endsWith(".vidbasic.top")) {
                                    vidbasic(embed, label, episodeUrl, subtitleCallback, callback)
                                } else external(embed, label, episodeUrl, subtitleCallback, callback)
                            }
                        fragment.select("video source[src], video[src]").forEach {
                            resolve(it.attr("src"), episodeUrl)?.let { url -> emit(url, label, episodeUrl, callback) }
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i("KShow123", "KSHOW123_LINKS streams=${linkCount.get()} subtitles=${seenSubtitles.size} ms=${SystemClock.elapsedRealtime() - started}")
        if (linkCount.get() == 0) throw ErrorLoadingException("KShow123: tiada sumber video berjaya diekstrak. Semak log KSHOW123_API dan KSHOW123_ERROR.")
        return true
    }

    private suspend fun vidbasic(
        embed: String, label: String, episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val response = withTimeoutOrNull(15_000L) { app.get(embed, referer = episodeUrl) }
            ?: return@coroutineScope
        val mirrors = response.document.select(".linkserver[data-video]")
            .mapNotNull { item ->
                val url = resolve(item.attr("data-video"), embed) ?: return@mapNotNull null
                url to item.text().trim().ifBlank { "Mirror" }
            }.distinctBy { it.first }.take(6)
        Log.i("KShow123", "KSHOW123_MIRRORS server=$label count=${mirrors.size}")
        mirrors.map { (url, mirror) ->
            async {
                extractionSlots.withPermit {
                    guarded("mirror-$mirror", 15_000L) {
                        if (URI(url).host.equals(URI(embed).host, ignoreCase = true) &&
                            URI(url).path.endsWith("/3rdplayer.html")) {
                            val player = app.get(url, referer = embed).document
                            val encrypted = player.selectFirst("script[data-name=crypto]")?.attr("data-value")
                            val decoded = encrypted?.let { KShow123Crypto.decodeStandard(it) }
                            val stream = decoded?.let { resolve(it, url) }
                            if (stream != null) emit(stream, "$label · $mirror", url, callback)
                            else Log.w("KShow123", "KSHOW123_DECODE_FAILED server=Standard")
                            URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("sub=") }
                                ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
                                ?.let { KShow123Crypto.decodeStandard(it) }
                                ?.let { subtitle(it, url, subtitleCallback) }
                        } else external(url, "$label · $mirror", embed, subtitleCallback, callback)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun external(
        url: String, label: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        if (media(url)) {
            emit(url, label, referer, callback)
            return
        }
        // Extractor callbacks are synchronous. Forward the already-built
        // subtitle instead of invoking a suspending builder inside them.
        loadExtractor(url, referer, { sub ->
            if (!URI(sub.url).path.orEmpty().endsWith("/intro.vtt", ignoreCase = true) &&
                seenSubtitles.add(sub.url)) subtitleCallback(sub)
        }) { link ->
            if (seenLinks.add(link.url)) {
                callback(link)
                linkCount.incrementAndGet()
            }
        }
    }
}
