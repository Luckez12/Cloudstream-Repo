package com.luckez12.kisskh

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class KissKHProvider : MainAPI() {
    override var mainUrl = "https://kisskh.co"
    override var name = "KissKH"
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override val hasMainPage = false
    override val hasChromecastSupport = false

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
        )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/api/DramaList/Search?q=${enc(query.trim())}"
        val root = mapper.readTree(
            app.get(url, headers = headers, cacheTime = 0).text
        )

        return arrayNodes(root).mapNotNull { item ->
            val id = item.intValue("id", "ID") ?: return@mapNotNull null
            val title = item.textValue("title", "Title", "name", "Name")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = absoluteUrl(
                item.textValue(
                    "thumbnail", "Thumbnail",
                    "poster", "Poster",
                    "image", "Image",
                )
            )

            val type = parseType(item.textValue("type", "Type"))
            val detailsUrl = "$mainUrl/Drama/${slug(title)}?id=$id"

            when (type) {
                TvType.Movie -> newMovieSearchResponse(
                    title,
                    detailsUrl,
                    TvType.Movie,
                ) {
                    posterUrl = poster
                }

                TvType.Anime -> newAnimeSearchResponse(
                    title,
                    detailsUrl,
                    TvType.Anime,
                ) {
                    posterUrl = poster
                }

                else -> newTvSeriesSearchResponse(
                    title,
                    detailsUrl,
                    TvType.TvSeries,
                ) {
                    posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = queryValue(url, "id")?.toIntOrNull() ?: return null

        val root = mapper.readTree(
            app.get(
                "$mainUrl/api/DramaList/Drama/$id?isq=false",
                headers = headers,
                cacheTime = 0,
            ).text
        )

        val title = root.textValue("title", "Title", "name", "Name")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val type = parseType(root.textValue("type", "Type"))

        val poster = absoluteUrl(
            root.textValue(
                "thumbnail", "Thumbnail",
                "poster", "Poster",
                "image", "Image",
            )
        )

        val plot = root.textValue(
            "description", "Description",
            "synopsis", "Synopsis",
        )

        val year = Regex("""\b(?:19|20)\d{2}\b""")
            .find(
                root.textValue(
                    "releaseDate", "ReleaseDate",
                    "release", "Release",
                ).orEmpty()
            )
            ?.value
            ?.toIntOrNull()

        val episodeNodes = arrayNodes(root.getIgnoreCase("episodes"))

        val episodes = episodeNodes.mapNotNull { ep ->
            val epId = ep.intValue("id", "ID") ?: return@mapNotNull null
            val number = ep.doubleValue("number", "Number")
                ?: ep.doubleValue("episode", "Episode")
                ?: return@mapNotNull null

            val numberText = cleanNumber(number)
            val data = "$mainUrl/Drama/${slug(title)}/Episode-$numberText" +
                "?id=$id&ep=$epId&page=0&pageSize=100"

            newEpisode(data) {
                name = "Episode $numberText"
                episode = if (number % 1.0 == 0.0) number.toInt() else null
            }
        }

        if (type == TvType.Movie) {
            val dataUrl = episodes.firstOrNull()?.data.orEmpty()

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                dataUrl = dataUrl,
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            type,
            episodes,
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false

        val page = runCatching {
            app.get(
                data,
                headers = headers + ("Referer" to "$mainUrl/"),
                cacheTime = 0,
            ).text
        }.getOrNull().orEmpty()

        val direct = findMediaUrl(page) ?: return false

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = direct,
                type = ExtractorLinkType.VIDEO,
            ) {
                referer = data
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    private fun findMediaUrl(raw: String): String? {
        val text = raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return Regex(
            """https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.value
            ?: Regex(
                """https?://[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]*)?""",
                RegexOption.IGNORE_CASE,
            ).find(text)?.value
    }

    private fun parseType(value: String?): TvType =
        when (value?.trim()?.lowercase()) {
            "movie", "film" -> TvType.Movie
            "anime" -> TvType.Anime
            else -> TvType.TvSeries
        }

    private fun cleanNumber(number: Double): String =
        if (number % 1.0 == 0.0) {
            number.toInt().toString()
        } else {
            number.toString().trimEnd('0').trimEnd('.')
        }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun slug(value: String): String =
        value.trim()
            .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
            .trim('-')
            .ifBlank { "Drama" }

    private fun queryValue(url: String, key: String): String? =
        Regex("""(?:[?&])${Regex.escape(key)}=([^&#]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)

    private fun absoluteUrl(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return when {
            url.startsWith("https://") || url.startsWith("http://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun arrayNodes(node: JsonNode?): List<JsonNode> {
        if (node == null || node.isNull) return emptyList()

        if (node.isArray) return node.toList()

        listOf(
            "data", "Data",
            "result", "Result",
            "items", "Items",
            "dramas", "Dramas",
        ).forEach { key ->
            val child = node.get(key)
            if (child?.isArray == true) return child.toList()
        }

        return emptyList()
    }

    private fun JsonNode.getIgnoreCase(key: String): JsonNode? {
        get(key)?.let { return it }

        fields().forEachRemaining { entry ->
            if (entry.key.equals(key, ignoreCase = true)) {
                return entry.value
            }
        }

        return null
    }

    private fun JsonNode.textValue(vararg keys: String): String? {
        keys.forEach { key ->
            val node = getIgnoreCase(key)
            if (node != null && !node.isNull) {
                val value = node.asText()
                if (value.isNotBlank() && value != "null") return value
            }
        }
        return null
    }

    private fun JsonNode.intValue(vararg keys: String): Int? {
        keys.forEach { key ->
            val node = getIgnoreCase(key) ?: return@forEach
            if (node.isInt || node.isLong) return node.asInt()
            node.asText().toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonNode.doubleValue(vararg keys: String): Double? {
        keys.forEach { key ->
            val node = getIgnoreCase(key) ?: return@forEach
            if (node.isNumber) return node.asDouble()
            node.asText().toDoubleOrNull()?.let { return it }
        }
        return null
    }
}
