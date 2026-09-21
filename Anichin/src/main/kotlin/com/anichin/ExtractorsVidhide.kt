package com.anichin

import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro

class Morencius : VidHidePro() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"
}

class EmturbovidNative : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}
