package com.animextv

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLEncoder

internal class AnimeXTVTryEmbed(private val mainUrl: String) {
    private val base = "https://tryembed.us.cc"
    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    internal fun ticket(html: String): String? = Regex("window\\.BOOTSTRAP_TICKET\\s*=\\s*(\"[^\"]+\")")
        .find(html)?.groupValues?.get(1)?.let { mapper.readTree(it).asText().takeIf { t -> t.isNotBlank() } }

    internal suspend fun parse(root: JsonNode, referer: String, headers: Map<String,String>, subtitles: (SubtitleFile) -> Unit): List<AnimeXTVBatch> {
        val mirrors = AnimeXTVMirrors()
        if (!mirrors.subOnly(root.path("meta"))) return emptyList()
        val shared = root.path("captions").takeIf { it.isArray }?.toList().orEmpty()
        return root.path("providers").filter { mirrors.subOnly(it) && it.path("status").asText("") == "ready" }.map { provider ->
            (shared + provider.path("captions").takeIf { it.isArray }?.toList().orEmpty()).forEach { track ->
                val raw = track.path("url").asText("").ifBlank { track.path("file").asText("") }
                if (raw.isNotBlank()) {
                    val url = URI(base).resolve(raw).toString()
                    if (URI(url).scheme in setOf("http", "https")) subtitles(newSubtitleFile(track.path("label").asText("").ifBlank { track.path("lang").asText("Subtitles") }, url))
                }
            }
            val mp4 = provider.path("type").asText("") == "mp4"
            val ext = if (mp4) "mp4" else "m3u8"
            val links = provider.path("qualities").filter { mirrors.subOnly(it) }.flatMap { quality ->
                val direct = quality.path("directUrl").asText("")
                val token = quality.path("token").asText("")
                val fallback = quality.path("fallbackToken").asText("")
                val primary = direct.ifBlank { if (token.isBlank()) "" else "$base/s/$token.$ext" }
                // Public player lists fallbackToken as an alternate transport source.
                val urls = listOf(primary, if (fallback.isBlank()) "" else "$base/s/$fallback.$ext").filter { it.isNotBlank() }.distinct()
                urls.mapNotNull { raw ->
                    val url = URI(base).resolve(raw).toString()
                    if (URI(url).scheme !in setOf("http", "https")) return@mapNotNull null
                    newExtractorLink("AnimeXTV · TryEmbed", "TryEmbed", url, if (mp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                        this.referer = referer
                        // Anonymous bootstrap cookies/nonces belong only to TryEmbed's own transport.
                        this.headers = if (URI(url).host == URI(base).host) headers else mapOf("Referer" to referer, "User-Agent" to userAgent)
                        this.quality = quality.path("height").asInt().takeIf { it > 0 }
                            ?: Regex("^(2160|1440|1080|720|576|480|360|240)p?$", RegexOption.IGNORE_CASE)
                                .matchEntire(quality.path("name").asText(""))?.groupValues?.get(1)?.toIntOrNull()
                            ?: Qualities.Unknown.value
                    }
                }
            }.distinctBy { it.url }
            val name = provider.path("name").asText("").removeSuffix(" Server").ifBlank { provider.path("id").asText("Server").replaceFirstChar { it.uppercase() } }
            AnimeXTVBatch("TryEmbed · $name", links)
        }
    }

    suspend fun load(ani: Int, episode: Int, subtitles: (SubtitleFile) -> Unit): List<AnimeXTVBatch> {
        val embed = "$base/embed/anime/$ani/$episode/sub"
        val deadline = System.currentTimeMillis() + 48_000L
        return try {
            withTimeoutOrNull(50_000L) {
                val page = withTimeoutOrNull(10_000L) { app.get(embed, referer = "$mainUrl/", headers = mapOf("User-Agent" to userAgent)) }
                    ?: return@withTimeoutOrNull emptyList()
                val ticket = ticket(page.text) ?: return@withTimeoutOrNull emptyList()
                val cookies = page.cookies.toMutableMap()
                fun headers(nonce: String = ""): Map<String,String> = buildMap {
                    put("Referer", embed); put("Origin", base); put("User-Agent", userAgent)
                    if (cookies.isNotEmpty()) put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    if (nonce.isNotBlank()) put("X-Embed-Nonce", nonce)
                }
                val response = withTimeoutOrNull(12_000L) { app.post("$base/api/bootstrap", headers = headers() + ("X-TryEmbed-Bootstrap" to ticket)) }
                    ?: return@withTimeoutOrNull emptyList()
                if (response.code !in 200..299) {
                    Log.w("AnimeXTV", "ANIMEXTV_TRYEMBED stage=bootstrap status=${response.code}")
                    return@withTimeoutOrNull emptyList()
                }
                cookies.putAll(response.cookies)
                val root = mapper.readTree(response.text)
                var nonce = root.path("embedNonce").asText("")
                val batches = parse(root, embed, headers(nonce), subtitles).toMutableList()
                val idle = root.path("providers").filter {
                    it.path("status").asText("") == "idle" && AnimeXTVMirrors().subOnly(it) &&
                        it.path("id").asText("").matches(Regex("[a-z0-9_-]+"))
                }.map { it.path("id").asText() }.ifEmpty {
                    if (batches.any { it.links.isNotEmpty() }) emptyList() else listOf("")
                }.take(12)
                for (server in idle) {
                    if (System.currentTimeMillis() + 12_500L > deadline) break
                    val endpoint = "$base/api/stream_data?id=$ani&episode=$episode&audio=sub&player=jw" +
                        if (server.isBlank()) "" else "&server=$server"
                    val url = endpoint + if (nonce.isBlank()) "" else "&nonce=${URLEncoder.encode(nonce, "UTF-8")}" 
                    val stream = withTimeoutOrNull(12_000L) { app.get(url, headers = headers(nonce)) } ?: continue
                    if (stream.code !in 200..299) continue
                    cookies.putAll(stream.cookies)
                    val data = mapper.readTree(stream.text)
                    nonce = data.path("embedNonce").asText("").ifBlank { nonce }
                    batches.addAll(parse(data, embed, headers(nonce), subtitles))
                }
                batches
            }.orEmpty()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w("AnimeXTV", "ANIMEXTV_TRYEMBED reason=${e.javaClass.simpleName}")
            emptyList()
        }
    }
}
