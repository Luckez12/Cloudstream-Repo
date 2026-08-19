package com.OneTouchTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.util.Locale

class OneTouchTV : MainAPI() {
    companion object {
        private const val RECOMMENDATION_CACHE_MS = 15 * 60 * 1000L

        @Volatile
        private var recommendationCache: Pair<Long, List<SearchResponse>>? = null
    }

    override var mainUrl = base64Decode("aHR0cHM6Ly9hcGkzLmRldmNvcnAubWU=")
    override var name = "OneTouchTV"
    override var lang = "en"

    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "vod/home" to "Home",
    )

    private fun log(message: String) {
        println("[OneTouchTV] $message")
    }

    private suspend fun getDecrypted(url: String, referer: String? = null): String {
        val raw = try {
            app.get(url, referer = referer).text
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV request failed: ${error.message}",
            )
        }

        return try {
            decryptString(raw)
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV decrypt failed: ${error.message}",
            )
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val decrypted = getDecrypted("$mainUrl/${request.data}")
        val payload = try {
            parseJson<HomeResponse>(decrypted)
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV home parse failed: ${error.message}",
            )
        }

        val random = payload.randomSlideShow
            ?: payload.result?.randomSlideShow
            ?: emptyList()
        val recent = payload.recents
            ?: payload.result?.recents
            ?: emptyList()

        val media = (random + recent)
            .distinctBy { it.id2 ?: it.id ?: it.title }

        val lists = media
            .groupBy {
                it.country?.trim()?.lowercase(Locale.ROOT)
                    ?.ifBlank { "unknown" }
                    ?: "unknown"
            }
            .mapNotNull { (country, items) ->
                val responses = items.mapNotNull(::toSearchResponse)
                if (responses.isEmpty()) return@mapNotNull null

                val title = country.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT)
                    else char.toString()
                }
                HomePageList(
                    title,
                    responses,
                    isHorizontalImages = false,
                )
            }

        log("home items=${media.size} sections=${lists.size}")
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(
        query: String,
        page: Int,
    ): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return emptyList<SearchResponse>().toNewSearchResponseList(false)
        }

        val encoded = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
        val decrypted = getDecrypted(
            "$mainUrl/vod/search?page=$page&keyword=$encoded",
            referer = "$mainUrl/",
        )

        val results = try {
            if (decrypted.trimStart().startsWith("[")) {
                parseJson<List<SearchItem>>(decrypted)
            } else {
                parseJson<SearchEnvelope>(decrypted).result
            }
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV search parse failed: ${error.message}",
            )
        }

        val mapped = results.mapNotNull { item ->
            val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newTvSeriesSearchResponse(
                item.title?.ifBlank { "Unknown" } ?: "Unknown",
                "$mainUrl/vod/$id/detail",
                if (item.type.equals("movie", ignoreCase = true)) {
                    TvType.Movie
                } else {
                    TvType.TvSeries
                },
            ) {
                posterUrl = item.image
            }
        }

        log("search page=$page query='$cleanQuery' results=${mapped.size}")
        return mapped.toNewSearchResponseList(mapped.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val decrypted = getDecrypted(url)
        val data = try {
            parseJson<DetailResponse>(decrypted)
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV detail parse failed: ${error.message}",
            )
        }

        val title = data.title?.ifBlank { "Unknown Title" } ?: "Unknown Title"
        val backgroundPoster = data.image?.takeUnless { it == "null" }
        val poster = data.poster
            ?.replace("image-7wk.pages.dev", "image-v1.pages.dev")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: data.image.orEmpty()

        val actors = data.actors.mapNotNull { actor ->
            val actorName = actor.name?.trim().orEmpty()
            if (actorName.isBlank()) return@mapNotNull null

            ActorData(
                Actor(
                    actorName,
                    actor.image.orEmpty(),
                ),
            )
        }
        val tags = data.genres.map { genre ->
            genre.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT)
                else char.toString()
            }
        }

        val episodes = data.episodes
            .distinctBy { it.identifier to it.playId }
            .mapNotNull { item ->
                val identifier = item.identifier?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val playId = item.playId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                newEpisode("$mainUrl/vod/$identifier/episode/$playId") {
                    name = "Episode ${item.episode ?: "?"}"
                }
            }
            .reversed()

        val recommendations = loadRecommendations()

        log("load title='$title' episodes=${episodes.size} recs=${recommendations.size}")
        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes,
        ) {
            backgroundPosterUrl = backgroundPoster
            posterUrl = poster
            plot = data.description.orEmpty()
            this.tags = tags
            showStatus = getStatus(data.status.orEmpty())
            year = data.year?.toIntOrNull()
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    private suspend fun loadRecommendations(): List<SearchResponse> {
        val now = System.currentTimeMillis()
        recommendationCache?.let { (cachedAt, cachedItems) ->
            if (now - cachedAt < RECOMMENDATION_CACHE_MS) {
                return cachedItems
            }
        }

        return try {
            val decrypted = getDecrypted("$mainUrl/vod/top")
            val top = parseJson<TopResponse>(decrypted)
            val recommendations = (top.day.orEmpty() + top.week.orEmpty() + top.month.orEmpty())
                .distinctBy { it.id ?: it._id ?: it.title }
                .mapNotNull { item ->
                    val id = item.id ?: item._id ?: return@mapNotNull null
                    newTvSeriesSearchResponse(
                        item.title?.ifBlank { "Unknown Title" } ?: "Unknown Title",
                        "$mainUrl/vod/$id/detail",
                        if (item.type.equals("movie", ignoreCase = true)) {
                            TvType.Movie
                        } else {
                            TvType.TvSeries
                        },
                    ) {
                        posterUrl = item.image
                    }
                }

            recommendationCache = now to recommendations
            recommendations
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("recommendations failed: ${error.message}")
            recommendationCache?.second.orEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decrypted = getDecrypted(data)
        val (sources, tracks) = try {
            parseSourcesAndTracks(decrypted)
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV stream parse failed: ${error.message}",
            )
        }

        val uniqueTracks = tracks
            .filter { it.file.isNotBlank() }
            .distinctBy { it.file to it.name }

        uniqueTracks.forEach { track ->
            subtitleCallback(
                newSubtitleFile(
                    track.name.ifBlank { "Unknown" },
                    track.file,
                ),
            )
        }

        val uniqueSources = sources
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url to it.headers }

        uniqueSources.forEach { source ->
            val label = source.name.ifBlank { "Source" }
            callback(
                newExtractorLink(
                    label,
                    label,
                    source.url,
                    INFER_TYPE,
                ) {
                    quality = getQualityFromName(
                        source.quality.ifBlank { source.name },
                    )
                    headers = source.headers
                },
            )
        }

        log("loadLinks sources=${uniqueSources.size} subtitles=${uniqueTracks.size}")
        return uniqueSources.isNotEmpty()
    }

    private fun toSearchResponse(item: HomeItem): SearchResponse? {
        val id = (item.id2 ?: item.id)?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(
            item.title?.ifBlank { "Unknown Title" } ?: "Unknown Title",
            "$mainUrl/vod/$id/detail",
            if (item.type.equals("movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries,
        ) {
            posterUrl = item.image
        }
    }

    private fun getStatus(status: String): ShowStatus {
        return when {
            status.equals("Finished Airing", ignoreCase = true) -> ShowStatus.Completed
            status.equals("ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }
}
