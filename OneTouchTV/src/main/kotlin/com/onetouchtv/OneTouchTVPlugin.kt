package com.OneTouchTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class OneTouchTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OneTouchTV())
    }
}
