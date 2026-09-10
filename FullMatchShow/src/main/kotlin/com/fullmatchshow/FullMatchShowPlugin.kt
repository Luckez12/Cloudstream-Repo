package com.fullmatchshow

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FullMatchShowPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FullMatchShow())
    }
}
