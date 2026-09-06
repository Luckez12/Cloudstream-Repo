package com.oppadrama

import android.content.Context
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OppadramaProviderPlugin : BasePlugin() {
    override fun load() {
        OppaRuntime.context = (getContext() as? Context)?.applicationContext
        registerMainAPI(OppadramaProvider())
    }
}
