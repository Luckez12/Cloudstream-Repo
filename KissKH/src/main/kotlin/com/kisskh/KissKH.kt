package com.kisskh

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.util.ArrayList

class KissKH : MainAPI() {
    override var mainUrl = "https://kisskh.id"
    override var name = "KissKH"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=2" to "Latest Releases",
        "&type=0&sub=0&country=2&status=0&order=1" to "Best Korean Dramas",
        "&type=0&sub=0&country=1&status=0&order=1" to "Best Chinese Dramas",
        "&type=2&sub=0&country=2&status=0&order=1" to "Popular Movies",
        "&type=2&sub=0&country=2&status=0&order=2" to "Latest Updated Movies",
        "&type=1&sub=0&country=2&status=0&order=1" to "Popular TV Series",
        "&type=1&sub=0&country=2&status=0&order=2" to "Latest Updated TV Series",
        "&type=3&sub=0&country=0&status=0&order=1" to "Popular Anime",
        "&type=3&sub=0&country=0&status=0&order=2" to "Latest Updated Anime",
        "&type=4&sub=0&country=0&status=0&order=1" to "Popular Hollywood",
        "&type=4&sub=0&country=0&status=0&order=2" to "Latest Updated Hollywood",
        "&type=0&sub=0&country=0&status=3&order=2" to "Coming Soon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = app.get(
            "$mainUrl/api/DramaList/List?page=$page${request.data}",
            referer = "$mainUrl/"
        ).parsedSafe<Responses>()?.data
            ?.mapNotNull { it.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid KissKH response")

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (!settingsForProvider.enableAdult && label?.contains("RAW", ignoreCase = true) == true) {
            return null
        }

        val mediaTitle = title ?: return null
        val mediaId = id ?: return null

        return newAnimeSearchResponse(
            mediaTitle,
            "${getTitle(mediaTitle)}/$mediaId",
            TvType.TvSeries
        ) {
            posterUrl = thumbnail
            posterHeaders = mapOf("User-Agent" to USER_AGENT)
            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val response = app.get(
            "$mainUrl/api/DramaList/Search?q=$q&type=0",
            referer = "$mainUrl/"
        ).text

        return tryParseJson<ArrayList<Media>>(response)
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val dramaId = url.substringAfterLast("/")
        val slug = url.substringBeforeLast("/").substringAfterLast("/")

        val res = app.get(
            "$mainUrl/api/DramaList/Drama/$dramaId?isq=false",
            referer = "$mainUrl/Drama/$slug?id=$dramaId"
        ).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid KissKH drama response")

        val episodes = res.episodes?.mapNotNull { eps ->
            val epsId = eps.id ?: return@mapNotNull null
            val number = formatEpisodeNumber(eps.number)

            newEpisode(
                Data(res.title, eps.number, res.id, epsId).toJson()
            ) {
                name = "Episode $number"
            }
        } ?: throw ErrorLoadingException("No episodes found")

        return newTvSeriesLoadResponse(
            res.title ?: return null,
            url,
            if (res.type == "Movie" || episodes.size == 1) TvType.Movie else TvType.TvSeries,
            episodes.reversed()
        ) {
            posterUrl = res.thumbnail?.trim()
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
            year = res.releaseDate?.substringBefore("-")?.toIntOrNull()
            plot = res.description
            tags = listOfNotNull(res.country, res.status, res.type).filter { it.isNotBlank() }
            showStatus = when (res.status) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    private fun getTitle(str: String): String = str
        .replace(Regex("[^a-zA-Z0-9]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

    private fun formatEpisodeNumber(number: Double?): String {
        if (number == null) return ""
        return if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
    }

    private fun getLanguage(str: String): String = when (str.trim()) {
        "Indonesia" -> "Indonesian"
        else -> str.trim()
    }

    private fun inferQuality(url: String): Int = when {
        url.contains("1080", ignoreCase = true) -> Qualities.P1080.value
        url.contains("720", ignoreCase = true) -> Qualities.P720.value
        else -> Qualities.Unknown.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<Data>(data) ?: return false
        val episodeId = loadData.epsId ?: return false

        Log.d(TAG, "loadLinks episodeId=$episodeId")

        val keyUrls = listOf(
            "$VIDEO_KEY_API$episodeId&version=$KISSKH_VERSION",
            "$SUBTITLE_KEY_API$episodeId&version=$KISSKH_VERSION"
        )

        val keys = keyUrls.amap { url ->
            try {
                app.get(url, timeout = 8000).parsedSafe<Key>()?.key.orEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "kkey request failed: ${e.message}")
                ""
            }
        }

        val videoKey = keys.getOrNull(0).orEmpty()
        val subtitleKey = keys.getOrNull(1).orEmpty()

        var streamFound = false
        var subtitleFound = false

        if (videoKey.isNotBlank()) {
            val kkey = URLEncoder.encode(videoKey, "UTF-8")
            val episodeNumber = formatEpisodeNumber(loadData.eps)
            val slug = getTitle(loadData.title.orEmpty())
            val videoApi = "$mainUrl/api/DramaList/Episode/$episodeId.png?err=false&ts=&time=&kkey=$kkey"
            val referer = "$mainUrl/Drama/$slug/Episode-$episodeNumber?id=${loadData.id}&ep=$episodeId&page=0&pageSize=100"

            val source = try {
                app.get(videoApi, referer = referer, timeout = 10000).parsedSafe<Sources>()
            } catch (e: Exception) {
                Log.e(TAG, "Video API failed: ${e.message}")
                null
            }

            source?.let {
                Log.d(TAG, "Video=${it.video}")
                Log.d(TAG, "ThirdParty=${it.thirdParty}")

                listOfNotNull(it.video, it.thirdParty)
                    .map { link -> link.trim() }
                    .filter { link -> link.isNotBlank() }
                    .distinct()
                    .amap { link ->
                        safeApiCall {
                            when {
                                link.contains(".m3u8", ignoreCase = true) -> {
                                    M3u8Helper.generateM3u8(
                                        name,
                                        fixUrl(link),
                                        referer = "$mainUrl/",
                                        headers = mapOf("Origin" to mainUrl)
                                    ).forEach(callback)
                                    streamFound = true
                                }

                                link.contains(".mp4", ignoreCase = true) -> {
                                    callback.invoke(
                                        newExtractorLink(
                                            name,
                                            name,
                                            url = fixUrl(link),
                                            INFER_TYPE
                                        ) {
                                            quality = inferQuality(link)
                                            headers = mapOf(
                                                "Referer" to "$mainUrl/",
                                                "Origin" to mainUrl
                                            )
                                        }
                                    )
                                    streamFound = true
                                }

                                link.startsWith("http", ignoreCase = true) -> {
                                    loadExtractor(
                                        link,
                                        "$mainUrl/",
                                        subtitleCallback,
                                        callback
                                    )
                                    streamFound = true
                                }
                            }
                        }
                    }
            }
        } else {
            Log.e(TAG, "Video kkey is empty")
        }

        if (subtitleKey.isNotBlank()) {
            val kkey = URLEncoder.encode(subtitleKey, "UTF-8")
            val subApi = "$mainUrl/api/Sub/$episodeId?kkey=$kkey"

            try {
                val subtitles = tryParseJson<List<Subtitle>>(
                    app.get(subApi, referer = "$mainUrl/", timeout = 10000).text
                ).orEmpty()

                subtitles.forEach { sub ->
                    val src = sub.src?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    val language = getLanguage(sub.label ?: "Unknown")
                    subtitleCallback.invoke(newSubtitleFile(language, fixUrl(src)))
                    subtitleFound = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subtitle API failed: ${e.message}")
            }
        } else {
            Log.e(TAG, "Subtitle kkey is empty")
        }

        return streamFound || subtitleFound
    }

    private val chunkRegex by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = chain.proceed(chain.request())
                val url = response.request.url.toString()

                if (!url.contains(".txt", ignoreCase = true)) return response

                val contentType = response.body.contentType()
                val encrypted = response.body.string()
                val chunks = encrypted.split(chunkRegex)
                    .filter { it.isNotBlank() }
                    .map { it.trim() }

                val decrypted = chunks.mapIndexedNotNull { index, chunk ->
                    val parts = chunk.split("\n")
                    if (parts.isEmpty()) return@mapIndexedNotNull null

                    val timeCode = parts.first()
                    val text = parts.drop(1).mapNotNull { line ->
                        if (line.isBlank()) return@mapNotNull ""
                        try {
                            decrypt(line)
                        } catch (e: Exception) {
                            Log.e("KISSKH_SUB", "Decrypt failed: ${e.message}")
                            null
                        }
                    }.joinToString("\n")

                    if (text.isBlank()) return@mapIndexedNotNull null
                    listOf(index + 1, timeCode, text).joinToString("\n")
                }.joinToString("\n\n")

                return response.newBuilder()
                    .body(decrypted.toResponseBody(contentType))
                    .build()
            }
        }
    }

    companion object {
        private const val TAG = "KISSKH"
        private const val KISSKH_VERSION = "2.8.10"
        private const val VIDEO_KEY_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        private const val SUBTITLE_KEY_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"
    }
}

data class Media(
    @param:JsonProperty("episodesCount") val episodesCount: Int?,
    @param:JsonProperty("thumbnail") val thumbnail: String?,
    @param:JsonProperty("label") val label: String?,
    @param:JsonProperty("id") val id: Int?,
    @param:JsonProperty("title") val title: String?
)

data class Data(
    @param:JsonProperty("title") val title: String?,
    @param:JsonProperty("eps") val eps: Double?,
    @param:JsonProperty("id") val id: Int?,
    @param:JsonProperty("epsId") val epsId: Int?
)

data class Sources(
    @param:JsonProperty("Video") val video: String?,
    @param:JsonProperty("ThirdParty") val thirdParty: String?
)

data class Subtitle(
    @param:JsonProperty("src") val src: String?,
    @param:JsonProperty("label") val label: String?
)

data class Responses(
    @param:JsonProperty("data") val data: ArrayList<Media>? = arrayListOf()
)

data class Episodes(
    @param:JsonProperty("id") val id: Int?,
    @param:JsonProperty("number") val number: Double?,
    @param:JsonProperty("sub") val sub: Int?
)

data class MediaDetail(
    @param:JsonProperty("description") val description: String?,
    @param:JsonProperty("releaseDate") val releaseDate: String?,
    @param:JsonProperty("status") val status: String?,
    @param:JsonProperty("type") val type: String?,
    @param:JsonProperty("country") val country: String?,
    @param:JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    @param:JsonProperty("thumbnail") val thumbnail: String?,
    @param:JsonProperty("id") val id: Int?,
    @param:JsonProperty("title") val title: String?
)

data class Key(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("version") val version: String? = null,
    @param:JsonProperty("key") val key: String? = null
)
