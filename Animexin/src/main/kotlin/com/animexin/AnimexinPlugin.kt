package com.animexin

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion

@CloudstreamPlugin
class AnimexinPlugin: BasePlugin() {
    override fun load() {
        Log.w("Animexin", "ANIMEXIN_V13_LOADED version=13 flow=anichin-fast hardsub=id-en unknown=accept")
        registerMainAPI(Animexin())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
    }
}
