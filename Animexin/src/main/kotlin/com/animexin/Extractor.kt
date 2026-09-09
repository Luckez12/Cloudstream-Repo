package com.animexin

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = app.get(url, referer = mainUrl).document
        val packed = document
            .selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            .orEmpty()

        val unpacked = JsUnpacker(packed).unpack() ?: return null
        val streamUrl = Regex(
            """sources:\\[\\{file:["'](.*?)["']""",
            RegexOption.IGNORE_CASE
        ).find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex(
                """file:\s*["'](https?://[^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(unpacked)
                ?.groupValues
                ?.getOrNull(1)
            ?: return null

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: ""
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class wishfast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}
