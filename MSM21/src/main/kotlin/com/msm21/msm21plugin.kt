package com.msm21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

object MsmRuntime {
    @Volatile
    var context: Context? = null
}

@CloudstreamPlugin
class msm21plugin : Plugin() {
    override fun load(context: Context) {
        MsmRuntime.context = context.applicationContext

        registerMainAPI(msm21())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Bysesukior())
        registerExtractorAPI(MixDropTop())
    }
}
