package com.oppadrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OppadramaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        OppaRuntime.context = context.applicationContext
        registerMainAPI(OppadramaProvider())
    }
}
