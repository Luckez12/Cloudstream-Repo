package com.fullmatchshow

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FullMatchShowPlugin : BasePlugin() {
    override fun load() {
        Log.w("FullMatchShow", "FULLMATCH_V3_LOADED version=3 flow=current-site-2026")
        registerMainAPI(FullMatchShow())
    }
}
