package com.kshow123

import android.os.SystemClock
import android.util.Log
import com.lagradost.cloudstream3.*
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

    override suspend fun load(url: String): LoadResponse? {
        throw ErrorLoadingException("KShow123 tahap 1: homepage sahaja. Detail, episod dan player belum tersedia.")
    }
}
