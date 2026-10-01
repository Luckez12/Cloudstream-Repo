package com.pencurimovie

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PencurimoviePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Pencurimovie())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Hgnative("https://hanerix.com"))
        registerExtractorAPI(Hgnative("https://audinifer.com"))
        registerExtractorAPI(Hgnative("https://vibuxer.com"))
        registerExtractorAPI(PencuriMixDropTop())
        registerExtractorAPI(PencuriMorencius())
    }
}
