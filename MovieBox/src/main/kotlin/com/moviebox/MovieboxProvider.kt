package com.moviebox

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CancellationException

class MovieboxProvider : MainAPI() {

    override var mainUrl = "https://movieboxhd.net"
    override var name = "MovieBox 👾"
    override var lang = "en"

    override val instantLinkLoading = true
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    /*
     * H5 API V2 is the structured REST backend used for discovery/filtering.
     * Keep this separate from the Android API cluster (api3/api4/api5/api6...).
     * The Android hosts use a different /wefeed-mobile-bff API surface.
     */
    private val h5ApiUrl = "https://h5-api.aoneroom.com"

    /*
     * H5 web mirrors. moviebox.ph currently redirects its public website to
     * movieboxhd.net, so prefer the current public host first and keep the
     * older mirrors only as fallbacks for the H5 API surface.
     */
    private val webHosts = listOf(
        "https://movieboxhd.net",
        "https://moviebox.ph",
        "https://moviebox.pk",
        "https://moviebox.ng",
        "https://filmboom.top"
    )

    /*
     * Mirror strategy:
     * search and detail try the preferred/current host first, then fall back
     * to the remaining mirrors. The first valid response wins and the host is
     * remembered for later requests.
     */
    @Volatile
    private var preferredWebHost: String? = null

    private val searchHostTimeoutSeconds = 4L
    private val detailHostTimeoutSeconds = 5L
    private val playHostTimeoutSeconds = 4L
    private val captionHostTimeoutSeconds = 3L
    private val recommendationTimeoutSeconds = 3L

    private fun orderedWebHosts(seedHost: String? = null): List<String> = buildList {
        seedHost?.takeIf { it.isNotBlank() }?.let { add(it) }
        preferredWebHost?.takeIf { it.isNotBlank() && !contains(it) }?.let { add(it) }
        webHosts.forEach { host ->
            if (!contains(host)) add(host)
        }
    }

    private val commonHeaders = mapOf(
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Client-Info" to "{\"timezone\":\"Asia/Kuala_Lumpur\"}",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"
    )

    override val mainPage: List<MainPageData> = mainPageOf(
        "872031290915189720" to "Trending Now",
        "997144265920760504" to "Popular Movie",
        "5283462032510044280" to "Latest Indonesian Drama",
        "6528093688173053896" to "Trending Indonesian Movies",
        "4380734070238626200" to "K-Drama",
        "7736026911486755336" to "Western TV",
        "8624142774394406504" to "Most Popular C-Drama",
        "5404290953194750296" to "Trending Anime",
        "5848753831881965888" to "Indonesian Horror Stories",
        "1164329479448281992" to "Thai-Drama",
        "7132534597631837112" to "Animated Film",
        "1,ForYou" to "Movie ForYou",
        "1,Hottest" to "Movie Hottest",
        "1,Latest" to "Movie Latest",
        "1,Rating" to "Movie Rating",
        "2,ForYou" to "TVShow ForYou",
        "2,Hottest" to "TVShow Hottest",
        "2,Latest" to "TVShow Latest",
        "2,Rating" to "TVShow Rating",
        "1006,ForYou" to "Animation ForYou",
        "1006,Hottest" to "Animation Hottest",
        "1006,Latest" to "Animation Latest",
        "1006,Rating" to "Animation Rating"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {

        val items = if (!request.data.contains(",")) {
            app.get(
                "$h5ApiUrl/wefeed-h5api-bff/ranking-list/content?id=${request.data}&page=$page&perPage=24",
                headers = commonHeaders
            ).parsedSafe<Media>()
                ?.data
                ?.subjectList
                .orEmpty()
        } else {
            val params = request.data.split(",", limit = 2)
            val channelId = params.getOrNull(0)?.toIntOrNull() ?: 1
            val sort = params.getOrNull(1).orEmpty().ifBlank { "ForYou" }

            val body = mapOf(
                "channelId" to channelId,
                "page" to page,
                "perPage" to 28,
                "sort" to sort,
                "genre" to "All",
                "country" to "All",
                "year" to "All",
                "classify" to "All"
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            app.post(
                "$h5ApiUrl/wefeed-h5api-bff/subject/filter",
                headers = commonHeaders,
                requestBody = body
            ).parsedSafe<Media>()
                ?.data
                ?.items
                .orEmpty()
        }

        if (items.isEmpty()) {
            throw ErrorLoadingException("MovieBox returned no data")
        }

        return newHomePageResponse(
            request.name,
            items.map { it.toSearchResponse(this) }
        )
    }

    private data class SearchRaceResult(
        val host: String,
        val items: List<Items>
    )

    private data class DetailRaceResult(
        val host: String,
        val detail: MediaDetail.Data
    )

    private suspend fun raceSearchHosts(
        query: String
    ): SearchRaceResult? {
        val hosts = orderedWebHosts()
        if (hosts.isEmpty()) return null

        val requestJson = mapOf(
            "keyword" to query.trim(),
            "page" to 1,
            "perPage" to 24,
            "subjectType" to 0
        ).toJson()

        for (host in hosts) {
            try {
                val requestBody = requestJson.toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()
                )

                val items = app.post(
                    "$host/wefeed-h5-bff/web/subject/search",
                    headers = commonHeaders,
                    referer = "$host/",
                    requestBody = requestBody,
                    timeout = searchHostTimeoutSeconds
                ).parsedSafe<Media>()
                    ?.data
                    ?.items
                    .orEmpty()

                if (items.isNotEmpty()) {
                    return SearchRaceResult(host, items)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Dead/blocked mirror: continue to the next host.
            }
        }

        return null
    }

    private suspend fun raceDetailHosts(
        subjectId: String
    ): DetailRaceResult? {
        val hosts = orderedWebHosts()
        if (hosts.isEmpty()) return null

        for (host in hosts) {
            try {
                val detail = app.get(
                    "$host/wefeed-h5-bff/web/subject/detail?subjectId=$subjectId",
                    headers = commonHeaders,
                    referer = "$host/",
                    timeout = detailHostTimeoutSeconds
                ).parsedSafe<MediaDetail>()?.data

                if (detail?.subject != null) {
                    return DetailRaceResult(host, detail)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Dead/blocked mirror: continue to the next host.
            }
        }

        return null
    }

    private suspend fun loadRecommendationsFast(
        subjectId: String,
        host: String
    ): List<SearchResponse>? {
        return try {
            app.get(
                "$host/wefeed-h5-bff/web/subject/detail-rec?subjectId=$subjectId&page=1&perPage=12",
                headers = commonHeaders,
                referer = "$host/",
                timeout = recommendationTimeoutSeconds
            ).parsedSafe<Media>()
                ?.data
                ?.items
                ?.map { it.toSearchResponse(this@MovieboxProvider) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val result = raceSearchHosts(query) ?: return emptyList()
        preferredWebHost = result.host
        return result.items.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").substringBefore("?")
        if (id.isBlank()) throw ErrorLoadingException("Invalid MovieBox subject id")

        val detailResult = raceDetailHosts(id)
            ?: throw ErrorLoadingException("MovieBox detail unavailable")
        val selectedHost = detailResult.host
        val detail = detailResult.detail
        preferredWebHost = selectedHost

        val subject = detail.subject ?: throw ErrorLoadingException("MovieBox subject missing")

        val title = subject.title.orEmpty().ifBlank { "MovieBox" }
        val poster = subject.cover?.url
        val tags = subject.genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }

        val year = subject.releaseDate
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tvType = if (subject.subjectType == 2) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val description = subject.description
        val trailer = subject.trailer?.videoAddress?.url
        val rating = subject.imdbRatingValue?.toDoubleOrNull()

        val actors = detail.stars
            ?.mapNotNull { cast ->
                val actorName = cast.name?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                ActorData(
                    Actor(actorName, cast.avatarUrl),
                    roleString = cast.character
                )
            }
            ?.distinctBy { it.actor }

        /*
         * Recommendations are optional UI data. Keep them on a very short
         * best-effort timeout so they never turn into a multi-second blocker
         * after the critical detail race has already succeeded.
         */
        val recommendations = loadRecommendationsFast(id, selectedHost)

        return if (tvType == TvType.TvSeries) {
            val episodes = detail.resource
                ?.seasons
                .orEmpty()
                .flatMap { season ->
                    val episodeNumbers = season.allEp
                        ?.split(",")
                        ?.mapNotNull { it.trim().toIntOrNull() }
                        ?.filter { it > 0 }
                        ?.distinct()
                        ?.sorted()
                        ?.takeIf { it.isNotEmpty() }
                        ?: season.maxEp
                            ?.takeIf { it > 0 }
                            ?.let { (1..it).toList() }
                            .orEmpty()

                    episodeNumbers.map { episodeNumber ->
                        newEpisode(
                            LoadData(
                                id = id,
                                season = season.se,
                                episode = episodeNumber,
                                detailPath = subject.detailPath,
                                apiHost = selectedHost
                            ).toJson()
                        ) {
                            this.season = season.se
                            this.episode = episodeNumber
                        }
                    }
                }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(
                    id = id,
                    detailPath = subject.detailPath,
                    apiHost = selectedHost
                ).toJson()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    private data class ResolvedStream(
        val host: String,
        val referer: String,
        val stream: Media.Data.Streams
    )

    private fun buildPlayReferer(
        host: String,
        media: LoadData,
        subjectId: String
    ): String {
        val detailPath = media.detailPath.orEmpty()

        if (detailPath.isBlank()) return "$host/"

        // The current public MovieBox site uses /moviedetail/... while some
        // older H5 mirrors still use /spa/videoPlayPage/movies/.... Keep the
        // referer aligned with the host that supplied the API response.
        return if (host.contains("movieboxhd.net") || host.contains("moviebox.ph")) {
            "$host/moviedetail/$detailPath?id=$subjectId&type=/movie/detail"
        } else {
            "$host/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        }
    }

    private suspend fun loadPlayHosts(
        media: LoadData,
        subjectId: String,
        season: Int,
        episode: Int
    ): List<ResolvedStream> {
        /*
         * These domains are mirrors of the same H5 backend. Once one host
         * returns a non-empty stream list, use that complete list immediately.
         * Checking every mirror after success only duplicates links and adds
         * several seconds to source loading.
         */
        for (host in orderedWebHosts(media.apiHost)) {
            try {
                val referer = buildPlayReferer(
                    host = host,
                    media = media,
                    subjectId = subjectId
                )

                val streams = app.get(
                    "$host/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$season&ep=$episode",
                    headers = commonHeaders,
                    referer = referer,
                    timeout = playHostTimeoutSeconds
                ).parsedSafe<Media>()
                    ?.data
                    ?.streams
                    .orEmpty()
                    .filter { !it.url.isNullOrBlank() }

                if (streams.isNotEmpty()) {
                    preferredWebHost = host
                    Log.i("MovieBox", "MOVIEBOX_PLAY host=$host streams=${streams.size} se=$season ep=$episode")
                    return streams.map { stream ->
                        ResolvedStream(
                            host = host,
                            referer = referer,
                            stream = stream
                        )
                    }.distinctBy { it.stream.url }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w("MovieBox", "MOVIEBOX_PLAY_FAIL host=$host type=${error::class.simpleName}")
            }
        }

        Log.w("MovieBox", "MOVIEBOX_PLAY_EMPTY subject=$subjectId se=$season ep=$episode")
        return emptyList()
    }

    private fun allowedSubtitleLanguage(caption: Media.Data.Captions): String? {
        val values = listOfNotNull(caption.lan, caption.lanName)
            .map { value ->
                value.trim()
                    .lowercase()
                    .replace('_', '-')
                    .replace(Regex("\\s+"), " ")
            }
            .filter { it.isNotBlank() }

        fun hasCode(vararg codes: String): Boolean = values.any { value ->
            codes.any { code ->
                value == code ||
                    value.startsWith("$code-") ||
                    value.startsWith("$code ") ||
                    value.startsWith("$code(") ||
                    value.startsWith("$code[")
            }
        }

        fun hasName(vararg names: String): Boolean = values.any { value ->
            names.any { name ->
                Regex("(^|[^a-z])${Regex.escape(name)}([^a-z]|$)")
                    .containsMatchIn(value)
            }
        }

        return when {
            hasCode("ms", "msa", "may") ||
                hasName(
                    "bahasa melayu",
                    "bahasa malaysia",
                    "malay",
                    "melayu",
                    "malaysian"
                ) -> "Malay"

            hasCode("en", "eng") ||
                hasName("english") -> "English"

            hasCode("id", "ind", "in") ||
                hasName("bahasa indonesia", "indonesian") -> "Indonesian"

            else -> null
        }
    }

    private suspend fun loadCaptionsAcrossHosts(
        subjectId: String,
        seeds: List<ResolvedStream>
    ): List<Media.Data.Captions> {
        val captions = mutableListOf<Media.Data.Captions>()

        /*
         * Caption metadata is keyed by stream id + format. Try the stream's
         * origin host first, then fall back only when that host returns no
         * usable EN/MS/ID captions. Do not query every mirror after success.
         */
        val captionSeeds = seeds
            .filter {
                !it.stream.id.isNullOrBlank() &&
                    !it.stream.format.isNullOrBlank()
            }
            .distinctBy { "${it.stream.id}|${it.stream.format}" }

        for (seed in captionSeeds) {
            val streamId = seed.stream.id ?: continue
            val format = seed.stream.format ?: continue

            for (host in orderedWebHosts(seed.host)) {
                try {
                    val hostCaptions = app.get(
                        "$host/wefeed-h5-bff/web/subject/caption?format=$format&id=$streamId&subjectId=$subjectId",
                        headers = commonHeaders,
                        referer = "$host/",
                        timeout = captionHostTimeoutSeconds
                    ).parsedSafe<Media>()
                        ?.data
                        ?.captions
                        .orEmpty()
                        .filter { caption ->
                            !caption.url.isNullOrBlank() &&
                                allowedSubtitleLanguage(caption) != null
                        }

                    if (hostCaptions.isNotEmpty()) {
                        captions += hostCaptions
                        Log.i("MovieBox", "MOVIEBOX_CAPTION host=$host count=${hostCaptions.size}")
                        break
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w("MovieBox", "MOVIEBOX_CAPTION_FAIL host=$host type=${error::class.simpleName}")
                }
            }
        }

        return captions.distinctBy { it.url }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media = try {
            parseJson<LoadData>(data)
        } catch (_: Throwable) {
            return false
        }

        val subjectId = media.id?.takeIf { it.isNotBlank() } ?: return false
        val season = media.season ?: 0
        val episode = media.episode ?: 0

        /*
         * Compatibility path:
         * - no manual child-task cancellation machinery
         * - every MovieBox web mirror is checked
         * - unique streams from every successful host are emitted
         */
        val resolvedStreams = loadPlayHosts(
            media = media,
            subjectId = subjectId,
            season = season,
            episode = episode
        )

        if (resolvedStreams.isEmpty()) return false

        resolvedStreams
            .sortedByDescending {
                getQualityFromName(it.stream.resolutions)
            }
            .forEach { resolved ->
                val source = resolved.stream
                val streamUrl = source.url ?: return@forEach

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        buildString {
                            append(this@MovieboxProvider.name)
                            source.resolutions
                                ?.takeIf { it.isNotBlank() }
                                ?.let { append(" ").append(it) }
                        },
                        streamUrl,
                        INFER_TYPE
                    ) {
                        this.referer = resolved.referer
                        this.quality = getQualityFromName(source.resolutions)
                        this.headers = mapOf(
                            "Accept" to "*/*",
                            "User-Agent" to commonHeaders["User-Agent"].orEmpty(),
                            "Origin" to resolved.host
                        )
                    }
                )
            }

        loadCaptionsAcrossHosts(
            subjectId = subjectId,
            seeds = resolvedStreams
        ).forEach { subtitle ->
            val subtitleUrl = subtitle.url ?: return@forEach
            val language = allowedSubtitleLanguage(subtitle)
                ?: return@forEach

            subtitleCallback.invoke(
                newSubtitleFile(language, subtitleUrl)
            )
        }

        return true
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null,
        val apiHost: String? = null
    )

    data class Media(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf(),
        ) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
                @JsonProperty("url") val url: String? = null,
                @JsonProperty("resolutions") val resolutions: String? = null,
            )

            data class Captions(
                @JsonProperty("lan") val lan: String? = null,
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null,
            )
        }
    }

    data class MediaDetail(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null,
        ) {
            data class Stars(
                @JsonProperty("name") val name: String? = null,
                @JsonProperty("character") val character: String? = null,
                @JsonProperty("avatarUrl") val avatarUrl: String? = null,
            )

            data class Resource(
                @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
            ) {
                data class Seasons(
                    @JsonProperty("se") val se: Int? = null,
                    @JsonProperty("maxEp") val maxEp: Int? = null,
                    @JsonProperty("allEp") val allEp: String? = null,
                )
            }
        }
    }

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
    ) {

        fun toSearchResponse(provider: MovieboxProvider): SearchResponse {
            val type = if (subjectType == 1) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

            return if (type == TvType.Movie) {
                provider.newMovieSearchResponse(
                    title.orEmpty(),
                    subjectId.orEmpty(),
                    TvType.Movie,
                    false
                ) {
                    this.posterUrl = cover?.url
                }
            } else {
                provider.newTvSeriesSearchResponse(
                    title.orEmpty(),
                    subjectId.orEmpty(),
                    TvType.TvSeries,
                    false
                ) {
                    this.posterUrl = cover?.url
                }
            }
        }

        data class Cover(
            @JsonProperty("url") val url: String? = null,
        )

        data class Trailer(
            @JsonProperty("videoAddress") val videoAddress: VideoAddress? = null,
        ) {
            data class VideoAddress(
                @JsonProperty("url") val url: String? = null,
            )
        }
    }
}
