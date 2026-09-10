package com.fullmatchshow

import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

private const val FULLMATCH_UA = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/**
 * Local Playmate extractor so FullMatchShow does not depend on the TV having a
 * Cloudstream build new enough to include the upstream Playmate extractor.
 */
class FullMatchPlaymate : ExtractorApi() {
    override var name = "FullMatch Playmate"
    override var mainUrl = "https://playmate.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast('/').substringBefore('?').trim()
        if (id.isBlank()) return

        val response = runCatching {
            app.post(
                "$mainUrl/api/s",
                json = mapOf("c" to id, "d" to "web"),
                headers = mapOf("User-Agent" to FULLMATCH_UA)
            )
        }.getOrNull() ?: return

        val raw = Regex("""[\"']sx[\"']\s*:\s*[\"']([^\"']+)[\"']""")
            .find(response.text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val media = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .trim()

        if (!media.startsWith("http", ignoreCase = true)) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = media,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to FULLMATCH_UA)
            }
        )
    }
}

/**
 * PlayMogo is a DoodStream-compatible mirror. Bundle the current extraction
 * flow locally because older TV app builds may not know the PlayMogo domain.
 */
class FullMatchPlaymogo : ExtractorApi() {
    override var name = "FullMatch PlayMogo"
    override var mainUrl = "https://playmogo.com"
    override val requiresReferer = false

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url
            .replace("/d/", "/e/")
            .replace("/f/", "/e/")

        val page = runCatching {
            app.get(
                embedUrl,
                referer = referer,
                headers = mapOf("User-Agent" to FULLMATCH_UA)
            )
        }.getOrNull() ?: return

        val html = page.text
        val passPath = Regex("""/pass_md5/[^'\"\s<]+""")
            .find(html)
            ?.value
            ?: return

        val origin = runCatching {
            val uri = URI(page.url)
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: mainUrl

        val passUrl = if (passPath.startsWith("http")) passPath else "$origin$passPath"
        val prefix = runCatching {
            app.get(
                passUrl,
                referer = page.url,
                headers = mapOf("User-Agent" to FULLMATCH_UA)
            ).text.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        val token = passUrl.substringAfterLast('/')
        val randomPart = buildString {
            repeat(10) { append(alphabet.random()) }
        }
        val media = "$prefix$randomPart?token=$token"
        val qualityName = Regex("""\d{3,4}[pP]""")
            .find(html.substringAfter("<title>", "").substringBefore("</title>", ""))
            ?.value

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = media,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = getQualityFromName(qualityName)
                this.headers = mapOf("User-Agent" to FULLMATCH_UA)
            }
        )
    }
}

/** MixDrop's current FullMatchShows links use .top / miixdrop.top mirrors. */
open class FullMatchMixDropTop : ExtractorApi() {
    override var name = "FullMatch MixDrop"
    override var mainUrl = "https://mixdrop.top"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replaceFirst("/f/", "/e/")
        val response = runCatching {
            app.get(
                embedUrl,
                referer = referer,
                headers = mapOf("User-Agent" to FULLMATCH_UA)
            )
        }.getOrNull() ?: return

        val unpacked = getAndUnpack(response.text)
        val raw = Regex("""wurl.*?=.*?[\"'](.*?)[\"'];""", RegexOption.DOT_MATCHES_ALL)
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?: return

        val media = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            else -> return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = media,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = response.url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to FULLMATCH_UA)
            }
        )
    }
}

class FullMatchMiixDropTop : FullMatchMixDropTop() {
    override var mainUrl = "https://miixdrop.top"
}
