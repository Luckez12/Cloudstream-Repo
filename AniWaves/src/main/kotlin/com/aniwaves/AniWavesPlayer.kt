package com.aniwaves

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
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

internal class AniWavesPlayer(private val mainUrl: String) {
    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")

    internal fun sourceEndpoint(playerUrl: String, id: String): String =
        URI(playerUrl).resolve("getSources?id=${URLEncoder.encode(id, "UTF-8")}").toString()

    // Echo exposes both {sources: "HLS URL"} and {sources: {HD: ["MP4 URL"]}}.
    // Older pages wrap that same quality map inside an episode ID.
    internal fun videoSources(node: JsonNode): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        fun walk(value: JsonNode, label: String) {
            when {
                value.isTextual -> {
                    val url = value.asText()
                    if (url.startsWith("https://") || url.startsWith("http://")) result.add(label to url)
                }
                value.isArray -> value.forEach { walk(it, label) }
                value.isObject -> {
                    if (value.has("file")) walk(value.path("file"), value.path("label").asText(label))
                    else value.fields().forEach { (key, child) ->
                        if (key !in setOf("tracks", "image", "skip_data", "htmlGuide")) walk(child, key)
                    }
                }
            }
        }
        walk(if (node.has("sources")) node.path("sources") else node, "")
        return result.distinctBy { it.second }
    }

    suspend fun load(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = coroutineScope {
        val payload = runCatching { mapper.readTree(data) }.getOrNull() ?: return@coroutineScope false
        val showUrl = payload.path("showUrl").asText()
        val serverQuery = payload.path("servers").asText()
        val site = runCatching { URI(showUrl) }.getOrNull() ?: return@coroutineScope false
        if (!site.host.equals(URI(mainUrl).host, true) || !site.path.startsWith("/watch/") ||
            !serverQuery.matches(Regex("[0-9]+&eps=[0-9]+(?:\\.[0-9]+)?"))) return@coroutineScope false
        val response = app.get("$mainUrl/ajax/server/list?servers=$serverQuery", referer = showUrl, headers = ajaxHeaders)
        val servers = mapper.readTree(response.text)
        if (servers.path("status").asInt() != 200) return@coroutineScope false
        val dub = payload.path("dub").asBoolean(false)
        val rows = Jsoup.parse(servers.path("result").asText()).select(".servers .type[data-type]")
            .filter { if (dub) it.attr("data-type") == "dub" else it.attr("data-type") in setOf("sub", "ssub") }
            .flatMap { group -> group.select("li[data-link-id]").map { group.attr("data-type") to it } }
            .distinctBy { it.second.attr("data-link-id") }.take(12)
        val slots = Semaphore(3)
        val emitted = mutableSetOf<String>()
        val subs = mutableSetOf<String>()
        val forward: (ExtractorLink) -> Unit = { link ->
            if (synchronized(emitted) { emitted.add(link.url + "|" + link.referer) }) callback(link)
        }
        val subtitles: (SubtitleFile) -> Unit = { sub ->
            if (synchronized(subs) { subs.add(sub.url) }) subtitleCallback(sub)
        }
        rows.map { (kind, row) -> async {
            slots.withPermit {
                val label = "${row.text().trim()} · ${kind.uppercase()}"
                try {
                    withTimeoutOrNull(18_000L) {
                        val id = URLEncoder.encode(row.attr("data-link-id"), "UTF-8")
                        val root = mapper.readTree(app.get("$mainUrl/ajax/sources?id=$id&asi=0&autoPlay=0", referer = showUrl, headers = ajaxHeaders).text)
                        if (root.path("status").asInt() == 200) {
                            val result = root.path("result")
                            emitTracks(result.path("tracks"), showUrl, subtitles)
                            val embedded = result.path("url").asText()
                            emitSources(result.path("sources"), embedded.ifBlank { showUrl }, label, forward)
                            if (embedded.isNotBlank()) {
                                val uri = URI(embedded)
                                when {
                                    uri.host.equals("play.echovideo.ru", true) -> {
                                        val document = app.get(embedded, referer = showUrl).document
                                        val token = document.selectFirst("#mg-player[data-id]")?.attr("data-id").orEmpty()
                                        if (token.isNotBlank()) {
                                            val sources = mapper.readTree(app.get(sourceEndpoint(embedded, token), referer = embedded).text)
                                            emitTracks(sources.path("tracks"), embedded, subtitles)
                                            emitSources(sources, embedded, label, forward)
                                        }
                                    }
                                    embedded.contains(".m3u8", true) || embedded.contains(".mp4", true) ->
                                        emitSources(mapper.valueToTree<JsonNode>(embedded), showUrl, label, forward)
                                    else -> loadExtractor(embedded, showUrl, subtitles, forward)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AniWaves", "ANIWAVES_SERVER_FAILED server=$label reason=${e.javaClass.simpleName}")
                }
            }
        } }.awaitAll()
        Log.i("AniWaves", "ANIWAVES_LINKS mode=${if (dub) "dub" else "sub"} servers=${rows.size} links=${emitted.size} subtitles=${subs.size}")
        emitted.isNotEmpty()
    }

    private suspend fun emitSources(node: JsonNode, referer: String, server: String, callback: (ExtractorLink) -> Unit) {
        videoSources(node).forEach { (label, url) ->
            val hls = url.contains(".m3u8", true)
            callback(newExtractorLink("AniWaves", "AniWaves · $server" + if (label.isBlank()) "" else " · $label", url,
                if (hls) ExtractorLinkType.M3U8 else INFER_TYPE) {
                this.referer = referer
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                quality = Regex("(?:^|\\D)(2160|1080|720|480|360)(?:p|$)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
            })
        }
    }

    private suspend fun emitTracks(node: JsonNode, referer: String, callback: (SubtitleFile) -> Unit) {
        if (!node.isArray) return
        node.forEach { track ->
            val kind = track.path("kind").asText("captions")
            val raw = track.path("file").asText(track.path("src").asText())
            if (kind in setOf("captions", "subtitles") && raw.isNotBlank()) {
                val url = URI(referer).resolve(raw).toString()
                callback(newSubtitleFile(track.path("label").asText("English"), url))
            }
        }
    }
}
