package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class MovieboxProvider : MainAPI() {

    override var mainUrl = "https://moviebox.ph"
    override var name = "MovieBox"
    override var lang = "id"

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
     * H5 web mirrors. Requests that still live on /wefeed-h5-bff/web are tried
     * in order so a blocked/dead mirror does not kill the whole provider.
     */
    private val webHosts = listOf(
        "https://moviebox.ph",
        "https://moviebox.pk",
        "https://moviebox.ng",
        "https://filmboom.top"
    )

    /*
     * Fast race strategy for playback:
     * all compatible H5 mirrors are queried in parallel and the first host
     * that returns at least one valid stream URL wins. Remaining requests
     * are cancelled immediately. The winning host is remembered so normal
     * search/detail requests can prefer it later.
     */
    @Volatile
    private var preferredWebHost: String? = null

    private val playRaceTimeoutMs = 5_500L
    private val captionRaceTimeoutMs = 3_000L

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

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val requestBody = mapOf(
            "keyword" to query.trim(),
            "page" to 1,
            "perPage" to 24,
            "subjectType" to 0
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        for (host in orderedWebHosts()) {
            val result = try {
                app.post(
                    "$host/wefeed-h5-bff/web/subject/search",
                    headers = commonHeaders,
                    referer = "$host/",
                    requestBody = requestBody
                ).parsedSafe<Media>()
                    ?.data
                    ?.items
                    .orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }

            if (result.isNotEmpty()) {
                return result.map { it.toSearchResponse(this) }
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").substringBefore("?")
        if (id.isBlank()) throw ErrorLoadingException("Invalid MovieBox subject id")

        var selectedHost: String? = null
        var document: MediaDetail.Data? = null

        for (host in orderedWebHosts()) {
            val parsed = try {
                app.get(
                    "$host/wefeed-h5-bff/web/subject/detail?subjectId=$id",
                    headers = commonHeaders,
                    referer = "$host/"
                ).parsedSafe<MediaDetail>()?.data
            } catch (_: Throwable) {
                null
            }

            if (parsed?.subject != null) {
                selectedHost = host
                document = parsed
                break
            }
        }

        val detail = document ?: throw ErrorLoadingException("MovieBox detail unavailable")
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

        val recommendationHost = selectedHost ?: mainUrl
        val recommendations = try {
            app.get(
                "$recommendationHost/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=12",
                headers = commonHeaders,
                referer = "$recommendationHost/"
            ).parsedSafe<Media>()
                ?.data
                ?.items
                ?.map { it.toSearchResponse(this) }
        } catch (_: Throwable) {
            null
        }

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

    private data class PlayRaceResult(
        val host: String,
        val referer: String,
        val streams: List<Media.Data.Streams>
    )

    private fun buildPlayReferer(
        host: String,
        media: LoadData,
        subjectId: String
    ): String {
        val detailPath = media.detailPath.orEmpty()
        return if (detailPath.isNotBlank()) {
            "$host/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        } else {
            "$host/"
        }
    }

    private suspend fun racePlayHosts(
        media: LoadData,
        subjectId: String,
        season: Int,
        episode: Int
    ): PlayRaceResult? = coroutineScope {
        val hosts = orderedWebHosts(media.apiHost)
        if (hosts.isEmpty()) return@coroutineScope null

        val winner = CompletableDeferred<PlayRaceResult?>()
        val remaining = AtomicInteger(hosts.size)

        val jobs = hosts.map { host ->
            launch {
                try {
                    val referer = buildPlayReferer(host, media, subjectId)
                    val streams = app.get(
                        "$host/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$season&ep=$episode",
                        headers = commonHeaders,
                        referer = referer
                    ).parsedSafe<Media>()
                        ?.data
                        ?.streams
                        .orEmpty()
                        .filter { !it.url.isNullOrBlank() }

                    if (streams.isNotEmpty()) {
                        winner.complete(
                            PlayRaceResult(
                                host = host,
                                referer = referer,
                                streams = streams
                            )
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A failed mirror is ignored. Another parallel mirror may win.
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        winner.complete(null)
                    }
                }
            }
        }

        val result = withTimeoutOrNull(playRaceTimeoutMs) {
            winner.await()
        }

        jobs.forEach { job ->
            if (job.isActive) job.cancel()
        }

        result
    }

    private suspend fun raceCaptionHosts(
        subjectId: String,
        streamId: String,
        format: String,
        winningHost: String
    ): List<Media.Data.Captions> = coroutineScope {
        val hosts = orderedWebHosts(winningHost)
        if (hosts.isEmpty()) return@coroutineScope emptyList()

        val winner = CompletableDeferred<List<Media.Data.Captions>?>()
        val remaining = AtomicInteger(hosts.size)

        val jobs = hosts.map { host ->
            launch {
                try {
                    val captions = app.get(
                        "$host/wefeed-h5-bff/web/subject/caption?format=$format&id=$streamId&subjectId=$subjectId",
                        headers = commonHeaders,
                        referer = "$host/"
                    ).parsedSafe<Media>()
                        ?.data
                        ?.captions
                        .orEmpty()
                        .filter { !it.url.isNullOrBlank() }

                    if (captions.isNotEmpty()) {
                        winner.complete(captions)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Ignore dead/blocked caption mirror.
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        winner.complete(null)
                    }
                }
            }
        }

        val result = withTimeoutOrNull(captionRaceTimeoutMs) {
            winner.await()
        }.orEmpty()

        jobs.forEach { job ->
            if (job.isActive) job.cancel()
        }

        result
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
         * Critical path: race every compatible H5 mirror at the same time.
         * The first mirror with a non-empty stream list wins.
         */
        val playResult = racePlayHosts(
            media = media,
            subjectId = subjectId,
            season = season,
            episode = episode
        ) ?: return false

        preferredWebHost = playResult.host

        val streams = playResult.streams
            .distinctBy { it.url }
            .sortedByDescending { getQualityFromName(it.resolutions) }

        if (streams.isEmpty()) return false

        /*
         * Emit video links immediately after the first valid host wins.
         * Caption lookup happens afterwards and cannot delay stream discovery.
         */
        streams.forEach { source ->
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
                    this.referer = "${playResult.host}/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val captionSeed = streams.firstOrNull { source ->
            !source.id.isNullOrBlank() && !source.format.isNullOrBlank()
        }

        if (captionSeed != null) {
            val streamId = captionSeed.id
            val format = captionSeed.format

            if (!streamId.isNullOrBlank() && !format.isNullOrBlank()) {
                val captions = raceCaptionHosts(
                    subjectId = subjectId,
                    streamId = streamId,
                    format = format,
                    winningHost = playResult.host
                )

                captions
                    .distinctBy { it.url }
                    .forEach { subtitle ->
                        val subtitleUrl = subtitle.url ?: return@forEach
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                subtitle.lanName
                                    ?.takeIf { it.isNotBlank() }
                                    ?: subtitle.lan
                                    ?: "Unknown",
                                subtitleUrl
                            )
                        )
                    }
            }
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
            val type = if (subjectType == 1) TvType.Movie else TvType.TvSeries

            return provider.newMovieSearchResponse(
                title.orEmpty(),
                subjectId.orEmpty(),
                type,
                false
            ) {
                this.posterUrl = cover?.url
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
