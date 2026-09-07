package com.anichin

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinPlugin : BasePlugin() {
    override fun load() {
        Log.i("Anichin", "ANICHIN_PLUGIN_LOADED version=15")
        registerMainAPI(AnichinProvider())

        // Dailymotion/GeoDailymotion and OK.ru/Odnoklassniki are built into
        // the current Cloudstream library. Do not re-register local copies,
        // otherwise these stale aliases override the maintained built-ins.

        registerExtractorAPI(Rumble())
        registerExtractorAPI(Morencius())

        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamRubyCom())
        registerExtractorAPI(StreamRubyNet())

        registerExtractorAPI(AnichinStream())
        registerExtractorAPI(AnichinPlayer())
    }
}
