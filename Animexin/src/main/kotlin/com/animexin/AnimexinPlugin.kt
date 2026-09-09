package com.animexin

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion

@CloudstreamPlugin
class AnimexinPlugin: BasePlugin() {
    override fun load() {
        Log.w("Animexin", "ANIMEXIN_V12_LOADED version=12 playerFlow=hardsub-id-en minQuality=720 unknown400=sentinel hlsMaster=expand-720plus")
        registerMainAPI(Animexin())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
    }
}
