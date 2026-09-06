package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AnichinProvider : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Release",
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Movie"
    )

    private data class PlayerOption(
        val label: String,
        val url: String
    )

    private data class ParsedEpisode(
        val data: String,
        val number: Double?,
        val name: String
    )

    /**
     * Current series pages live at /{series-slug}/ while homepage cards for
     * Latest/Popular often point at /{series-slug}-episode-N-.../.
     */
    private fun normalizeCatalogUrl(rawHref: String): String? {
        val absolute = absoluteUrl(mainUrl, rawHref) ?: return null
        val clean = absolute.substringBefore('#')
        val slug = runCatching {
            URI(clean).path.trim('/').substringAfterLast('/')
        }.getOrNull().orEmpty()

        if (slug.isBlank()) return clean

        val seriesSlug = EPISODE_SLUG_SUFFIX.replace(slug, "")
        return if (seriesSlug != slug && seriesSlug.isNotBlank()) {
            "$mainUrl/$seriesSlug/"
        } else {
            clean
        }
    }

    private fun cleanCatalogTitle(raw: String): String {
        val title = raw
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (title.isBlank()) return title

        return title
            .removePrefix("Nonton ")
            .replace(
                Regex(
                    """\s+(?:Episode|Ep|Eps)\s*\d+(?:\.\d+)?(?:\s*(?:Tamat|END))?.*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()
            .ifBlank { title }
    }

    private fun cleanDetailTitle(raw: String): String {
        return raw
            .replace(Regex("""\s+"""), " ")
            .trim()
            .removePrefix("Nonton ")
            .substringBefore(" - Anichin")
            .replace(
                Regex("""\s+Subtitle\s+Indonesia$""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()
    }

    private fun cardPoster(item: Element): String? {
        val image = item.selectFirst(".limit img, .bsx img, img") ?: return null
        val raw = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }
            .trim()

        return absoluteUrl(mainUrl, raw)
    }

    private fun episodeBadgeNumber(text: String): Double? {
        return Regex(
            """(?:Episode|Ep|Eps)\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun parseItems(
        root: Element,
        selector: String,
        forcedType: TvType? = null
    ): List<SearchResponse> {
        return root.select(selector).mapNotNull { item ->
            val anchor = item.selectFirst(".bsx > a[href], a[href]")
                ?: return@mapNotNull null

            val href = normalizeCatalogUrl(anchor.attr("href"))
                ?: return@mapNotNull null

            if (!href.startsWith(mainUrl)) return@mapNotNull null

            val rawTitle = item.selectFirst(".tt")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2, .tt h3, h2, h3")?.text()?.trim()?.ifBlank { null }
                ?: anchor.attr("title").trim().ifBlank { null }
                ?: anchor.selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val title = cleanCatalogTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null

            val typeText = item.selectFirst(".typez, .type, .status")?.text().orEmpty()
            val tvType = forcedType ?: when {
                typeText.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                title.contains(" Movie:", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val badgeText = item.selectFirst(".bt .epx, .epx, .ep, .status")?.text().orEmpty()
            val ep = episodeBadgeNumber(badgeText)

            newAnimeSearchResponse(title, href, tvType) {
                posterUrl = cardPoster(item)

                // Cloudstream's catalogue badge is integer based. Never turn
                // episode 8.5 into the incorrect episode 8.
                if (ep != null && ep % 1.0 == 0.0) {
                    addSub(ep.toInt())
                }
            }
        }
            .distinctBy { it.url }
    }

    private fun headingText(element: Element): String {
        val own = element.ownText().trim()
        if (own.isNotBlank()) return own

        return element.selectFirst("h1, h2, h3, h4, h5")
            ?.ownText()
            ?.trim()
            .orEmpty()
    }

    private fun findSectionContainer(
        doc: Document,
        vararg labels: String
    ): Element? {
        val heading = doc.select("h1, h2, h3, h4, h5, .releases")
            .firstOrNull { element ->
                val text = headingText(element)
                text.isNotBlank() && labels.any { label ->
                    text.equals(label, ignoreCase = true) ||
                        text.contains(label, ignoreCase = true)
                }
            } ?: return null

        val marker = heading.closest(".releases") ?: heading
        var sibling = marker.nextElementSibling()

        repeat(8) {
            val current = sibling ?: return null

            val currentHeading = headingText(current)
            if (
                current.hasClass("releases") ||
                current.matches("h1, h2, h3, h4, h5")
            ) {
                if (currentHeading.isNotBlank()) return null
            }

            if (
                current.select("article.bs, .bsx").isNotEmpty() ||
                current.matches(".listupd")
            ) {
                return current
            }

            sibling = current.nextElementSibling()
        }

        return null
    }

    private fun parseArchiveItems(
        doc: Document,
        forcedType: TvType? = null
    ): List<SearchResponse> {
        val selectors = listOf(
            ".postbody .listupd article.bs",
            "main .listupd article.bs",
            "#content .listupd article.bs",
            ".listupd.normal article.bs"
        )

        selectors.forEach { selector ->
            val parsed = parseItems(doc, selector, forcedType)
            if (parsed.isNotEmpty()) return parsed
        }

        return parseItems(
            doc,
            ".listupd article.bs",
            forcedType
        )
    }

    private fun archiveUrl(
        page: Int,
        type: String = ""
    ): String {
        val encodedType = URLEncoder.encode(type, "UTF-8")
        return if (page <= 1) {
            "$mainUrl/anime/?order=update&status=&type=$encodedType"
        } else {
            "$mainUrl/anime/?page=$page&order=update&status=&type=$encodedType"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.name) {
            "Latest Release" -> {
                // Use the same update-sorted archive for every page so page 1
                // and later pages cannot drift into different ordering.
                parseArchiveItems(
                    app.get(archiveUrl(page)).document
                )
            }

            "Popular Today" -> {
                // "Terpopuler Hari Ini" is a homepage-only list. Do not
                // substitute generic archive pages on later pagination.
                if (page > 1) {
                    emptyList()
                } else {
                    val home = app.get(mainUrl).document
                    val section = findSectionContainer(
                        home,
                        "Terpopuler Hari Ini",
                        "Popular Today"
                    )

                    val homepageItems = section?.let {
                        parseItems(it, "article.bs, .bs")
                    }.orEmpty()

                    if (homepageItems.isNotEmpty()) {
                        homepageItems
                    } else {
                        val archive = app.get("$mainUrl/anime/").document
                        val popular = findSectionContainer(
                            archive,
                            "Donghua Paling Populer"
                        )
                        popular?.let {
                            parseItems(it, "article.bs, .bs")
                        }.orEmpty()
                    }
                }
            }

            "Movie" -> {
                if (page == 1) {
                    val home = app.get(mainUrl).document
                    val section = findSectionContainer(home, "Movie")

                    val homepageItems = section?.let {
                        parseItems(
                            it,
                            "article.bs, .bs",
                            TvType.AnimeMovie
                        )
                    }.orEmpty()

                    if (homepageItems.isNotEmpty()) {
                        homepageItems
                    } else {
                        parseArchiveItems(
                            app.get(archiveUrl(1, "Movie")).document,
                            TvType.AnimeMovie
                        )
                    }
                } else {
                    parseArchiveItems(
                        app.get(archiveUrl(page, "Movie")).document,
                        TvType.AnimeMovie
                    )
                }
            }

            else -> emptyList()
        }

        return newHomePageResponse(
            request.name,
            items.distinctBy { it.url }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        if (encoded.isBlank()) return emptyList()

        val doc = app.get("$mainUrl/?s=$encoded").document
        return parseArchiveItems(doc)
    }

    private fun episodeNumberFrom(
        href: String,
        numberText: String,
        titleText: String
    ): Double? {
        val explicit = Regex(
            """(?:Episode|Ep|Eps)\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find("$numberText $titleText")
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        if (explicit != null) return explicit

        val fromHref = Regex(
            """-episode-(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        if (fromHref != null) return fromHref

        return Regex("""^\s*(\d+(?:\.\d+)?)\b""")
            .find(numberText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun formatEpisodeNumber(number: Double): String {
        return if (number % 1.0 == 0.0) {
            number.toInt().toString()
        } else {
            number.toString().trimEnd('0').trimEnd('.')
        }
    }

    private fun seriesSlugFromUrl(
        seriesUrl: String?
    ): String? {
        if (seriesUrl.isNullOrBlank()) return null

        val slug = try {
            URI(seriesUrl)
                .path
                .trim('/')
                .substringAfterLast('/')
        } catch (_: Exception) {
            return null
        }

        return slug
            .takeIf { it.isNotBlank() }
            ?.let { EPISODE_SLUG_SUFFIX.replace(it, "") }
    }

    private fun getParsedEpisodes(
        doc: Document,
        seriesUrl: String? = null
    ): List<ParsedEpisode> {
        val primaryAnchors = doc.select(
            ".eplister li a[href], " +
                ".eplister a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href], " +
                ".bixbox.bxcl.epcheck a[href]"
        )

        val anchors: List<Element> = if (primaryAnchors.isNotEmpty()) {
            primaryAnchors.toList()
        } else {
            // Last-resort fallback is restricted to the current series slug.
            // This prevents sidebar/recommendation episodes from another title
            // being injected into this series.
            val seriesSlug = seriesSlugFromUrl(seriesUrl)
            if (seriesSlug == null) {
                emptyList()
            } else {
                val contentRoot = doc.selectFirst(
                    ".postbody, .bigcontent, main, #content"
                ) ?: doc

                contentRoot
                    .select(
                        "a[href*='-episode-'], " +
                            "a[href*='-subtitle-indonesia']"
                    )
                    .filter { anchor ->
                        val href = absoluteUrl(
                            seriesUrl ?: mainUrl,
                            anchor.attr("href")
                        ) ?: return@filter false

                        val hrefSlug = try {
                            URI(href)
                                .path
                                .trim('/')
                                .substringAfterLast('/')
                        } catch (_: Exception) {
                            return@filter false
                        }

                        hrefSlug.startsWith(
                            "$seriesSlug-episode-",
                            ignoreCase = true
                        ) ||
                            hrefSlug.equals(
                                "$seriesSlug-subtitle-indonesia",
                                ignoreCase = true
                            ) ||
                            hrefSlug.startsWith(
                                "$seriesSlug-subtitle-indonesia-",
                                ignoreCase = true
                            )
                    }
            }
        }

        return anchors.mapNotNull { anchor ->
            val href = absoluteUrl(
                seriesUrl ?: mainUrl,
                anchor.attr("href")
            ) ?: return@mapNotNull null

            if (!href.startsWith(mainUrl)) return@mapNotNull null

            val numberText = anchor.selectFirst(
                ".epl-num, .epnum, .episode-number, [data-num]"
            )?.text()?.trim().orEmpty()

            val titleText = anchor.selectFirst(
                ".epl-title, .episode-title, .title"
            )?.text()?.trim()?.ifBlank { null }
                ?: anchor.text().replace(Regex("""\s+"""), " ").trim().ifBlank { null }
                ?: return@mapNotNull null

            val number = episodeNumberFrom(
                href,
                numberText,
                titleText
            )

            val isEnd = titleText.contains("Tamat", ignoreCase = true) ||
                Regex("""\bEND\b""", RegexOption.IGNORE_CASE).containsMatchIn(titleText)

            val name = when {
                number != null -> {
                    "Episode ${formatEpisodeNumber(number)}" +
                        if (isEnd) " END" else ""
                }

                titleText.contains("Movie", ignoreCase = true) -> "Movie"

                else -> titleText
            }

            ParsedEpisode(
                data = href,
                number = number,
                name = name
            )
        }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<ParsedEpisode> { it.number ?: Double.MAX_VALUE }
                    .thenBy { it.name }
            )
    }

    private fun getEpisodesFromDocument(
        doc: Document,
        poster: String?,
        seriesUrl: String? = null
    ): MutableList<Episode> {
        return getParsedEpisodes(doc, seriesUrl)
            .map { parsed ->
                newEpisode(parsed.data) {
                    name = parsed.name
                    posterUrl = poster

                    // Keep decimal episodes truthful in the visible name.
                    // The current Cloudstream episode index is integer based.
                    val number = parsed.number
                    if (number != null && number % 1.0 == 0.0) {
                        episode = number.toInt()
                    }
                }
            }
            .toMutableList()
    }

    private fun extractInfoMap(doc: Document): Map<String, String> {
        val labels = listOf(
            "Status",
            "Network",
            "Studio",
            "Tanggal rilis",
            "Durasi",
            "Season",
            "Negara",
            "Tipe",
            "Type",
            "Episode",
            "Subber",
            "Diposting oleh",
            "Ditambahkan",
            "Diperbarui pada"
        )

        val labelPattern = labels.joinToString("|") {
            Regex.escape(it)
        }

        val regex = Regex(
            """(?i)($labelPattern)\s*:\s*(.*?)(?=(?:$labelPattern)\s*:|$)"""
        )

        val blocks = doc.select(
            ".spe span, .info-content .spe span, .spe li, " +
                ".info-content .spe, .spe"
        )

        val info = linkedMapOf<String, String>()

        blocks.forEach { element ->
            val text = element.text()
                .replace(Regex("""\s+"""), " ")
                .trim()

            regex.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                    .lowercase()
                    .trim()

                val value = match.groupValues[2]
                    .trim()
                    .trimEnd(',')

                if (value.isNotBlank() && key !in info) {
                    info[key] = value
                }
            }
        }

        return info
    }

    private fun isSeoSynopsis(text: String): Boolean {
        val lower = text.lowercase()

        if (lower.startsWith("tonton streaming")) return true
        if (lower.startsWith("nonton ") && lower.contains("terlengkap")) return true

        val seoHits = listOf(
            "download gratis",
            "berbagai kualitas",
            "menghemat kuota",
            "mp4 mkv",
            "hardsub softsub",
            "streaming online",
            "di anichin"
        ).count { lower.contains(it) }

        return seoHits >= 2
    }

    private fun cleanSynopsisCandidate(
        raw: String,
        title: String
    ): String? {
        var text = raw
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (text.isBlank()) return null

        text = text
            .replace(
                Regex(
                    """^Sinopsis\s+${Regex.escape(title)}\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    """^${Regex.escape(title)}\s*[–—-]\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex("""^Sinopsis\s*:\s*""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()

        if (text.length < 40) return null
        if (isSeoSynopsis(text)) return null

        return text
    }

    private fun collectFollowingSynopsis(
        heading: Element,
        title: String
    ): String? {
        val paragraphs = mutableListOf<String>()
        var node = heading.nextElementSibling()

        repeat(12) {
            val current = node ?: return@repeat
            val tag = current.tagName().lowercase()
            val currentText = current.text().trim()

            if (tag in setOf("h1", "h2", "h3", "h4", "h5")) {
                node = null
                return@repeat
            }

            val rawParagraphs = if (tag == "p") {
                listOf(current.text())
            } else {
                current.select("p").map { it.text() }
            }

            rawParagraphs
                .mapNotNull { cleanSynopsisCandidate(it, title) }
                .forEach(paragraphs::add)

            node = current.nextElementSibling()
        }

        return paragraphs
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
    }

    private fun extractSynopsis(
        doc: Document,
        title: String
    ): String? {
        val synopsisHeading = doc.select("h2, h3, h4, h5")
            .firstOrNull {
                it.text().contains("Sinopsis", ignoreCase = true)
            }

        if (synopsisHeading != null) {
            collectFollowingSynopsis(
                synopsisHeading,
                title
            )?.let { return it }

            // Some templates put a title heading immediately after "Sinopsis".
            val titleHeading = synopsisHeading
                .nextElementSibling()
                ?.takeIf {
                    it.matches("h2, h3, h4, h5")
                }

            if (titleHeading != null) {
                collectFollowingSynopsis(
                    titleHeading,
                    title
                )?.let { return it }
            }

            val container = synopsisHeading.closest(".bixbox")
                ?: synopsisHeading.parent()

            if (container != null) {
                val paragraphs = container
                    .select(".entry-content p, .desc p, p")
                    .mapNotNull {
                        cleanSynopsisCandidate(
                            it.text(),
                            title
                        )
                    }
                    .distinct()

                if (paragraphs.isNotEmpty()) {
                    return paragraphs.joinToString("\n\n")
                }
            }
        }

        val knownContainers = doc.select(
            ".bixbox.synp .entry-content, " +
                ".synp .entry-content, " +
                ".entry-content[itemprop=description], " +
                ".synopsis, .sinopsis"
        )

        knownContainers.forEach { container ->
            val paragraphs = container
                .select("p")
                .mapNotNull {
                    cleanSynopsisCandidate(
                        it.text(),
                        title
                    )
                }
                .distinct()

            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("\n\n")
            }

            cleanSynopsisCandidate(
                container.text(),
                title
            )?.let { return it }
        }

        // Movie/episode templates can omit the "Sinopsis" heading and only
        // show a heading equal to the title followed by multiple paragraphs.
        doc.select("h2, h3, h4, h5")
            .firstOrNull { heading ->
                cleanDetailTitle(heading.text())
                    .equals(title, ignoreCase = true)
            }
            ?.let { heading ->
                collectFollowingSynopsis(
                    heading,
                    title
                )?.let { return it }
            }

        // Final conservative fallback. Never use SEO meta/OG descriptions.
        return doc.select("p")
            .mapNotNull {
                cleanSynopsisCandidate(
                    it.text(),
                    title
                )
            }
            .filterNot {
                it.contains("server streaming", ignoreCase = true) ||
                    it.contains("grup telegram", ignoreCase = true)
            }
            .maxByOrNull { it.length }
    }

    private fun extractReleaseYear(
        info: Map<String, String>
    ): Int? {
        val release = info["tanggal rilis"]
        val fromRelease = Regex("""\b(19|20)\d{2}\b""")
            .find(release.orEmpty())
            ?.value
            ?.toIntOrNull()

        if (fromRelease != null) return fromRelease

        // Season is an acceptable fallback because it describes the title,
        // unlike "Diperbarui pada" which is only the website update date.
        return Regex("""\b(19|20)\d{2}\b""")
            .find(info["season"].orEmpty())
            ?.value
            ?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = normalizeCatalogUrl(url) ?: url
        val doc = app.get(animeUrl).document

        val rawTitle = doc.selectFirst(
            "h1.entry-title, .infox h1, .infolimit h2, h1"
        )?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.ifBlank { null }
            ?: throw ErrorLoadingException("Title not found")

        val title = cleanDetailTitle(rawTitle)

        val poster = doc.selectFirst(
            ".thumb img, .bigcontent .thumb img, .infox img"
        )?.let { image ->
            val raw = image.attr("data-src")
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("data-original") }
                .ifBlank { image.attr("src") }

            absoluteUrl(animeUrl, raw)
        } ?: doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.let { absoluteUrl(animeUrl, it) }

        val info = extractInfoMap(doc)
        val statusText = info["status"]
        val typeText = info["tipe"] ?: info["type"]
        val year = extractReleaseYear(info)

        val synopsis = extractSynopsis(doc, title)

        val tags = doc.select(
            ".genxed a, .genx a, a[rel=tag][href*='/genres/']"
        )
            .mapNotNull { it.text().trim().ifBlank { null } }
            .filterNot { it.contains("Animation", ignoreCase = true) }
            .distinct()

        val parsedEpisodes = getParsedEpisodes(doc, animeUrl)
        val episodes = getEpisodesFromDocument(doc, poster, animeUrl)

        val isMovie = when {
            typeText?.contains("Movie", ignoreCase = true) == true -> true
            title.contains(" Movie:", ignoreCase = true) -> true
            title.endsWith(" Movie", ignoreCase = true) -> true
            animeUrl.contains("-movie-", ignoreCase = true) -> true
            else -> false
        }

        if (isMovie) {
            // Movie series pages usually point to a separate episode/post that
            // contains the actual player. Use that post as loadLinks data.
            val movieData = parsedEpisodes.firstOrNull()?.data ?: animeUrl

            return newMovieLoadResponse(
                title,
                animeUrl,
                TvType.AnimeMovie,
                movieData
            ) {
                posterUrl = poster
                plot = synopsis
                this.tags = tags
                this.year = year
            }
        }

        return newAnimeLoadResponse(
            title,
            animeUrl,
            TvType.Anime
        ) {
            engName = title
            posterUrl = poster

            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }

            plot = synopsis
            this.tags = tags
            this.year = year

            showStatus = when {
                statusText?.contains("Completed", ignoreCase = true) == true ->
                    ShowStatus.Completed

                statusText?.contains("Ongoing", ignoreCase = true) == true ->
                    ShowStatus.Ongoing

                else -> null
            }

            doc.selectFirst("[data-alid], [data-anilist]")?.let { element ->
                val id = element.attr("data-alid")
                    .ifBlank { element.attr("data-anilist") }
                    .toIntOrNull()

                if (id != null) addAniListId(id)
            }

            doc.selectFirst("[data-malid], [data-mal]")?.let { element ->
                val id = element.attr("data-malid")
                    .ifBlank { element.attr("data-mal") }
                    .toIntOrNull()

                if (id != null) addMalId(id)
            }
        }
    }

    private fun absoluteUrl(
        base: String,
        raw: String
    ): String? {
        val value =
            raw.trim()
                .replace("&amp;", "&")
                .replace("\\/", "/")

        if (value.isBlank()) {
            return null
        }

        if (value.startsWith(
                "javascript:",
                ignoreCase = true
            )
        ) {
            return null
        }

        return try {
            when {
                value.startsWith("//") -> {
                    val scheme =
                        URI(base).scheme ?: "https"

                    "$scheme:$value"
                }

                value.startsWith("http://") ||
                    value.startsWith("https://") -> {
                    value
                }

                else -> {
                    URI(base)
                        .resolve(value)
                        .toString()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePlayerValue(
        rawValue: String,
        baseUrl: String
    ): String? {
        val value = rawValue.trim()
        if (value.isBlank()) return null

        // Only resolve values that are clearly URLs/paths. A Base64 payload is
        // otherwise a valid relative-URI string and would be misread as a path.
        val looksLikeUrl = value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//") ||
            value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../")

        if (looksLikeUrl) {
            return absoluteUrl(baseUrl, value)
        }

        // Some player options contain raw iframe HTML.
        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        val decoded = runCatching {
            String(Base64.decode(value, Base64.DEFAULT))
        }.getOrNull() ?: return null

        Regex(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return absoluteUrl(baseUrl, it) }

        return Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).find(decoded)
            ?.value
            ?.let { absoluteUrl(baseUrl, it) }
    }

    private fun Document.collectTopLevelPlayers(
        pageUrl: String
    ): List<PlayerOption> {
        val players =
            mutableListOf<PlayerOption>()

        select(
            "#embed_holder iframe[src], " +
                "#embed_holder iframe[data-src], " +
                ".player-embed iframe[src], " +
                ".player-embed iframe[data-src], " +
                ".embed_holder iframe[src], " +
                ".embed_holder iframe[data-src], " +
                "iframe.metaframe[src]"
        ).forEachIndexed { index, iframe ->
            val src =
                iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }

            val url =
                absoluteUrl(
                    pageUrl,
                    src
                )
                    ?: return@forEachIndexed

            players.add(
                PlayerOption(
                    label = "Direct ${index + 1}",
                    url = url
                )
            )
        }

        select(
            ".mobius option, " +
                "select.mirror option, " +
                ".mirror option, " +
                ".server option, " +
                "option[data-index], " +
                "option[data-video], " +
                "option[data-src]"
        ).forEach { option ->
            val label =
                option.text()
                    .trim()
                    .ifBlank {
                        option.attr("data-index")
                            .trim()
                    }
                    .ifBlank {
                        "Server"
                    }

            val candidates = listOf(
                option.attr("value"),
                option.attr("data-video"),
                option.attr("data-src"),
                option.attr("data-embed")
            )

            candidates.forEach { raw ->
                val url =
                    decodePlayerValue(
                        raw,
                        pageUrl
                    )
                        ?: return@forEach

                players.add(
                    PlayerOption(
                        label = label,
                        url = url
                    )
                )
            }
        }

        return players
            .filter {
                it.url.startsWith("http")
            }
            .distinctBy {
                it.url
            }
            .sortedBy {
                it.priority()
            }
    }

    private fun Document.collectNestedPlayerUrls(
        pageUrl: String
    ): List<String> {
        val urls =
            mutableListOf<String>()

        select(
            "iframe[src], " +
                "iframe[data-src], " +
                "video source[src], " +
                "source[src]"
        ).forEach { element ->
            val raw =
                element.attr("src")
                    .ifBlank {
                        element.attr("data-src")
                    }

            absoluteUrl(
                pageUrl,
                raw
            )?.let {
                urls.add(it)
            }
        }

        select("script").forEach { script ->
            val text =
                script.data()
                    .ifBlank {
                        script.html()
                    }

            Regex(
                """(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+)["']""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.MULTILINE
                )
            ).findAll(text)
                .forEach { match ->
                    match.groupValues
                        .getOrNull(1)
                        ?.let {
                            absoluteUrl(
                                pageUrl,
                                it
                            )
                        }
                        ?.let {
                            urls.add(it)
                        }
                }

            Regex(
                """https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(text)
                .forEach { match ->
                    urls.add(
                        match.value
                            .replace("\\/", "/")
                    )
                }
        }

        return urls
            .filter {
                it.startsWith("http")
            }
            .distinct()
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String
    ): Document? {
        return try {
            withTimeoutOrNull(PLAYER_REQUEST_TIMEOUT_MS) {
                app.get(url, referer = referer).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val attemptKey =
            "$url\u0000$referer"

        if (!attemptedUrls.add(attemptKey)) {
            return false
        }

        val emitted =
            AtomicBoolean(false)

        val wrappedCallback:
            (ExtractorLink) -> Unit = { link ->

            if (emittedUrls.add(link.url)) {
                emitted.set(true)
                callback(link)
            }
        }

        return try {
            withTimeoutOrNull(
                EXTRACTOR_TIMEOUT_MS
            ) {
                loadExtractor(
                    url,
                    referer,
                    subtitleCallback,
                    wrappedCallback
                )
            }

            emitted.get()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun <T> collectSuccessful(
        items: List<T>,
        concurrency: Int,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) {
            return@coroutineScope false
        }

        val semaphore =
            Semaphore(
                concurrency.coerceAtLeast(1)
            )

        items.map { item ->
            async {
                semaphore.withPermit {
                    try {
                        block(item)
                    } catch (
                        e: CancellationException
                    ) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
            .awaitAll()
            .any {
                it
            }
    }

    /**
     * Hybrid two-lane scheduler for Cloudstream link loading.
     *
     * The first three priority players get their own fast lane so OK.ru,
     * Dailymotion, Rumble, or whichever sources are ranked first can resolve
     * immediately. The rest of the player list is processed at the same time
     * in a separate bounded lane.
     *
     * Nothing is cancelled after the first success. Extractor callbacks are
     * emitted as soon as each source resolves, while all remaining sources
     * continue inside the same Cloudstream coroutine lifecycle.
     */
    private suspend fun <T> collectTwoLane(
        items: List<T>,
        block: suspend (T) -> Boolean
    ): Boolean = coroutineScope {
        if (items.isEmpty()) {
            return@coroutineScope false
        }

        val fastLane =
            items.take(FAST_LANE_SIZE)

        val fullLane =
            items.drop(FAST_LANE_SIZE)

        val fastJob = async {
            collectSuccessful(
                fastLane,
                FAST_LANE_CONCURRENCY,
                block
            )
        }

        val fullJob = async {
            collectSuccessful(
                fullLane,
                FULL_LANE_CONCURRENCY,
                block
            )
        }

        listOf(
            fastJob,
            fullJob
        )
            .awaitAll()
            .any { it }
    }

    private suspend fun resolvePlayerPipeline(
        wrapperUrl: String,
        episodeUrl: String,
        attemptedUrls: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directSuccess =
            tryLoadExtractor(
                wrapperUrl,
                episodeUrl,
                attemptedUrls,
                emittedUrls,
                subtitleCallback,
                callback
            )

        if (directSuccess) {
            return true
        }

        val wrapperDocument =
            fetchDocument(
                wrapperUrl,
                episodeUrl
            )
                ?: return false

        val playerUrls =
            wrapperDocument
                .collectNestedPlayerUrls(
                    wrapperUrl
                )

        return collectSuccessful(
            playerUrls,
            MAX_NESTED_CONCURRENCY
        ) { playerUrl ->

            val playerSuccess =
                tryLoadExtractor(
                    playerUrl,
                    wrapperUrl,
                    attemptedUrls,
                    emittedUrls,
                    subtitleCallback,
                    callback
                )

            if (playerSuccess) {
                true
            } else {
                val nestedDocument =
                    fetchDocument(
                        playerUrl,
                        wrapperUrl
                    )

                if (nestedDocument == null) {
                    false
                } else {
                    val nestedUrls =
                        nestedDocument
                            .collectNestedPlayerUrls(
                                playerUrl
                            )

                    collectSuccessful(
                        nestedUrls,
                        MAX_NESTED_CONCURRENCY
                    ) { nestedUrl ->
                        tryLoadExtractor(
                            nestedUrl,
                            playerUrl,
                            attemptedUrls,
                            emittedUrls,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {
        val document =
            withTimeoutOrNull(
                EPISODE_REQUEST_TIMEOUT_MS
            ) {
                app.get(data).document
            }
                ?: return false

        // Most Anichin videos are hard-subbed. Only expose extractor
        // subtitles when the episode page explicitly tells viewers to enable
        // CC, which covers special releases without showing useless generated
        // tracks on normal episodes.
        val needsClosedCaptions =
            document.text().contains(
                "AKTIFKAN SUB CC",
                ignoreCase = true
            )

        val emittedSubtitleUrls:
            MutableSet<String> =
            ConcurrentHashMap
                .newKeySet()

        val effectiveSubtitleCallback:
            (SubtitleFile) -> Unit = { subtitle ->
                if (
                    needsClosedCaptions &&
                    emittedSubtitleUrls.add(subtitle.url)
                ) {
                    subtitleCallback(subtitle)
                }
            }

        val attemptedUrls:
            MutableSet<String> =
            ConcurrentHashMap
                .newKeySet()

        val emittedUrls:
            MutableSet<String> =
            ConcurrentHashMap
                .newKeySet()

        val players =
            document
                .collectTopLevelPlayers(data)

        if (players.isEmpty()) {
            val staticPlayers =
                document
                    .collectNestedPlayerUrls(data)

            return collectTwoLane(
                staticPlayers
            ) { playerUrl ->
                tryLoadExtractor(
                    playerUrl,
                    data,
                    attemptedUrls,
                    emittedUrls,
                    effectiveSubtitleCallback,
                    callback
                )
            }
        }

        return collectTwoLane(
            players
        ) { player ->
            resolvePlayerPipeline(
                player.url,
                data,
                attemptedUrls,
                emittedUrls,
                effectiveSubtitleCallback,
                callback
            )
        }
    }

    private fun PlayerOption.priority(): Int {
        val value =
            "$label $url".lowercase()

        return when {
            value.contains("ok.ru") ||
                value.contains("okru") -> 0

            value.contains("dailymotion") -> 1

            value.contains("rumble") -> 2

            value.contains("anichin.stream") -> 3

            value.contains(
                "anichin-player.web.id"
            ) -> 4

            value.contains("streamruby") ||
                value.contains("ruby") -> 5

            value.contains("vidhide") -> 6

            else -> 20
        }
    }

    companion object {
        private val EPISODE_SLUG_SUFFIX = Regex(
            """-episode-\d+(?:\.\d+)?(?:-[^/]*)?$""",
            RegexOption.IGNORE_CASE
        )

        // Top priority sources get three dedicated workers. The remaining
        // sources get another three workers, giving fast first-link response
        // without sacrificing the rest of the server list.
        private const val FAST_LANE_SIZE = 3
        private const val FAST_LANE_CONCURRENCY = 3
        private const val FULL_LANE_CONCURRENCY = 3
        private const val MAX_NESTED_CONCURRENCY = 2

        private const val EPISODE_REQUEST_TIMEOUT_MS =
            10_000L

        private const val PLAYER_REQUEST_TIMEOUT_MS =
            7_000L

        private const val EXTRACTOR_TIMEOUT_MS =
            8_000L
    }
}
