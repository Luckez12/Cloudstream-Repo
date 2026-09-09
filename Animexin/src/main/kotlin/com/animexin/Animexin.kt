package com.animexin

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)",
    )

    // AnimeXin currently returns 403 for poster requests made by Coil on some
    // Cloudstream builds. posterHeaders alone is not enough on those builds,
    // so fetch protected posters through Cloudstream's own HTTP client while
    // the AnimeXin page session/referer is active and hand Coil a data URI.
    private val imageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
            "Sec-Fetch-Dest" to "image",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "same-origin"
        )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterFetchSemaphore = Semaphore(5)

    private fun Element.getImageUrl(): String? {
        fun fromSrcset(value: String): String? = value
            .split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .filter { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            .lastOrNull()

        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            fromSrcset(attr("data-srcset")).orEmpty(),
            fromSrcset(attr("srcset")).orEmpty(),
            attr("src")
        ).firstOrNull { imageUrl ->
            imageUrl.isNotBlank() &&
                !imageUrl.startsWith("data:", ignoreCase = true)
        }
    }

    private fun guessImageMime(bytes: ByteArray, contentType: String?): String {
        val cleanType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (cleanType?.startsWith("image/") == true) return cleanType

        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private suspend fun resolvePosterUrl(rawUrl: String?, referer: String): String? {
        val fixedUrl = rawUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: return null

        if (!fixedUrl.contains("animexin.dev", ignoreCase = true)) {
            return fixedUrl
        }

        posterCache[fixedUrl]?.let { return it }

        return posterFetchSemaphore.withPermit {
            posterCache[fixedUrl]?.let { return@withPermit it }

            val inlinePoster = runCatching {
                val response = app.get(
                    fixedUrl,
                    referer = referer.ifBlank { "$mainUrl/" },
                    headers = imageHeaders,
                    timeout = 15L
                )

                if (!response.isSuccessful) {
                    Log.w(
                        "Animexin",
                        "ANIMEXIN_POSTER_FETCH code=${response.code} host=${runCatching { java.net.URI(fixedUrl).host }.getOrNull()}"
                    )
                    return@runCatching null
                }

                val declaredSize = response.size
                if (declaredSize != null && declaredSize > 2_500_000L) {
                    Log.w("Animexin", "ANIMEXIN_POSTER_FETCH skip=oversize bytes=$declaredSize")
                    return@runCatching null
                }

                val body = response.body
                val bytes = body.bytes()
                body.close()

                if (bytes.isEmpty() || bytes.size > 2_500_000) {
                    Log.w("Animexin", "ANIMEXIN_POSTER_FETCH skip=invalid-size bytes=${bytes.size}")
                    return@runCatching null
                }

                val mime = guessImageMime(bytes, response.headers["Content-Type"])
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUri = "data:$mime;base64,$encoded"
                Log.i("Animexin", "ANIMEXIN_POSTER_FETCH code=${response.code} bytes=${bytes.size} inline=true")
                dataUri
            }.onFailure { error ->
                Log.w("Animexin", "ANIMEXIN_POSTER_FETCH_FAIL type=${error::class.simpleName}")
            }.getOrNull()

            if (inlinePoster != null) {
                posterCache[fixedUrl] = inlinePoster
            }

            // Returning null is intentional when the protected fetch fails.
            // Falling back to the raw AnimeXin URL would only make Coil hit
            // the same confirmed HTTP 403 again.
            inlinePoster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "$mainUrl/${request.data}&page=$page"
        val document = app.get(pageUrl).document
        val home = coroutineScope {
            document.select("div.listupd > article")
                .map { element -> async { element.toSearchResult(pageUrl) } }
                .awaitAll()
                .filterNotNull()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private suspend fun Element.toSearchResult(pageReferer: String): SearchResponse? {
        val anchor = this.selectFirst("div.bsx > a[href], a[href]") ?: return null
        val title = anchor.attr("title").trim().ifBlank {
            this.selectFirst(".tt, h2, h3")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val href = fixUrl(anchor.attr("href"))
        val rawPoster = this.selectFirst("div.bsx > a img, img")?.getImageUrl()
        val posterUrl = resolvePosterUrl(rawPoster, pageReferer)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val pageUrl = "${mainUrl}/page/$page/?s=$query"
        val document = app.get(pageUrl).document
        val results = coroutineScope {
            document.select("div.listupd > article")
                .map { element -> async { element.toSearchResult(pageUrl) } }
                .awaitAll()
                .filterNotNull()
                .toNewSearchResponseList()
        }
        return results
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href=document.selectFirst("div.eplister > ul > li a")?.attr("href") ?:""
        val rawPoster = document.selectFirst("div.thumb img, div.ime img, img.wp-post-image")
            ?.getImageUrl()
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
        val poster = resolvePosterUrl(rawPoster, url)
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type=document.selectFirst(".spe")?.text().toString()
        val tvtag=if (type.contains("Movie")) TvType.Movie else TvType.TvSeries
        return if (tvtag == TvType.TvSeries) {
            val episodeRegex = Regex("(\\d+)")

            val episodes = coroutineScope {
                document.select("div.eplister > ul > li").map { info ->
                    async {
                        val href1 = info.select("a").attr("href")
                        val rawEpisodePoster = info.selectFirst("a img")?.getImageUrl()
                        val posterr = if (rawEpisodePoster != null) {
                            resolvePosterUrl(rawEpisodePoster, url) ?: poster
                        } else {
                            poster
                        }

                        val epText = info.selectFirst("div.epl-num")?.text().orEmpty()
                        val epnum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

                        newEpisode(href1) {
                            this.episode = epnum
                            this.name = epnum?.let { "Episode $it" } ?: epText
                            this.posterUrl = posterr
                        }
                    }
                }.awaitAll()
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select(".mobius option").forEach { server->
            val base64 = server.attr("value")
            val decoded=base64Decode(base64)
            val doc = Jsoup.parse(decoded)
            val href=doc.select("iframe").attr("src")
            val url=Http(href)
            loadExtractor(url,subtitleCallback, callback)
        }
        return true
    }
}
