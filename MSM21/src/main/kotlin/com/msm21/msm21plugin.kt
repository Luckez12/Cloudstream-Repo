package com.msm21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class msm21plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(msm21())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Hglink())
    }
}
