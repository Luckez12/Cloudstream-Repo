package com.anichin

import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.newExtractorLink

open class StreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    private fun getFileCode(url: String): String? {
        val patterns = listOf(
            Regex("""embed-([A-Za-z0-9_-]+)\.html""", RegexOption.IGNORE_CASE),
            Regex("""/(?:e|embed)/([A-Za-z0-9_-]+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
    }

    private fun cleanMediaUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .trim()
    }

    private fun findM3u8(text: String): String? {
        val patterns = listOf(
            Regex(
                """["']?file["']?\s*:\s*["']([^"']*?\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """["'](https?://[^"']*?\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::cleanMediaUrl)
                ?.takeIf {
                    it.startsWith("https://", ignoreCase = true) ||
                        it.startsWith("http://", ignoreCase = true)
                }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = getFileCode(url) ?: return

        val response = runCatching {
            app.post(
                "$mainUrl/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to ""
                ),
                referer = referer ?: url
            )
        }.getOrNull() ?: return

        val unpacked = runCatching {
            if (!getPacked(response.text).isNullOrEmpty()) {
                getAndUnpack(response.text)
            } else {
                null
            }
        }.getOrNull()

        val sourceScript = response.document
            .selectFirst("script:containsData(sources:)")
            ?.data()

        val m3u8 = sequenceOf(
            unpacked,
            sourceScript,
            response.text
        ).filterNotNull()
            .mapNotNull(::findM3u8)
            .firstOrNull()
            ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        )
    }
}

class StreamRubyCom : StreamRuby() {
    override val mainUrl = "https://streamruby.com"
}

class StreamRubyNet : StreamRuby() {
    override val mainUrl = "https://streamruby.net"
}
