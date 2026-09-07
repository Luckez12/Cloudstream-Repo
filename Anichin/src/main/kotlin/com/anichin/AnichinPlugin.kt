package com.anichin

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinPlugin : BasePlugin() {
    override fun load() {
        Log.i("Anichin", "ANICHIN_PLUGIN_LOADED version=16")
        registerMainAPI(AnichinProvider())

        // Custom host fixes for Anichin:
        // Dailymotion keeps the master HLS so audio is preserved.
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())

        // OK.ru supports both current ok.ru and legacy odnoklassniki.ru embeds.
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())

        registerExtractorAPI(Rumble())
        registerExtractorAPI(Morencius())

        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamRubyCom())
        registerExtractorAPI(StreamRubyNet())

        registerExtractorAPI(AnichinStream())
        registerExtractorAPI(AnichinPlayer())
    }
}
