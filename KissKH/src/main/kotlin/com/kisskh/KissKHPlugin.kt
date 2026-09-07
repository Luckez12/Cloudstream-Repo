package com.kisskh

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KissKHPlugin : BasePlugin() {
    override fun load() {
        Log.i("KissKH", "KISSKH_PLUGIN_LOADED version=3")
        registerMainAPI(KissKH())
    }
}
