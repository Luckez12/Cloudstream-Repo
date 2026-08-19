package com.pencurimovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PencurimoviePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pencurimovie())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Hglink())
    }
}
