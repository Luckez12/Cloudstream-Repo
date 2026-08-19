package com.OneTouchTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Locale

class OneTouchTV : MainAPI() {
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
                if (items.isEmpty()) return@mapNotNull null
                val title = country.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT)
                    else char.toString()
                }
                HomePageList(
                    title,
                    items.map(::toSearchResponse),
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

        val actors = data.actors.map { actor ->
            ActorData(
                Actor(
                    actor.name.orEmpty(),
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

        val episodes = data.episodes.mapNotNull { item ->
            val identifier = item.identifier ?: return@mapNotNull null
            val playId = item.playId ?: return@mapNotNull null
            newEpisode("$mainUrl/vod/$identifier/episode/$playId") {
                name = "Episode ${item.episode ?: "?"}"
            }
        }.reversed()

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
        return try {
            val decrypted = getDecrypted("$mainUrl/vod/top")
            val top = parseJson<TopResponse>(decrypted)
            (top.day.orEmpty() + top.week.orEmpty() + top.month.orEmpty())
                .distinctBy { it.id ?: it._id ?: it.title }
                .mapNotNull { item ->
                    val id = item.id ?: item._id ?: return@mapNotNull null
                    newTvSeriesSearchResponse(
                        item.title?.ifBlank { "Unknown Title" } ?: "Unknown Title",
                        "$mainUrl/vod/$id/detail",
                        TvType.Movie,
                    ) {
                        posterUrl = item.image
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("recommendations failed: ${error.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val decrypted = getDecrypted(data)
        val (sources, tracks) = try {
            parseSourcesAndTracks(decrypted)
        } catch (error: Throwable) {
            throw ErrorLoadingException(
                "OneTouchTV stream parse failed: ${error.message}",
            )
        }

        launch {
            tracks.forEach { track ->
                val file = track.file.takeIf { it.isNotBlank() } ?: return@forEach
                subtitleCallback(
                    newSubtitleFile(
                        track.name.ifBlank { "Unknown" },
                        file,
                    ),
                )
            }
        }

        launch {
            sources.forEach { source ->
                val streamUrl = source.url.takeIf { it.isNotBlank() } ?: return@forEach
                val label = source.name.ifBlank { "Source" }
                callback(
                    newExtractorLink(
                        label,
                        label,
                        streamUrl,
                        INFER_TYPE,
                    ) {
                        quality = getQualityFromName(source.quality)
                        headers = source.headers
                    },
                )
            }
        }

        log("loadLinks sources=${sources.size} subtitles=${tracks.size}")
        sources.isNotEmpty()
    }

    private fun toSearchResponse(item: HomeItem): SearchResponse {
        val id = item.id2 ?: item.id.orEmpty()
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
