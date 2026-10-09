package com.animextv

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

internal class AnimeXTVCatalog(private val mainUrl: String) {
    private val backend = "https://streamx-backend-myr0.onrender.com/api"
    private val headers = mapOf("Content-Type" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/")
    private val slots = Semaphore(2)
    private val fields = "id idMal title { romaji english } coverImage { large extraLarge } description(asHtml:false) episodes duration status format seasonYear genres nextAiringEpisode { episode }"
    private val malFields = "alternative_titles,main_picture,synopsis,num_episodes,average_episode_duration,status,start_season,media_type,genres"

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        withTimeoutOrNull(10_000L) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    private suspend fun graph(query: String): JsonNode {
        val response = app.post("https://graphql.anilist.co", json = mapOf("query" to query), headers = headers)
        val node = mapper.readTree(response.text)
        if (response.code !in 200..299 || node.has("errors") || !node.has("data")) throw ErrorLoadingException("AnimeXTV: AniList gagal.")
        return node.path("data")
    }

    private suspend fun mal(path: String): JsonNode {
        val response = app.get("$backend/mal$path", headers = headers)
        val node = mapper.readTree(response.text)
        if (response.code !in 200..299 || node.has("error")) throw ErrorLoadingException("AnimeXTV: MAL gagal.")
        return node
    }

    internal fun fromMal(node: JsonNode): JsonNode? {
        val idMal = node.path("id").asInt()
        val id = AnimeXTVIds.fromMal(idMal) ?: return null
        val result = mapper.createObjectNode().put("id", id).put("idMal", idMal)
        result.set<JsonNode>("title", mapper.createObjectNode().put("romaji", node.path("title").asText(""))
            .put("english", node.path("alternative_titles").path("en").asText("")))
        result.set<JsonNode>("coverImage", mapper.createObjectNode().put("large", node.path("main_picture").path("medium").asText(""))
            .put("extraLarge", node.path("main_picture").path("large").asText("")))
        result.put("description", node.path("synopsis").asText(""))
        result.put("episodes", node.path("num_episodes").asInt())
        result.put("duration", node.path("average_episode_duration").asInt() / 60)
        result.put("seasonYear", node.path("start_season").path("year").asInt())
        result.put("format", node.path("media_type").asText("").uppercase())
        result.put("status", when (node.path("status").asText("")) {
            "currently_airing" -> "RELEASING"
            "finished_airing" -> "FINISHED"
            "not_yet_aired" -> "NOT_YET_RELEASED"
            else -> ""
        })
        result.set<JsonNode>("genres", mapper.valueToTree(node.path("genres").map { it.path("name").asText("") }))
        return result
    }

    suspend fun listing(section: String, page: Int, search: String? = null): Pair<List<JsonNode>, Boolean> = slots.withPermit {
        val filter = if (search != null) "search:${mapper.writeValueAsString(search)},sort:SEARCH_MATCH" else when (section) {
            "trending" -> "sort:TRENDING_DESC"
            "popular" -> "sort:POPULARITY_DESC"
            "airing" -> "status:RELEASING,sort:POPULARITY_DESC"
            "completed" -> "status:FINISHED,sort:END_DATE_DESC"
            "movies" -> "format:MOVIE,sort:POPULARITY_DESC"
            else -> throw ErrorLoadingException("AnimeXTV: katalog tidak dikenali.")
        }
        val primary = attempt {
            graph("query { Page(page:$page,perPage:20) { pageInfo { hasNextPage } media(type:ANIME,isAdult:false,$filter) { $fields } } }").path("Page")
        }
        if (primary != null && primary.path("media").isArray) {
            return@withPermit primary.path("media").toList() to primary.path("pageInfo").path("hasNextPage").asBoolean()
        }
        val path = if (search != null) "/anime?q=${URLEncoder.encode(search, "UTF-8")}" else "/anime/ranking?ranking_type=" + when (section) {
            "airing" -> "airing"
            "movies" -> "movie"
            "popular" -> "bypopularity"
            else -> "all"
        }
        val fallback = attempt { mal("$path&limit=20&offset=${(page - 1) * 20}&fields=${URLEncoder.encode(malFields, "UTF-8")}") }
            ?: throw ErrorLoadingException("AnimeXTV: API katalog tidak tersedia. Cuba lagi sebentar.")
        Log.i("AnimeXTV", "ANIMEXTV_CATALOG api=mal section=$section page=$page")
        if (section == "completed" && search == null) {
            // MAL's ranking endpoint has no recently-completed equivalent.
            throw ErrorLoadingException("AnimeXTV: katalog Recently Completed memerlukan AniList.")
        }
        fallback.path("data").mapNotNull { fromMal(it.path("node")) } to fallback.path("paging").has("next")
    }

    suspend fun detail(id: Int, malId: Int?): JsonNode = slots.withPermit {
        val primary = attempt { graph("query { Media(id:$id,type:ANIME) { $fields } }").path("Media") }
        if (primary != null && primary.path("id").asInt() == id) return@withPermit primary
        val mappedMal = malId ?: AnimeXTVIds.toMal(id)
            ?: throw ErrorLoadingException("AnimeXTV: ID MAL tidak tersedia.")
        val fallback = attempt { mal("/anime/$mappedMal?fields=${URLEncoder.encode(malFields, "UTF-8")}") }?.let { fromMal(it) }
            ?: throw ErrorLoadingException("AnimeXTV: metadata tidak tersedia. Cuba lagi sebentar.")
        if (fallback.path("episodes").asInt() == 0 && fallback.path("status").asText("") == "RELEASING") {
            val released = attempt {
                val first = mapper.readTree(app.get("$backend/jikan/anime/$mappedMal/episodes", headers = headers).text)
                val lastPage = first.path("pagination").path("last_visible_page").asInt(1)
                if (!first.path("data").isArray) throw ErrorLoadingException("AnimeXTV: episod tidak tersedia.")
                if (lastPage <= 1) first.path("data").size()
                else {
                    val last = mapper.readTree(app.get("$backend/jikan/anime/$mappedMal/episodes?page=$lastPage", headers = headers).text)
                    if (!last.path("data").isArray) throw ErrorLoadingException("AnimeXTV: episod tidak tersedia.")
                    (lastPage - 1) * 100 + last.path("data").size()
                }
            }
            if (released == null || released <= 0) throw ErrorLoadingException("AnimeXTV: API senarai episod tidak tersedia.")
            (fallback as com.fasterxml.jackson.databind.node.ObjectNode).put("episodes", released)
        }
        fallback
    }
}
