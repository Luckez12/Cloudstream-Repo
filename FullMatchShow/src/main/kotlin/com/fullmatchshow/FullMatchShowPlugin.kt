package com.fullmatchshow

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FullMatchShowPlugin : BasePlugin() {
    override fun load() {
        Log.w("FullMatchShow", "FULLMATCH_V5_LOADED version=5 flow=multi-mirror-tv-safe")
        registerMainAPI(FullMatchShow())
        // Bundle current FullMatchShows hosts so older Android TV Cloudstream builds
        // are not dependent on newly-added upstream extractors.
        registerExtractorAPI(FullMatchPlaymate())
        registerExtractorAPI(FullMatchPlaymogo())
        registerExtractorAPI(FullMatchMixDropTop())
        registerExtractorAPI(FullMatchMiixDropTop())
    }
}
