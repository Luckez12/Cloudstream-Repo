package com.kshow123

import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidHidePro

class KShowHglink : StreamWishExtractor() {
    override var mainUrl = "https://hglink.to"
    override var name = "StreamWish"
}

class KShowMinochinos : VidHidePro() {
    override var mainUrl = "https://minochinos.com"
    override var name = "Vidhide"
}

class KShowMixdrop : MixDrop() {
    override var mainUrl = "https://mixdrop.ps"
    override var name = "Mixdrop"
}

class KShowWatchads : StreamTape() {
    override var mainUrl = "https://watchadsontape.com"
    override var name = "StreamTape"
}
