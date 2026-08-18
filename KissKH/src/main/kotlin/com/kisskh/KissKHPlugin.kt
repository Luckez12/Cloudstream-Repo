package com.kisskh

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHPlugin: Plugin() {
    override fun load() {
        registerMainAPI(KissKH())
    }
}
