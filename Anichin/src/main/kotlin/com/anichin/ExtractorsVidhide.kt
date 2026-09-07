package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException

class Morencius : VidHidePro() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        AdaptiveTrackGrouping.findMasterFromPage(
            pageUrl = url,
            referer = referer,
            sourceName = name
        )?.let {
            callback(it)
            return
        }

        val links = mutableListOf<ExtractorLink>()

        try {
            super.getUrl(
                url,
                referer,
                subtitleCallback
            ) { link ->
                links += link
            }
        } catch (e: CancellationException) {
            throw e
        }

        AdaptiveTrackGrouping
            .preferMaster(links, name)
            .forEach(callback)
    }
}

class EmturbovidAdaptive : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        AdaptiveTrackGrouping.findMasterFromPage(
            pageUrl = url,
            referer = referer,
            sourceName = name
        )?.let {
            callback(it)
            return
        }

        val links = mutableListOf<ExtractorLink>()

        try {
            super.getUrl(
                url,
                referer,
                subtitleCallback
            ) { link ->
                links += link
            }
        } catch (e: CancellationException) {
            throw e
        }

        AdaptiveTrackGrouping
            .preferMaster(links, name)
            .forEach(callback)
    }
}
