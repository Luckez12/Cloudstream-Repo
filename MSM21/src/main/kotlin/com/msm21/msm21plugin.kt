package com.msm21

import android.content.Context
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

object MsmRuntime {
    @Volatile
    var context: Context? = null
}

@CloudstreamPlugin
class msm21plugin : BasePlugin() {
    override fun load() {
        MsmRuntime.context = (getContext() as? Context)?.applicationContext

        registerMainAPI(msm21())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Bysesukior())
        registerExtractorAPI(MixDropTop())
    }
}
