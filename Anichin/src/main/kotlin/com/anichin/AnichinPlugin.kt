package com.anichin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnichinProvider())

        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())

        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())

        registerExtractorAPI(Rumble())

        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamRubyCom())
        registerExtractorAPI(StreamRubyNet())
    }
}
