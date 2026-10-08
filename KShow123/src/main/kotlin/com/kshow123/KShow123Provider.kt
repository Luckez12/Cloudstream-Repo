package com.kshow123

import android.os.SystemClock
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class KShow123Provider : MainAPI() {
    override var mainUrl = "https://kshow123.tv"
    override var name = "KShow123"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // Keys are actual section IDs in the site's homepage HTML.
    override val mainPage = mainPageOf(
        "recent" to "Recent Updates",
        "featured" to "Hot Today",
        "rated" to "Recent Release",
        "random" to "Random Shows"
    )

    private data class Snapshot(
        val origin: String,
        val fetchedAt: Long,
        val sections: Map<String, List<SearchResponse>>
    )

    private val homepageLock = Mutex()
    private var snapshot: Snapshot? = null
    private val sectionIds = listOf("recent", "featured", "rated", "random")

    private fun siteLink(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val base = URI(mainUrl.trimEnd('/') + "/")
            val uri = base.resolve(raw.trim())
            if (uri.scheme !in listOf("https", "http") ||
                !uri.host.equals(base.host, ignoreCase = true) ||
                !uri.path.orEmpty().startsWith("/show/")) {
                null
            } else {
                URI(uri.scheme, uri.authority, uri.path, null, null).toString()
            }
        }.getOrNull()
    }

    private fun showKey(url: String): String? = runCatching {
        Regex("^/show/([^/]+)/").find(URI(url).path.orEmpty())
            ?.groupValues?.get(1)
    }.getOrNull()

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("src") }.trim()
        if (raw.isBlank()) return null
        return runCatching {
            URI(mainUrl.trimEnd('/') + "/").resolve(raw)
                .takeIf { it.scheme in listOf("http", "https") && it.host != null }
                ?.toString()
        }.getOrNull()
    }

    private fun parseHomepage(document: Document): Map<String, List<SearchResponse>> {
        // Recent Updates is a text table. Reuse real images from the other
        // homepage sections; do not make one network request per card.
        val posters = mutableMapOf<String, String>()
        document.select("#main a.thumbnail[href]").forEach { anchor ->
            val url = siteLink(anchor.attr("href")) ?: return@forEach
            val key = showKey(url) ?: return@forEach
            val image = anchor.selectFirst("img")?.imageUrl() ?: return@forEach
            posters.putIfAbsent(key, image)
        }

        return sectionIds.associateWith { id ->
            val section = document.getElementById(id)
            section?.select("h2 a[href]")?.mapNotNull { anchor ->
                val title = anchor.text().replace(Regex("\\s+"), " ").trim()
                val url = siteLink(anchor.attr("href")) ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val poster = showKey(url)?.let { posters[it] }
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                }
            }?.distinctBy { it.url }.orEmpty()
        }
    }

    private suspend fun homepage(): Snapshot = homepageLock.withLock {
        val origin = mainUrl.trimEnd('/')
        val cached = snapshot
        if (cached != null && cached.origin == origin &&
            SystemClock.elapsedRealtime() - cached.fetchedAt < 30_000L) {
            return@withLock cached
        }

        val started = SystemClock.elapsedRealtime()
        val document = app.get("$origin/").document
        val sections = parseHomepage(document)
        if (sections.values.all { it.isEmpty() }) {
            throw ErrorLoadingException("KShow123: homepage tiada katalog. Website mungkin berubah atau akses disekat.")
        }

        Snapshot(origin, SystemClock.elapsedRealtime(), sections).also {
            snapshot = it
            Log.i(
                "KShow123",
                "KSHOW123_HOME_FETCH ${sections.entries.joinToString(" ") { entry -> "${entry.key}=${entry.value.size}" }} ms=${SystemClock.elapsedRealtime() - started}"
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data !in sectionIds) {
            throw ErrorLoadingException("KShow123: katalog tidak dikenali.")
        }
        // These are homepage sections, not paginated archive routes.
        val items = if (page <= 1) homepage().sections[request.data].orEmpty() else emptyList()
        Log.i("KShow123", "KSHOW123_HOME section=${request.data} page=$page items=${items.size}")
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val slug = Normalizer.normalize(query.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("đ", "d").replace("ß", "ss")
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("[\\s-]+"), "-").trim('-')
        if (slug.isBlank() || page > 1) {
            return emptyList<SearchResponse>().toNewSearchResponseList(false)
        }
        val document = app.get("$mainUrl/search/$slug/").document
        val results = document.select("#main a.thumbnail[href]").mapNotNull { anchor ->
            val url = siteLink(anchor.attr("href")) ?: return@mapNotNull null
            val title = anchor.parent()?.selectFirst("h2 a")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = anchor.selectFirst("img")?.imageUrl()
            }
        }.distinctBy { it.url }
        Log.i("KShow123", "KSHOW123_SEARCH page=$page items=${results.size}")
        return results.toNewSearchResponseList(false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = siteLink(url) ?: throw ErrorLoadingException("KShow123: URL rancangan tidak sah.")
        val key = showKey(target) ?: throw ErrorLoadingException("KShow123: rancangan tidak dikenali.")
        // Homepage cards link to individual episodes; details must open the
        // parent show so the user gets its complete episode list.
        val showUrl = "$mainUrl/show/$key/"
        val started = SystemClock.elapsedRealtime()
        val document = app.get(showUrl).document
        val title = document.selectFirst("#info h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("KShow123: tajuk detail tidak ditemui.")
        val poster = document.selectFirst("#info .media img")?.imageUrl()
        val episodes = document.select("#list-episodes h2 a[href]").mapNotNull { anchor ->
            val episodeUrl = siteLink(anchor.attr("href")) ?: return@mapNotNull null
            if (showKey(episodeUrl) != key) return@mapNotNull null
            val number = Regex("episode-([0-9]+(?:\\.[0-9]+)?)\\.html")
                .find(episodeUrl)?.groupValues?.get(1) ?: return@mapNotNull null
            val status = anchor.parent()?.selectFirst(".label")?.text().orEmpty()
            newEpisode(episodeUrl) {
                this.name = "Episode $number" + if (status.isBlank()) "" else " [$status]"
                episode = number.toIntOrNull()
                posterUrl = poster
            }
        }.distinctBy { it.data }.sortedBy {
            Regex("episode-([0-9]+(?:\\.[0-9]+)?)\\.html").find(it.data)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.MAX_VALUE
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("KShow123: senarai episod tidak ditemui.")
        val released = document.select("#info .media-info")
            .firstOrNull { it.selectFirst("strong")?.text()?.startsWith("Released") == true }
            ?.text()?.let { Regex("\\b(19|20)[0-9]{2}\\b").find(it)?.value?.toIntOrNull() }
        val cast = document.select("#info .media-info")
            .firstOrNull { it.selectFirst("strong")?.text()?.startsWith("Cast") == true }
            ?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() && it != "..." }.orEmpty()
        Log.i("KShow123", "KSHOW123_DETAIL show=$key episodes=${episodes.size} ms=${SystemClock.elapsedRealtime() - started}")
        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            plot = document.selectFirst("#info .desc")?.text()?.trim()
            year = released
            addActors(cast)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = siteLink(data) ?: return false
        return KShow123Player(mainUrl).load(episodeUrl, subtitleCallback, callback)
    }
}
