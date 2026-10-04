package com.lagradost.cloudstream3.utils
enum class ExtractorLinkType { M3U8, VIDEO, DASH }
enum class Qualities(val value: Int) { Unknown(400) }
data class ExtractorLink(val source: String, val name: String, val url: String, val type: ExtractorLinkType,
    var referer: String = "", var headers: Map<String,String> = emptyMap(), var quality: Int = 400,
    var extractorData: String? = null, var audioTracks: List<String> = emptyList())
suspend fun newExtractorLink(source: String, name: String, url: String, type: ExtractorLinkType,
    configure: ExtractorLink.() -> Unit): ExtractorLink = ExtractorLink(source,name,url,type).apply(configure)
