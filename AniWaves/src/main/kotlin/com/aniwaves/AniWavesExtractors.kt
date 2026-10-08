package com.aniwaves

import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.DoodLaExtractor

class AniWavesByse : ByseSX() {
    override val mainUrl = "https://mfw09.org"
    override val name = "AniWaves Byse"
}

class AniWavesDood : DoodLaExtractor() {
    override var mainUrl = "https://playmogo.com"
    override var name = "AniWaves DoodStream"
}
