package com.yomi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale

internal class YomiPlayer(private val mainUrl: String) {
    private data class Result(val embed: YomiWeb.Embed, val links: List<ExtractorLink>, val subs: List<SubtitleFile>)
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

    suspend fun load(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = coroutineScope {
        val uri = runCatching { URI(data) }.getOrNull() ?: return@coroutineScope false
        if (uri.host != URI(mainUrl).host || !uri.path.matches(Regex("/watch/[a-z0-9-]+-[0-9]+/[0-9]+/?"))) return@coroutineScope false
        val watch = YomiWeb.watch(data)
        // Only URLs actually selected by Yomi's player enter extraction. No guessed Dub URLs.
        val embeds = watch.embeds.distinctBy { it.url }
        Log.i("Yomi", "YOMI_PLAYER embeds=${embeds.size}")
        val results = embeds.map { embed -> async {
            val links = mutableListOf<ExtractorLink>()
            val subs = mutableListOf<SubtitleFile>()
            try {
                withTimeoutOrNull(12_000L) {
                    try {
                        if (URI(embed.url).host == "megaplay.buzz") links.addAll(mega(embed.url, subs))
                        if (links.isEmpty()) loadExtractor(embed.url, data, { subs.add(it) }, { links.add(it) })
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Log.w("Yomi", "YOMI_NATIVE_FALLBACK server=${label(embed.url)} reason=${e.javaClass.simpleName}")
                    }
                }
                if (links.isEmpty()) {
                    YomiWeb.streams(embed.url, data).forEach { media ->
                        if (media.kind == "subtitle") {
                            subs.add(SubtitleFile(media.language, media.url))
                            return@forEach
                        }
                        val hls = URI(media.url).path.orEmpty().lowercase().endsWith(".m3u8")
                        links.add(newExtractorLink("Yomi", label(embed.url), media.url,
                            if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            referer = media.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: embed.url
                            headers = media.headers
                            quality = Qualities.Unknown.value
                        })
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w("Yomi", "YOMI_SERVER_FAILED server=${label(embed.url)} audio=${embed.audio} reason=${e.javaClass.simpleName}")
            }
            Log.i("Yomi", "YOMI_SERVER_RESULT server=${label(embed.url)} audio=${embed.audio} links=${links.size} subtitles=${subs.size}")
            Result(embed, links.toList(), subs.toList())
        } }.awaitAll()
        val seen = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        for (result in results) {
            result.subs.forEach { sub -> language(sub)?.let { lang ->
                if (seenSubs.add(sub.url)) subtitleCallback(SubtitleFile(lang, sub.url))
            } }
            for (link in result.links) {
                if (!seen.add("${result.embed.audio}|${link.url}")) continue
                var master = false
                if (link.type == ExtractorLinkType.M3U8) {
                    try {
                        val response = withTimeoutOrNull(2_000L) { app.get(link.url, referer = link.referer, headers = link.headers) }
                        master = response != null && response.code in 200..299 && response.text.contains("#EXT-X-STREAM-INF:")
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                val qualityLabel = if (master) "Auto" else link.quality.takeIf { it > 0 && it != Qualities.Unknown.value }?.let { "${it}p" } ?: "Unknown"
                val audio = if (result.embed.audio == "dub") "Dub" else "Sub"
                callback(newExtractorLink("Yomi", "${label(result.embed.url)} · $audio · $qualityLabel", link.url, link.type) {
                    referer = link.referer
                    headers = link.headers
                    quality = if (master) Qualities.Unknown.value else link.quality
                    extractorData = link.extractorData
                })
            }
        }
        Log.i("Yomi", "YOMI_PLAYER_DONE links=${seen.size} subtitles=${seenSubs.size}")
        seen.isNotEmpty()
    }
}
