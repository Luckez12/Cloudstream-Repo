package com.oppadrama

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OppadramaProviderPlugin : BasePlugin() {
    override fun load() {
        Log.i("OppaDrama", "OPPADRAMA_PLUGIN_LOADED version=4")
        OppaRuntime.resolveContext()
        registerMainAPI(OppadramaProvider())
    }
}
