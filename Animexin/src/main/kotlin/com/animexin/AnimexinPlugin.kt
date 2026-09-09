package com.animexin

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimexinPlugin : BasePlugin() {
    override fun load() {
        Log.i("Animexin", "ANIMEXIN_PLUGIN_LOADED version=2 flow=current-site-player-sync")
        registerMainAPI(Animexin())

        // Current player compatibility and common mirror hosts.
        registerExtractorAPI(AnimexinDailymotion())
        registerExtractorAPI(AnimexinGeoDailymotion())
        registerExtractorAPI(AnimexinOkRuSSL())
        registerExtractorAPI(AnimexinOkRuWWW())
        registerExtractorAPI(AnimexinOkRuHTTP())
        registerExtractorAPI(AnimexinOdnoklassniki())
        registerExtractorAPI(AnimexinOdnoklassnikiWWW())
        registerExtractorAPI(AnimexinRumble())

        // Older AnimeXin episodes can still contain these hosts.
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
    }
}
