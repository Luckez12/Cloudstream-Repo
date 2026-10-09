package com.animextv

import android.util.Base64
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
import java.net.URLEncoder

internal data class AnimeXTVBatch(val server: String, val links: List<ExtractorLink>)

internal class AnimeXTVMirrors {
    private val slots = Semaphore(3)
    private val vidBase = "https://new.vidnest.fun"
    private val frameBase = "https://api.framextv.tech/anime"
    private val frameHeaders = mapOf("Referer" to "https://framextv.tech/", "Origin" to "https://framextv.tech", "User-Agent" to USER_AGENT)

    internal fun decode(text: String): JsonNode {
        val root = mapper.readTree(text)
        if (!root.path("encrypted").asBoolean()) return root
        // Same custom Base64 alphabet as Vidnest's public player response decoder.
        val alphabet = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
        val normal = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val encoded = root.path("data").asText("").filterNot { it.isWhitespace() }
        require(encoded.isNotBlank() && encoded.all { it in alphabet })
        val plain = encoded.map { normal[alphabet.indexOf(it)] }.joinToString("")
        return mapper.readTree(String(Base64.decode(plain, Base64.DEFAULT), Charsets.UTF_8))
    }

    internal fun subOnly(node: JsonNode): Boolean {
        val audio = node.path("audio").asText("").ifBlank { node.path("subOrDub").asText("") }.lowercase()
        val type = node.path("type").asText("").lowercase()
        val quality = node.path("quality").asText("").lowercase()
        return audio in setOf("", "sub", "subbed", "japanese", "ja", "jpn", "original") && type !in setOf("dub", "dubbed") &&
            !Regex("\\b(dub|dubbed|hindi)\\b", RegexOption.IGNORE_CASE).containsMatchIn(quality)
    }

    private fun url(raw: String, base: String): String? = runCatching {
        URI(base).resolve(raw.trim()).toString().takeIf { URI(it).scheme in setOf("http", "https") }
    }.getOrNull()
    private fun sourceNodes(root: JsonNode): List<JsonNode> =
        listOf(root.path("sources"), root.path("multiSrc")).filter { it.isArray }.flatMap { it.toList() }
            .distinctBy { it.path("url").asText("").ifBlank { it.path("file").asText("") } }
    private fun quality(raw: String): Int = Regex("^(2160|1440|1080|720|576|480|360|240)(?:p)?$", RegexOption.IGNORE_CASE)
        .matchEntire(raw.trim())?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
    private fun hls(node: JsonNode, raw: String) = node.path("type").asText("").lowercase() in setOf("hls", "m3u8") ||
        raw.contains(".m3u8", true) || node.path("quality").asText("").equals("auto", true)

    private suspend fun tracks(root: JsonNode, base: String, cb: (SubtitleFile) -> Unit) {
        listOf(root.path("tracks"), root.path("subtitles")).filter { it.isArray }.flatMap { it.toList() }.forEach { t ->
            if (t.path("kind").asText("captions") !in setOf("captions", "subtitles")) return@forEach
            val raw = t.path("url").asText("").ifBlank { t.path("file").asText("") }
            val resolved = (if (base.startsWith(frameBase)) frameUrl(raw) else url(raw, base))?.takeIf { raw.isNotBlank() } ?: return@forEach
            cb(newSubtitleFile(t.path("label").asText("").ifBlank { t.path("lang").asText("Subtitles") }, resolved))
        }
    }

    internal suspend fun vidLinks(root: JsonNode, kind: String, subtitles: (SubtitleFile) -> Unit): List<ExtractorLink> {
        if (!subOnly(root) || !subOnly(root.path("metadata"))) return emptyList()
        tracks(root, "$vidBase/", subtitles)
        return sourceNodes(root).filter { subOnly(it) }.mapNotNull { source ->
            val raw = source.path("url").asText("").ifBlank { source.path("file").asText("") }
            val file = url(raw, "$vidBase/")?.takeIf { raw.isNotBlank() } ?: return@mapNotNull null
            val ref = source.path("referer").asText("").ifBlank {
                if (kind == "aniwave") "https://play.echovideo.ru/" else "https://megaplay.buzz/"
            }
            newExtractorLink("AnimeXTV · Vidnest", "Vidnest", file, if (hls(source, file)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = ref
                headers = mapOf("Referer" to ref, "Origin" to (URI(ref).scheme + "://" + URI(ref).authority), "User-Agent" to USER_AGENT)
                quality = quality(source.path("quality").asText(""))
            }
        }.distinctBy { it.url }
    }

    suspend fun vidnest(ani: Int, episode: Int, subtitles: (SubtitleFile) -> Unit): List<AnimeXTVBatch> = coroutineScope {
        // Both Vidnest page layouts call these same APIs: fetch each source once.
        listOf("aniwave" to "AniWave", "megaplay" to "MegaPlay", "anitaku" to "Anitaku").map { (kind, label) -> async {
            slots.withPermit {
                val endpoint = when (kind) {
                    "aniwave" -> "$vidBase/aniwave_hls/$ani/$episode/sub"
                    "megaplay" -> "$vidBase/animehub/$ani/$episode/sub"
                    else -> "$vidBase/hianime/anime/$ani/$episode/sub/hd-2"
                }
                val links = safely {
                    val response = app.get(endpoint, referer = "https://vidnest.fun/")
                    if (response.code !in 200..299) return@safely emptyList()
                    vidLinks(decode(response.text), kind, subtitles)
                }.orEmpty()
                AnimeXTVBatch("Vidnest · $label", links)
            }
        } }.awaitAll()
    }

    internal fun frameUrl(raw: String): String? = when {
        raw.startsWith("/anime/api/") -> "$frameBase" + raw.removePrefix("/anime")
        raw.startsWith("/api/") -> "$frameBase$raw"
        else -> url(raw, "$frameBase/")
    }
    private fun encodedRef(ref: String): String = Base64.encodeToString(ref.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING)
    internal suspend fun frameLinks(root: JsonNode, subtitles: (SubtitleFile) -> Unit): List<ExtractorLink> {
        if (!root.path("success").asBoolean() || !subOnly(root)) return emptyList()
        // The API's own proxy preserves upstream headers and rewrites segment URLs.
        tracks(root, "$frameBase/", subtitles)
        return sourceNodes(root).filter { subOnly(it) }.mapNotNull { source ->
            val raw = source.path("url").asText("").ifBlank { source.path("file").asText("") }
            if (raw.isBlank()) return@mapNotNull null
            val upstreamRef = source.path("headers").path("Referer").asText("")
                .ifBlank { root.path("headers").path("Referer").asText("") }
            val file = if (raw.startsWith("/api/") || raw.startsWith("/anime/api/") ||
                raw.contains("/proxy") || source.path("skipProxy").asBoolean()) frameUrl(raw)
            else "$frameBase/api/proxy?url=${URLEncoder.encode(raw, "UTF-8")}" +
                if (upstreamRef.isNotBlank()) "&ref=${URLEncoder.encode(encodedRef(upstreamRef), "UTF-8")}" else ""
            file ?: return@mapNotNull null
            tracks(source, "$frameBase/", subtitles)
            newExtractorLink("AnimeXTV · FrameXTV", "FrameXTV", file, if (hls(source, raw)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                val direct = URI(file).host != URI(frameBase).host && source.path("skipProxy").asBoolean()
                referer = if (direct && upstreamRef.isNotBlank()) upstreamRef else "https://framextv.tech/"
                val upstreamHeaders = (if (source.path("headers").isObject) source.path("headers") else root.path("headers"))
                    .fields().asSequence().associate { it.key to it.value.asText("") }
                headers = if (direct) mapOf("User-Agent" to USER_AGENT) + upstreamHeaders else frameHeaders
                quality = quality(source.path("quality").asText(""))
            }
        }.distinctBy { it.url }
    }

    suspend fun frame(ani: Int, episode: Int, subtitles: (SubtitleFile) -> Unit): List<AnimeXTVBatch> = coroutineScope {
        val providers = safely {
            val response = app.get("$frameBase/api/providers?sub_type=sub", headers = frameHeaders)
            if (response.code !in 200..299) return@safely emptyList<Pair<String,String>>()
            mapper.readTree(response.text).path("providers").filter {
                it.path("audio").asText("") == "sub" && it.path("status").asText("") == "active" &&
                    it.path("id").asText("").matches(Regex("[a-z0-9_]+"))
            }.map { it.path("id").asText() to it.path("name").asText() }
        }.orEmpty().take(12)
        val requests = providers.ifEmpty { listOf("" to "") }
        requests.map { (id, _) -> async {
            slots.withPermit {
                val batch = safely {
                    val endpoint = "$frameBase/api/stream?id=$ani&type=tv&sub_type=sub&season=1&episode=$episode" + if (id.isBlank()) "" else "&provider=$id"
                    val response = app.get(endpoint, headers = frameHeaders)
                    if (response.code !in 200..299) return@safely null
                    val root = mapper.readTree(response.text)
                    val actual = root.path("provider").asText("")
                    if (actual.endsWith("_dub") || !subOnly(root)) return@safely null
                    val label = providers.firstOrNull { it.first == actual }?.second.orEmpty().ifBlank { root.path("server").asText("FrameXTV") }
                    AnimeXTVBatch("FrameXTV · $label", frameLinks(root, subtitles))
                }
                batch ?: AnimeXTVBatch("FrameXTV", emptyList())
            }
        } }.awaitAll()
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        withTimeoutOrNull(12_000L) { block() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
