package com.anichin

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinPlugin : BasePlugin() {
    override fun load() {
        Log.i("Anichin", "ANICHIN_PLUGIN_LOADED version=10")
        registerMainAPI(AnichinProvider())

        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())

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
