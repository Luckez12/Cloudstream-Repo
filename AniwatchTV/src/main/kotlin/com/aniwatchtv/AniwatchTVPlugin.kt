package com.aniwatchtv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniwatchTVPlugin : BasePlugin() {
    override fun load() { registerMainAPI(AniwatchTVProvider()) }
}
