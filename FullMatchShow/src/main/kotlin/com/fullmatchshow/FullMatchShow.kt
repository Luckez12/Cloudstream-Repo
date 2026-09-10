package com.fullmatchshow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FullMatchShow : MainAPI() {
    override var mainUrl = "https://fullmatchshows.com"
    override var name = "FullMatchShow"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Others)

    override val mainPage = mainPageOf(
        "$mainUrl/england/" to "England",
        "$mainUrl/spain/" to "Spain",
        "$mainUrl/italy/" to "Italy",
        "$mainUrl/germany/" to "Germany",
        "$mainUrl/france/" to "France",
        "$mainUrl/portugal/" to "Portugal",
        "$mainUrl/uefa/" to "UEFA",
        "$mainUrl/international/" to "International",
        "$mainUrl/other/" to "Other"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/")
        val url = if (page == 1) request.data else "$base/page/$page/"
        val document = app.get(url).document
        val items = document.select("div.p-wrap, article.p-wrap")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        val hasNext = document.select("a.next, a.next.page-numbers, a.page-numbers[rel=next]").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url).document
        val results = document.select("div.p-wrap, article.p-wrap")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        val hasNext = document.select("a.next, a.next.page-numbers, a.page-numbers[rel=next]").isNotEmpty()
        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.s-title, h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.s-feat img, .post-thumbnail img, meta[property=og:image]")?.let {
            it.attr("src").ifBlank { it.attr("content") }
        })
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst(".entry-content p")?.text()?.trim()
        val year = document.selectFirst("time.updated-date, time.entry-date, time[datetime]")
            ?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        val tags = document.select("div.efoot-bar.tag-bar a, .tags-links a")
            .map { it.text().trim() }.filter { it.isNotBlank() }
        val recommendations = document.select("div.p-wrap.p-grid, div.p-wrap, article.p-wrap")
            .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val episodes = document.select("table.video-table, .video-table, table[class*=video]")
            .flatMap { it.toEpisodes() }
            .ifEmpty { document.toLinkEpisodes() }
            .distinctBy { it.data }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val category = selectFirst("a.p-category")
        if (category?.attr("href")?.contains("/news/") == true) return null
        if (category?.className()?.contains("category-id-283") == true) return null

        val link = selectFirst("a.p-flink, h4.entry-title a, a[href*='full-match']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").takeIf { it.isNotBlank() } ?: link.text().trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("div.p-featured img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        })
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
    }

    private fun Element.toEpisodes(): List<Episode> {
        val source = selectFirst("thead tr th[colspan], caption")?.text()?.trim().orEmpty()
        return select("tbody tr, tr").mapIndexedNotNull { index, row ->
            val anchor = row.selectFirst("a.play-button, a[href]")
            val onclick = anchor?.attr("onclick").orEmpty().ifBlank { row.attr("onclick") }
            val href = anchor?.attr("href").orEmpty()
            val videoUrl = extractVideoUrl(href, onclick, anchor)
            if (videoUrl.isBlank() || videoUrl == "#" || videoUrl.startsWith("javascript:", true)) return@mapIndexedNotNull null

            val part = row.select("td").firstOrNull()?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: anchor?.text()?.trim().orEmpty().ifBlank { "Video" }
            val label = listOf(source, part).filter { it.isNotBlank() }.joinToString(" - ")
            newEpisode(data = "$videoUrl\t$label") {
                name = label
                episode = index + 1
            }
        }
    }

    private fun org.jsoup.nodes.Document.toLinkEpisodes(): List<Episode> {
        val linkPattern = Regex("highlights?|extended|full\\s*match|[12]st?\\s*half", RegexOption.IGNORE_CASE)
        return select(".entry-content a[href], .post-content a[href], main a[href]")
            .filter { anchor ->
                val href = anchor.attr("href")
                val text = anchor.text().trim()
                href.startsWith("http", true) && text.isNotBlank() && linkPattern.containsMatchIn(text)
            }
            .distinctBy { it.attr("href") }
            .mapIndexed { index, anchor ->
                val label = anchor.text().trim()
                newEpisode(data = "${fixUrl(anchor.attr("href"))}\t$label") {
                    name = label
                    episode = index + 1
                }
            }
    }

    private fun extractVideoUrl(href: String, onclick: String, anchor: Element?): String {
        val fromOnclick = Regex("""(?:loadVideo|playVideo)\s*\(\s*['\"]([^'\"]+)['\"]""")
            .find(onclick)?.groupValues?.getOrNull(1).orEmpty()
        val candidate = fromOnclick.ifBlank {
            sequenceOf(href, anchor?.attr("data-url").orEmpty(), anchor?.attr("data-video").orEmpty(), anchor?.attr("data-src").orEmpty())
                .firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return fixUrlNull(candidate).orEmpty()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("\t", limit = 2).let { if (it.size == 1) it[0].split("|", limit = 2) else it }
        val videoUrl = parts.firstOrNull()?.trim().orEmpty()
        val label = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() } ?: "Video"
        if (videoUrl.isBlank()) return false

        loadExtractor(videoUrl, mainUrl, subtitleCallback) { link ->
            callback(
                kotlinx.coroutines.runBlocking {
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = link.url,
                        type = link.type
                    )
                }
            )
        }
        return true
    }
}
