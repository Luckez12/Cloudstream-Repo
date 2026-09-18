package com.moviebox

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieboxPlugin : BasePlugin() {
    override fun load() {
        Log.i("MovieBox", "MOVIEBOX_PLUGIN_LOADED version=20 search=original playback=h5-preserved-plus-js-resource-fallback captions=original-h5")
        registerMainAPI(MovieboxProvider())
    }
}
