package com.yomi

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder

/** Same public bootstrap transport already implemented in this repo's AnimeXTV module. */
internal object YomiTryEmbed {
    private const val BASE = "https://tryembed.us.cc"
    private fun subOnly(node: JsonNode): Boolean {
        val audio = node.path("audio").asText("").ifBlank { node.path("subOrDub").asText("") }.lowercase()
        return audio in setOf("", "sub", "subbed", "japanese", "ja", "jpn", "original") &&
            node.path("type").asText("").lowercase() !in setOf("dub", "dubbed") &&
            !Regex("\\b(dub|dubbed|hindi)\\b", RegexOption.IGNORE_CASE).containsMatchIn(node.path("quality").asText(""))
    }
    suspend fun load(embed: String, referer: String, subtitles: (SubtitleFile) -> Unit,
                     accept: suspend (ExtractorLink) -> Boolean) {
        val page = app.get(embed, referer = referer, headers = mapOf("User-Agent" to USER_AGENT))
        val ticket = Regex("window\\.BOOTSTRAP_TICKET\\s*=\\s*(\"[^\"]+\")").find(page.text)
            ?.groupValues?.get(1)?.let { mapper.readTree(it).asText() } ?: return
        val cookies = page.cookies.toMutableMap()
        fun requestHeaders(nonce: String = "") = buildMap<String, String> {
            put("Referer", embed); put("Origin", BASE); put("User-Agent", USER_AGENT)
            if (cookies.isNotEmpty()) put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (nonce.isNotBlank()) put("X-Embed-Nonce", nonce)
        }
        val bootstrap = app.post("$BASE/api/bootstrap", headers = requestHeaders() + ("X-TryEmbed-Bootstrap" to ticket))
        if (bootstrap.code !in 200..299) return
        cookies.putAll(bootstrap.cookies)
        val root = mapper.readTree(bootstrap.text)
        var nonce = root.path("embedNonce").asText("")
        suspend fun parse(data: JsonNode): Boolean {
            if (!subOnly(data) || !subOnly(data.path("meta"))) return false
            val shared = data.path("captions").takeIf { it.isArray }?.toList().orEmpty()
            for (provider in data.path("providers")) {
                if (provider.path("status").asText() != "ready" || !subOnly(provider)) continue
                (shared + provider.path("captions").takeIf { it.isArray }?.toList().orEmpty()).forEach { track ->
                    val raw = track.path("url").asText("").ifBlank { track.path("file").asText("") }
                    if (raw.isNotBlank()) {
                        val target = URI(BASE).resolve(raw).toString()
                        subtitles(SubtitleFile(track.path("label").asText("").ifBlank { track.path("lang").asText("") }, target).apply {
                            this.headers = if (URI(target).host == URI(BASE).host) requestHeaders(nonce)
                                else mapOf("Referer" to embed, "User-Agent" to USER_AGENT)
                        })
                    }
                }
                val video = provider.path("type").asText() == "mp4"
                for (quality in provider.path("qualities")) {
                    if (!subOnly(quality)) continue
                    val token = quality.path("token").asText("")
                    val raw = quality.path("directUrl").asText("").ifBlank {
                        if (token.isBlank()) "" else "$BASE/s/$token.${if (video) "mp4" else "m3u8"}"
                    }
                    if (raw.isBlank()) continue
                    val target = URI(BASE).resolve(raw).toString()
                    if (URI(target).scheme !in setOf("http", "https")) continue
                    val link = newExtractorLink("Yomi", "TryEmbed", target,
                        if (video) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                        this.referer = embed
                        this.headers = if (URI(target).host == URI(BASE).host) requestHeaders(nonce)
                            else mapOf("Referer" to embed, "User-Agent" to USER_AGENT)
                        this.quality = quality.path("height").asInt().takeIf { it > 0 } ?: Qualities.Unknown.value
                    }
                    if (accept(link)) return true
                }
            }
            return false
        }
        if (parse(root)) return
        val parts = URI(embed).path.trim('/').split('/')
        val id = parts.getOrNull(2)?.toIntOrNull() ?: return
        val episode = parts.getOrNull(3)?.toIntOrNull() ?: return
        val idle = root.path("providers").filter { it.path("status").asText() == "idle" && subOnly(it) }
            .map { it.path("id").asText() }.filter { it.matches(Regex("[a-z0-9_-]+")) }.take(4)
        for (server in idle) {
            val endpoint = "$BASE/api/stream_data?id=$id&episode=$episode&audio=sub&player=jw&server=$server" +
                if (nonce.isBlank()) "" else "&nonce=${URLEncoder.encode(nonce, "UTF-8")}"
            val response = app.get(endpoint, headers = requestHeaders(nonce))
            if (response.code !in 200..299) continue
            cookies.putAll(response.cookies)
            val data = mapper.readTree(response.text)
            nonce = data.path("embedNonce").asText("").ifBlank { nonce }
            if (parse(data)) return
        }
    }
}
