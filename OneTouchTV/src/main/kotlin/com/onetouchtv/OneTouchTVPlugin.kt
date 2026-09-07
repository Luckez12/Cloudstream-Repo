package com.OneTouchTV

import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class OneTouchTVPlugin : BasePlugin() {
    override fun load() {
        Log.i("OneTouchTV", "ONETOUCHTV_PLUGIN_LOADED version=3")
        registerMainAPI(OneTouchTV())
    }
}
