package com.animextv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeXTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeXTVProvider())
    }
}
