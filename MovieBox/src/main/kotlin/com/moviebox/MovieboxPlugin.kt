package com.moviebox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieboxPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MovieboxProvider())
    }
}
