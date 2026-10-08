package com.kshow123

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KShow123Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KShow123Provider())
        Log.i("KShow123", "KSHOW123_PLUGIN_LOADED version=1 stage=homepage")
    }
}
