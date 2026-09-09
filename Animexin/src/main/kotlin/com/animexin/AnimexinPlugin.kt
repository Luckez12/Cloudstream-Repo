package com.animexin

import android.util.Log
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimexinPlugin : BasePlugin() {
    override fun load() {
        Log.w("Animexin", "ANIMEXIN_V14_LOADED flow=fast-clean hardsub=id-en unknown=accept")
        registerMainAPI(Animexin())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
    }
}
