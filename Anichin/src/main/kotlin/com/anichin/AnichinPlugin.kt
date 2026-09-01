package com.anichin

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinPlugin : Plugin() {
    override fun load(context: Context) {
        Log.i("Anichin", "ANICHIN_PLUGIN_LOADED version=3")
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
    }
}
