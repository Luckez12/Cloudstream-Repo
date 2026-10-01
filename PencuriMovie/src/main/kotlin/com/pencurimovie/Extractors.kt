package com.pencurimovie

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Native mirrors advertised by HGCloud's main.js on 2026-10-01.
 * Read the original page first, so future HTTP redirects/native pages still work.
 * No external JavaScript is executed by this extension.
 */
open class Hglink : ExtractorApi() {
    override val name = "HGCloud"
    override val mainUrl = "https://hglink.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val produced = AtomicBoolean(false)
        val emit: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank()) {
                produced.set(true)
                callback(link)
            }
        }
        try {
            withTimeoutOrNull(ORIGINAL_TIMEOUT_MS) {
                loadPackedPlayer(url, referer, name, subtitleCallback, emit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { /* Try the native mirrors below. */ }
        if (produced.get()) return

        val original = runCatching { URI(url) }.getOrNull() ?: return
        val code = original.path.orEmpty().trim('/').removePrefix("e/").removePrefix("f/")
        if (!code.matches(Regex("[A-Za-z0-9_-]+"))) return
        for (host in NATIVE_MIRRORS) {
            try {
                withTimeoutOrNull(MIRROR_TIMEOUT_MS) {
                    // Preserve the same video ID on the host's advertised native mirror.
                    val nativeUrl = URI("https", host, "/e/$code", original.query, null).toString()
                    Hgnative("https://$host").getUrl(nativeUrl, referer, subtitleCallback, emit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* A failed mirror must not suppress the next one. */ }
            if (produced.get()) {
                Log.i("PencuriMovie", "PM_V5_HGCLOUD mirror=$host success=true")
                return
            }
        }
        Log.w("PencuriMovie", "PM_V5_HGCLOUD success=false")
    }

    private companion object {
        const val ORIGINAL_TIMEOUT_MS = 4_000L
        const val MIRROR_TIMEOUT_MS = 7_000L
        val NATIVE_MIRRORS = listOf("hanerix.com", "audinifer.com", "vibuxer.com")
    }
}

class Hgcloud : Hglink() {
    override val mainUrl = "https://hgcloud.to"
}

class Dhcplay : Hglink() {
    override val mainUrl = "https://dhcplay.com"
}

class Hgnative(override val mainUrl: String) : ExtractorApi() {
    override val name = "HGCloud"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadPackedPlayer(url, referer, name, subtitleCallback, callback)
    }
}

class Dsvplay : DoodLaExtractor() {
    override var mainUrl = "https://dsvplay.com"
}

class PencuriMixDropTop : MixDrop() {
    override var mainUrl = "https://mixdrop.top"
}

class PencuriMorencius : VidHidePro() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val loaded = try {
            withTimeoutOrNull(8_000L) {
                loadPackedPlayer(url, referer, name, subtitleCallback, callback)
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { false }
        if (!loaded) super.getUrl(url, referer, subtitleCallback, callback)
    }
}

/** Read the current JWPlayer links object without running its advertising/player JS. */
private suspend fun loadPackedPlayer(
    url: String,
    referer: String?,
    name: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6L)
    val unpacked = getAndUnpack(response.text)
    val scripts = unpacked + "\n" + response.document.select("script").joinToString("\n") { it.data() }
    fun clean(value: String): String = value.replace("\\/", "/").replace("\\u0026", "&")
        .replace("\\u003d", "=").replace("&amp;", "&")
    fun absolute(value: String): String? = runCatching {
        URI(response.url).resolve(clean(value)).toString()
    }.getOrNull()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    val objectSources = Regex("""["'](hls[234])["']\s*:\s*["']([^"']+)["']""")
        .findAll(scripts).mapNotNull { match ->
            absolute(match.groupValues[2])?.let { match.groupValues[1] to it }
        }.toList()
    // hls3 can point to an obfuscated master.txt. Prefer the real, native m3u8.
    val media = objectSources.firstOrNull { (_, value) -> URI(value).path.endsWith(".m3u8", true) }?.second
        ?: Regex("""(?i)["']?file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(scripts)?.groupValues?.getOrNull(1)?.let(::absolute)
        ?: return false

    fun field(text: String, key: String): String? = Regex(
        """["']?$key["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE
    ).find(text)?.groupValues?.getOrNull(1)
    Regex("""\{[^{}]*\}""").findAll(scripts).forEach { match ->
        val block = match.value
        val kind = field(block, "kind")
        if (kind.equals("captions", true) || kind.equals("subtitles", true)) {
            val subtitle = field(block, "file")?.let(::absolute)
            if (subtitle != null) subtitleCallback(SubtitleFile(field(block, "label") ?: "Subtitle", subtitle))
        }
    }
    callback(newExtractorLink(source = name, name = name, url = media, type = ExtractorLinkType.M3U8) {
        this.referer = response.url
        this.headers = mapOf("User-Agent" to USER_AGENT)
        this.quality = Qualities.Unknown.value
    })
    return true
}
