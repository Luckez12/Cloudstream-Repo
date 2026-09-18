package com.moviebox

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieboxPlugin : BasePlugin() {
    override fun load() {
        Log.i("MovieBox", "MOVIEBOX_PLUGIN_LOADED version=18 searchFlow=original-android-jsonnode playback=h5-720plus-validated-resource-repair captions=original-h5")
        registerMainAPI(MovieboxProvider())
    }
}
