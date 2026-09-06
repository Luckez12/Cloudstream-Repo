package com.oppadrama

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OppadramaProviderPlugin : BasePlugin() {
    override fun load() {
        OppaRuntime.resolveContext()
        registerMainAPI(OppadramaProvider())
    }
}
