package com.moviebox

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieboxProvider : MainAPI() {

    override var mainUrl = "https://movieboxhd.net"
    override var name = "MovieBox 👾 v20"
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
     * Current H5 API mirrors. These are API-capable hosts, not just public
     * landing-page domains. Search/detail/play must use this pool.
     */
    private val webHosts = listOf(
        "https://h5.aoneroom.com",
        "https://movieboxapp.in",
        "https://moviebox.pk",
        "https://moviebox.ph",
        "https://moviebox.id",
        "https://v.moviebox.ph",
        "https://netnaija.video"
    )

    /*
     * Mirror strategy:
     * search and detail try the preferred/current host first, then fall back
     * to the remaining mirrors. The first valid response wins and the host is
     * remembered for later requests.
     */
    @Volatile
    private var preferredWebHost: String? = null

    /*
     * Signed Android mobile API is retained as a fallback. The current H5
     * client still exposes /wefeed-h5-bff/web/subject/search and is simpler
     * and more reliable for Cloudstream search.
     */
    private val mobileHosts = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    /*
     * Playback-only Android host order copied from the working moviebox.js.
     * Search/detail keep using mobileHosts above unchanged.
     */
    private val playbackMobileHosts = listOf(
        "https://api.inmoviebox.com",
        "https://apii.inmoviebox.com",
        "https://i-api.aoneroom.com",
        "https://api.aoneroom.com",
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com"
    )

    private val mobileSearchPath = "/wefeed-mobile-bff/subject-api/search"
    private val mobileResourcePath = "/wefeed-mobile-bff/subject-api/resource"
    private val mobileBootstrapPath = "/wefeed-mobile-bff/tab-operating"
    private val mobileSigningSecretB64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private val mobileUserAgent =
        "com.community.oneroom/50020044 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)"

    private val mobileDeviceId = UUID.randomUUID().toString().replace("-", "").lowercase()
    private val mobileGaid = UUID.randomUUID().toString()

    @Volatile
    private var preferredMobileHost: String? = null

    @Volatile
    private var mobileAuthToken: String? = null

    @Volatile
    private var playbackAuthToken: String? = null

    @Volatile
    private var preferredPlaybackMobileHost: String? = null

    private val mobileRequestTimeoutSeconds = 6L

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
        "Accept-Language" to "en-US,en;q=0.5",
        "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
        "User-Agent" to "moviebox-js-sdk/preview",
        "Content-Type" to "application/json"
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

    private inline fun <reified T : Any> parseJsonSafe(raw: String): T? = try {
        parseJson<T>(raw)
    } catch (_: Throwable) {
        null
    }

    /*
     * Mobile search currently returns some metadata fields with inconsistent
     * scalar types between titles, for example duration may be a formatted
     * string instead of a number. Search only needs these four fields, so keep
     * this response model deliberately narrow and permissive. This prevents an
     * unrelated metadata field from making the whole JSON response unparsable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MobileSearchEnvelope(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: MobileSearchData? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MobileSearchData(
        @JsonProperty("items") val items: List<MobileSearchItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MobileSearchItem(
        @JsonProperty("subjectId") val subjectId: Any? = null,
        @JsonProperty("subjectType") val subjectType: Any? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: MobileSearchCover? = null,
    ) {
        fun toItem(): Items? {
            val id = subjectId?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val name = title?.takeIf { it.isNotBlank() } ?: return null
            val type = when (val raw = subjectType) {
                is Number -> raw.toInt()
                else -> raw?.toString()?.toIntOrNull()
            }
            return Items(
                subjectId = id,
                subjectType = type,
                title = name,
                cover = cover?.url?.let { Items.Cover(url = it) }
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MobileSearchCover(
        @JsonProperty("url") val url: String? = null,
    )

    private fun md5Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private fun mobileClientInfo(): String = linkedMapOf<String, Any>(
        "package_name" to "com.community.oneroom",
        "version_name" to "3.0.03.0529.03",
        "version_code" to 50020044,
        "os" to "android",
        "os_version" to "13",
        "install_ch" to "ps",
        "device_id" to mobileDeviceId,
        "install_store" to "ps",
        "gaid" to mobileGaid,
        "brand" to "Redmi",
        "model" to "23078RKD5C",
        "system_language" to "en",
        "net" to "NETWORK_WIFI",
        "region" to "US",
        "timezone" to "America/New_York",
        "sp_code" to "40401",
        "X-Play-Mode" to "2"
    ).toJson()

    private fun canonicalMobileUrl(url: String): String {
        val uri = URI(url)
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val rawQuery = uri.rawQuery.orEmpty()
        if (rawQuery.isBlank()) return path

        val sorted = rawQuery
            .split("&")
            .filter { it.isNotEmpty() }
            .map { pair ->
                val keyRaw = pair.substringBefore("=")
                val valueRaw = pair.substringAfter("=", "")
                val key = URLDecoder.decode(keyRaw, "UTF-8")
                val value = URLDecoder.decode(valueRaw, "UTF-8")
                key to value
            }
            .sortedBy { it.first }

        return "$path?" + sorted.joinToString("&") { (key, value) -> "$key=$value" }
    }

    private fun buildMobileHeaders(
        method: String,
        url: String,
        body: String?,
        authToken: String?
    ): Map<String, String> {
        val accept = "application/json"
        val contentType = if (body != null) {
            "application/json; charset=utf-8"
        } else {
            "application/json"
        }
        val timestamp = System.currentTimeMillis()
        val timestampText = timestamp.toString()
        val clientTokenHash = md5Hex(timestampText.reversed().toByteArray(Charsets.UTF_8))
        val clientToken = "$timestampText,$clientTokenHash"

        var bodyHash = ""
        var bodyLength = ""
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            bodyLength = bytes.size.toString()
            bodyHash = md5Hex(bytes.copyOfRange(0, minOf(bytes.size, 102400)))
        }

        val canonical = listOf(
            method.uppercase(),
            accept,
            contentType,
            bodyLength,
            timestampText,
            bodyHash,
            canonicalMobileUrl(url)
        ).joinToString("\n")

        val padding = (4 - mobileSigningSecretB64.length % 4) % 4
        val secret = mobileSigningSecretB64 + "=".repeat(padding)
        val secretBytes = Base64.decode(secret, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signatureBase64 = Base64.encodeToString(
            mac.doFinal(canonical.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        return buildMap {
            put("User-Agent", mobileUserAgent)
            put("Accept", accept)
            put("Content-Type", contentType)
            put("Connection", "keep-alive")
            put("X-Client-Token", clientToken)
            put("x-tr-signature", "$timestampText|2|$signatureBase64")
            put("X-Client-Info", mobileClientInfo())
            put("X-Client-Status", "0")
            put("X-Play-Mode", "2")
            put("X-Forwarded-For", "197.210.65.1")
            put("Cache-Control", "no-cache, no-store, must-revalidate")
            put("Pragma", "no-cache")
            put("Expires", "0")
            authToken?.takeIf { it.isNotBlank() }?.let { token ->
                put("Authorization", "Bearer $token")
            }
        }
    }

    private fun playbackClientInfo(): String = linkedMapOf<String, Any>(
        "package_name" to "com.community.oneroom",
        "version_name" to "3.0.03.0529.03",
        "version_code" to 50020044,
        "os" to "android",
        "os_version" to "13",
        "install_ch" to "ps",
        "device_id" to mobileDeviceId,
        "install_store" to "ps",
        "gaid" to mobileGaid,
        "brand" to "Redmi",
        "model" to "23078RKD5C",
        "system_language" to "en",
        "net" to "NETWORK_WIFI",
        "region" to "US",
        "timezone" to "America/New_York",
        "sp_code" to "90101",
        "X-Play-Mode" to "2"
    ).toJson()

    private fun buildPlaybackMobileHeaders(
        method: String,
        url: String,
        body: String?,
        authToken: String?
    ): Map<String, String> = buildMobileHeaders(
        method = method,
        url = url,
        body = body,
        authToken = authToken
    ).toMutableMap().apply {
        // moviebox.js uses sp_code=90101 for /resource and does not spoof IP.
        this["X-Client-Info"] = playbackClientInfo()
        remove("X-Forwarded-For")
    }

    private fun orderedPlaybackMobileHosts(): List<String> = buildList {
        preferredPlaybackMobileHost
            ?.takeIf { it.isNotBlank() }
            ?.let { add(it) }
        playbackMobileHosts.forEach { host ->
            if (!contains(host)) add(host)
        }
    }

    private suspend fun bootstrapPlaybackAuth(): String? {
        playbackAuthToken?.takeIf { it.isNotBlank() }?.let { return it }

        for (host in orderedPlaybackMobileHosts()) {
            val url = "$host$mobileBootstrapPath?page=1&tabId=0&version="
            try {
                val response = app.get(
                    url,
                    headers = buildPlaybackMobileHeaders(
                        method = "GET",
                        url = url,
                        body = null,
                        authToken = null
                    ),
                    timeout = mobileRequestTimeoutSeconds
                )
                val token = tokenFromXUser(response.headers["x-user"])
                Log.i(
                    "MovieBox",
                    "MOVIEBOX_V20_PLAYBACK_AUTH host=$host http=${response.code} token=${!token.isNullOrBlank()}"
                )
                if (!token.isNullOrBlank()) {
                    playbackAuthToken = token
                    preferredPlaybackMobileHost = host
                    return token
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    "MovieBox",
                    "MOVIEBOX_V20_PLAYBACK_AUTH_FAIL host=$host type=${error::class.simpleName}"
                )
            }
        }
        return null
    }

    private fun tokenFromXUser(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            parseJson<XUserHeader>(raw).token?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun orderedMobileHosts(): List<String> = buildList {
        preferredMobileHost
            ?.takeIf { it.isNotBlank() }
            ?.let { add(it) }
        mobileHosts.forEach { host ->
            if (!contains(host)) add(host)
        }
    }

    private suspend fun bootstrapMobileAuth(): String? {
        mobileAuthToken?.takeIf { it.isNotBlank() }?.let { return it }

        for (host in orderedMobileHosts()) {
            val url = "$host$mobileBootstrapPath?page=1&tabId=0&version="
            try {
                val response = app.get(
                    url,
                    headers = buildMobileHeaders(
                        method = "GET",
                        url = url,
                        body = null,
                        authToken = null
                    ),
                    timeout = mobileRequestTimeoutSeconds
                )

                val token = tokenFromXUser(response.headers["x-user"])
                Log.i(
                    "MovieBox",
                    "MOVIEBOX_AUTH_BOOTSTRAP host=$host http=${response.code} token=${!token.isNullOrBlank()}"
                )

                if (!token.isNullOrBlank()) {
                    mobileAuthToken = token
                    preferredMobileHost = host
                    return token
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    "MovieBox",
                    "MOVIEBOX_AUTH_BOOTSTRAP_FAIL host=$host type=${error::class.simpleName}"
                )
            }
        }

        Log.w("MovieBox", "MOVIEBOX_AUTH_BOOTSTRAP_EMPTY")
        return null
    }

    private suspend fun raceH5SearchHosts(query: String): SearchRaceResult? {
        val requestJson = linkedMapOf<String, Any>(
            "keyword" to query.trim(),
            "page" to 1,
            "perPage" to 24,
            "subjectType" to 0
        ).toJson()

        for (host in orderedWebHosts()) {
            try {
                val response = app.post(
                    "$host/wefeed-h5-bff/web/subject/search",
                    headers = commonHeaders,
                    requestBody = requestJson.toRequestBody(
                        "application/json".toMediaTypeOrNull()
                    ),
                    timeout = searchHostTimeoutSeconds
                )

                val parsed = response.parsedSafe<Media>()
                val items = parsed?.data?.items
                    .orEmpty()
                    .filter { !it.subjectId.isNullOrBlank() && !it.title.isNullOrBlank() }

                Log.i(
                    "MovieBox",
                    "MOVIEBOX_H5_SEARCH host=$host http=${response.code} api=${parsed?.code} items=${items.size}"
                )

                if (response.code in 200..299 && parsed?.code == 0 && items.isNotEmpty()) {
                    preferredWebHost = host
                    return SearchRaceResult(host, items)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    "MovieBox",
                    "MOVIEBOX_H5_SEARCH_FAIL host=$host type=${error::class.simpleName}"
                )
            }
        }

        return null
    }

    private fun parseMobileSearchJson(raw: String): Pair<Int?, List<Items>>? {
        val root = try {
            AppUtils.tryParseJson<JsonNode>(raw)
        } catch (_: Throwable) {
            null
        } ?: return null

        val code = root.get("code")?.takeIf { !it.isNull }?.asInt()
        val dataNode = root.get("data") ?: root
        val itemsNode = dataNode.get("items")
            ?: dataNode.get("list")
            ?: root.get("items")
            ?: root.get("list")

        if (itemsNode == null || !itemsNode.isArray) {
            val rootKeys = root.fieldNames().asSequence().take(12).joinToString(",")
            val dataKeys = if (dataNode.isObject) {
                dataNode.fieldNames().asSequence().take(12).joinToString(",")
            } else ""
            Log.w(
                "MovieBox",
                "MOVIEBOX_SEARCH_SHAPE code=$code rootKeys=$rootKeys dataKeys=$dataKeys"
            )
            return code to emptyList()
        }

        val items = itemsNode.mapNotNull { node ->
            val idNode = node.get("subjectId") ?: node.get("subject_id") ?: return@mapNotNull null
            val titleNode = node.get("title") ?: node.get("name") ?: return@mapNotNull null
            val id = idNode.asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = titleNode.asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val typeNode = node.get("subjectType") ?: node.get("subject_type")
            val subjectType = when {
                typeNode == null || typeNode.isNull -> null
                typeNode.isNumber -> typeNode.asInt()
                else -> typeNode.asText().toIntOrNull()
            }
            val coverNode = node.get("cover")
            val coverUrl = when {
                coverNode == null || coverNode.isNull -> node.get("poster")?.asText()
                coverNode.isTextual -> coverNode.asText()
                else -> coverNode.get("url")?.asText()
            }?.takeIf { it.isNotBlank() }

            Items(
                subjectId = id,
                subjectType = subjectType,
                title = title,
                cover = coverUrl?.let { Items.Cover(url = it) }
            )
        }
        return code to items
    }

    private suspend fun raceSearchHosts(
        query: String,
        retryAuthOnce: Boolean = true
    ): SearchRaceResult? {
        val authToken = bootstrapMobileAuth() ?: return null
        val requestJson = linkedMapOf<String, Any>(
            "keyword" to query.trim(),
            "page" to 1,
            "perPage" to 20,
            "subjectType" to 0
        ).toJson()

        var sawAuthFailure = false

        for (host in orderedMobileHosts()) {
            val url = "$host$mobileSearchPath"
            try {
                val response = app.post(
                    url,
                    headers = buildMobileHeaders(
                        method = "POST",
                        url = url,
                        body = requestJson,
                        authToken = authToken
                    ),
                    requestBody = requestJson.toRequestBody(
                        "application/json; charset=utf-8".toMediaTypeOrNull()
                    ),
                    timeout = mobileRequestTimeoutSeconds
                )

                tokenFromXUser(response.headers["x-user"])?.let { freshToken ->
                    mobileAuthToken = freshToken
                }

                if (response.code == 401 || response.code == 403 || response.code == 440 || response.code == 530) {
                    sawAuthFailure = true
                    Log.w("MovieBox", "MOVIEBOX_SEARCH_AUTH_REJECT host=$host http=${response.code}")
                    continue
                }

                val raw = response.text
                val parsed = parseMobileSearchJson(raw)
                val apiCode = parsed?.first
                val items = parsed?.second.orEmpty()
                val parsedOk = parsed != null

                Log.i(
                    "MovieBox",
                    "MOVIEBOX_V10_SEARCH host=$host http=${response.code} api=$apiCode parsed=$parsedOk items=${items.size} bytes=${raw.length} query=${query.trim()}"
                )

                if (!parsedOk) {
                    val prefix = raw.take(220).replace(Regex("\\s+"), " ")
                    Log.w(
                        "MovieBox",
                        "MOVIEBOX_V10_SEARCH_PARSE_FAIL host=$host http=${response.code} bytes=${raw.length} body=$prefix"
                    )
                }

                if (response.code in 200..299 && (apiCode == 0 || apiCode == null) && items.isNotEmpty()) {
                    preferredMobileHost = host
                    return SearchRaceResult(host, items)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    "MovieBox",
                    "MOVIEBOX_V10_SEARCH_FAIL host=$host type=${error::class.simpleName}"
                )
            }
        }

        if (sawAuthFailure && retryAuthOnce) {
            Log.w("MovieBox", "MOVIEBOX_V10_SEARCH_REAUTH query=${query.trim()}")
            mobileAuthToken = null
            preferredMobileHost = null
            return raceSearchHosts(query, retryAuthOnce = false)
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

        // v9 deliberately uses only the signed Android search endpoint.
        // The current upstream architecture documents H5 for homepage data,
        // not search, so avoiding H5 here removes dead-host delay and noise.
        val mobileResult = raceSearchHosts(query)
        if (mobileResult == null) {
            Log.w("MovieBox", "MOVIEBOX_V10_SEARCH_EMPTY query=${query.trim()}")
            return emptyList()
        }

        preferredMobileHost = mobileResult.host
        return mobileResult.items
            .filter { !it.subjectId.isNullOrBlank() && !it.title.isNullOrBlank() }
            .map { it.toSearchResponse(this) }
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
        val detailPath = media.detailPath.orEmpty().trim().trim('/')
        val slug = detailPath.split('/').lastOrNull().orEmpty()

        // Current H5 client sends the play request with /movies/{slug} as
        // Referer. subjectId belongs in the API query, not in the Referer.
        return if (slug.isNotBlank()) "$host/movies/$slug" else "$host/"
    }

    private suspend fun warmH5Session(host: String) {
        try {
            val response = app.get(
                "$host/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox",
                headers = commonHeaders,
                referer = "$host/",
                timeout = 3L
            )
            Log.i("MovieBox", "MOVIEBOX_H5_SESSION host=$host http=${response.code}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Cookie bootstrap is best effort. Some mirrors set the session
            // cookie directly on the play request.
            Log.w("MovieBox", "MOVIEBOX_H5_SESSION_FAIL host=$host type=${error::class.simpleName}")
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
                warmH5Session(host)

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


    /*
     * Playback-only guard/fallback.
     *
     * Search, detail discovery, H5 stream discovery and captions deliberately
     * stay on the original Cloudstream implementation.  The mobile /resource
     * endpoint is used only when validating/replacing a bad playback URL.
     */
    private data class PlaybackResourceCandidate(
        val resourceId: String,
        val resourceLink: String,
        val resolution: Int,
        val requireMemberType: Int,
        val linkType: Int,
        val duration: Long,
        val size: Long,
        val title: String,
        val season: Int,
        val episode: Int,
    )

    private data class PlaybackProbe(
        val httpCode: Int,
        val totalBytes: Long,
    )

    private fun playbackNodeText(node: JsonNode?, name: String): String? {
        val value = node?.get(name) ?: return null
        if (value.isNull) return null
        return value.asText().trim().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun playbackNodeInt(node: JsonNode?, name: String): Int {
        val value = node?.get(name) ?: return 0
        return if (value.isNumber) value.asInt()
        else value.asText().trim().removeSuffix("p").toIntOrNull() ?: 0
    }

    private fun playbackNodeLong(node: JsonNode?, name: String): Long {
        val value = node?.get(name) ?: return 0L
        return if (value.isNumber) value.asLong()
        else value.asText().trim().toLongOrNull() ?: 0L
    }

    private fun isPlaybackNoticeTitle(title: String): Boolean = Regex(
        "install(?:ation)?|official\\s*notice|update\\s*(?:the\\s*)?app|download\\s*(?:the\\s*)?(?:latest\\s*)?(?:version|app)|uninstall|discontinued\\s*soon|support\\s*ending",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(title)

    private fun playbackTotalBytes(headers: okhttp3.Headers): Long {
        val contentRange = headers["content-range"].orEmpty()
        Regex("/(\\d+)\\s*$").find(contentRange)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return it }
        return headers["content-length"]?.toLongOrNull() ?: 0L
    }

    private suspend fun probePlaybackUrl(url: String): PlaybackProbe {
        return try {
            /*
             * Do not forward H5 Referer or the Android SDK UA to the player/CDN.
             * The original provider intentionally avoids those headers because
             * some signed hakunaymatata links reject them. Range is only for
             * validation and is never attached to the emitted ExtractorLink.
             */
            val response = app.get(
                url,
                headers = mapOf("Range" to "bytes=0-0"),
                timeout = playHostTimeoutSeconds
            )
            PlaybackProbe(
                httpCode = response.code,
                totalBytes = playbackTotalBytes(response.headers)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            PlaybackProbe(httpCode = 0, totalBytes = 0L)
        }
    }

    private fun minimumPlaybackBytes(mediaType: Int): Long =
        if (mediaType == 1) 20L * 1024L * 1024L else 8L * 1024L * 1024L

    private suspend fun shouldRejectH5PlaybackUrl(
        url: String,
        mediaType: Int
    ): Boolean {
        val probe = probePlaybackUrl(url)

        /*
         * H5 URLs are already signed playback URLs returned by MovieBox.
         * A CDN may reject our validation request (403/429), ignore Range, or
         * hide Content-Range while ExoPlayer can still play the same URL.
         * Therefore a failed/ambiguous probe must NEVER suppress an H5 source.
         * Reject only when the probe succeeds and conclusively proves that the
         * direct media file is a tiny service/update clip.
         */
        if (probe.httpCode != 200 && probe.httpCode != 206) return false

        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd")) return false

        val totalBytes = probe.totalBytes
        if (totalBytes <= 0L) return false

        return totalBytes < minimumPlaybackBytes(mediaType)
    }

    private suspend fun isVerifiedResourcePlaybackUrl(
        url: String,
        mediaType: Int,
        expectedBytes: Long = 0L
    ): Boolean {
        val probe = probePlaybackUrl(url)
        if (probe.httpCode != 200 && probe.httpCode != 206) return false

        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd")) return true

        val minBytes = minimumPlaybackBytes(mediaType)
        val totalBytes = probe.totalBytes

        // For /resource fallback we keep the JS-style stricter validation:
        // actual CDN size wins; when unavailable, API metadata must still say
        // that this is a full-size media file.
        if (totalBytes <= 0L) return expectedBytes >= minBytes
        return totalBytes >= minBytes
    }

    private fun parsePlaybackResourcePage(raw: String): Pair<List<PlaybackResourceCandidate>, Boolean>? {
        val root = try {
            AppUtils.tryParseJson<JsonNode>(raw)
        } catch (_: Throwable) {
            null
        } ?: return null

        val code = root.get("code")?.takeIf { !it.isNull }?.asInt()
        if (code != null && code != 0) return emptyList<PlaybackResourceCandidate>() to false

        val data = root.get("data") ?: root
        val list = data.get("list") ?: data.get("items")
        val candidates = if (list != null && list.isArray) {
            list.mapNotNull { node ->
                val link = playbackNodeText(node, "resourceLink")
                    ?: playbackNodeText(node, "url")
                    ?: return@mapNotNull null
                if (!link.startsWith("http://") && !link.startsWith("https://")) {
                    return@mapNotNull null
                }

                PlaybackResourceCandidate(
                    resourceId = playbackNodeText(node, "resourceId").orEmpty(),
                    resourceLink = link,
                    resolution = playbackNodeInt(node, "resolution"),
                    requireMemberType = playbackNodeInt(node, "requireMemberType"),
                    linkType = playbackNodeInt(node, "linkType"),
                    duration = playbackNodeLong(node, "duration"),
                    size = playbackNodeLong(node, "size"),
                    title = playbackNodeText(node, "title").orEmpty(),
                    season = playbackNodeInt(node, "se"),
                    episode = playbackNodeInt(node, "ep"),
                )
            }
        } else emptyList()

        val hasMore = data.get("pager")?.get("hasMore")?.asBoolean(false) == true
        return candidates to hasMore
    }

    private suspend fun loadPlaybackResourceCandidates(
        subjectId: String,
        mediaType: Int,
        season: Int,
        episode: Int,
        requestedResolution: Int,
        retryAuthOnce: Boolean = true
    ): List<PlaybackResourceCandidate> {
        val token = bootstrapPlaybackAuth() ?: return emptyList()
        var sawAuthFailure = false

        for (host in orderedPlaybackMobileHosts()) {
            try {
                val all = mutableListOf<PlaybackResourceCandidate>()
                var page = 1
                var validResponse = false

                while (page <= 20) {
                    val url = "$host$mobileResourcePath?ep=0&page=$page&perPage=10&resolution=$requestedResolution&se=0&subjectId=$subjectId"
                    val response = app.get(
                        url,
                        headers = buildPlaybackMobileHeaders(
                            method = "GET",
                            url = url,
                            body = null,
                            authToken = token
                        ),
                        timeout = mobileRequestTimeoutSeconds
                    )

                    tokenFromXUser(response.headers["x-user"])?.let { playbackAuthToken = it }

                    if (response.code == 401 || response.code == 403 ||
                        response.code == 440 || response.code == 530
                    ) {
                        sawAuthFailure = true
                        break
                    }
                    if (response.code !in 200..299) break

                    val parsed = parsePlaybackResourcePage(response.text) ?: break
                    validResponse = true
                    all += parsed.first
                    if (!parsed.second) break
                    page++
                }

                if (validResponse) {
                    preferredPlaybackMobileHost = host
                    Log.i(
                        "MovieBox",
                        "MOVIEBOX_V20_RESOURCE host=$host requested=${requestedResolution}p items=${all.size} se=$season ep=$episode"
                    )
                    return if (mediaType == 2) {
                        all.filter { it.season == season && it.episode == episode }
                    } else all
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Playback fallback only; keep trying the remaining API hosts.
            }
        }

        if (sawAuthFailure && retryAuthOnce) {
            playbackAuthToken = null
            preferredPlaybackMobileHost = null
            return loadPlaybackResourceCandidates(
                subjectId,
                mediaType,
                season,
                episode,
                requestedResolution,
                retryAuthOnce = false
            )
        }

        return emptyList()
    }

    private fun playbackCandidateScore(item: PlaybackResourceCandidate): Long {
        var score = 0L
        if (isPlaybackNoticeTitle(item.title)) score -= 1_000_000_000_000L
        if (item.requireMemberType == 0) score += 1_000_000_000_000L
        else score -= item.requireMemberType.toLong() * 100_000_000_000L
        if (item.linkType == 1) score += 50_000_000_000L
        score += item.resolution.toLong() * 100_000_000L
        score += item.duration * 10_000L
        score += item.size
        return score
    }

    private suspend fun findVerifiedPlaybackResource(
        subjectId: String,
        mediaType: Int,
        season: Int,
        episode: Int,
        requestedResolution: Int,
        emittedUrls: Set<String>
    ): PlaybackResourceCandidate? {
        val candidates = loadPlaybackResourceCandidates(
            subjectId,
            mediaType,
            season,
            episode,
            requestedResolution
        )
            .filter { it.resolution == 0 || it.resolution >= 720 }
            .filterNot { isPlaybackNoticeTitle(it.title) }
            .distinctBy {
                if (it.resourceId.isNotBlank()) "rid:${it.resourceId}" else "url:${it.resourceLink}"
            }
            .sortedByDescending(::playbackCandidateScore)

        for (candidate in candidates) {
            if (candidate.resourceLink in emittedUrls) continue
            if (isVerifiedResourcePlaybackUrl(candidate.resourceLink, mediaType, candidate.size)) {
                return candidate
            }
        }
        return null
    }

    private fun explicitPlaybackQuality(label: String?): Int? {
        val raw = label.orEmpty().trim()
        if (raw.isBlank()) return null

        Regex("(?<!\\d)(2160|1440|1080|720|480|360)(?:p)?(?!\\d)", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        return when (raw.uppercase()) {
            "4K", "UHD" -> 2160
            "2K", "QHD" -> 1440
            "FHD", "FULL HD", "FULLHD" -> 1080
            "HD" -> 720
            "SD" -> 480
            else -> null
        }
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
        val mediaType = if (season > 0 || episode > 0) 2 else 1

        /*
         * Original Cloudstream H5 discovery remains the source of stream
         * metadata and caption ids. The JS implementation is consulted only
         * for playback replacement/fallback.
         */
        val resolvedStreams = loadPlayHosts(
            media = media,
            subjectId = subjectId,
            season = season,
            episode = episode
        )

        Log.i(
            "MovieBox",
            "MOVIEBOX_V20_LOADLINKS subject=$subjectId se=$season ep=$episode h5Streams=${resolvedStreams.size} rawQualities=${resolvedStreams.joinToString(",") { it.stream.resolutions.orEmpty().ifBlank { "?" } }}"
        )

        val emittedUrls = linkedSetOf<String>()
        val emittedQualities = linkedSetOf<Int>()
        val hasKnown720PlusH5 = resolvedStreams.any {
            (explicitPlaybackQuality(it.stream.resolutions) ?: -1) >= 720
        }

        fun emitLink(url: String, quality: Int, label: String) {
            if (url.isBlank() || !emittedUrls.add(url)) return
            if (quality >= 720) emittedQualities += quality
            callback.invoke(
                newExtractorLink(
                    this.name,
                    label,
                    url,
                    INFER_TYPE
                ) {
                    this.quality = quality
                }
            )
        }

        /*
         * Keep the original H5 links unless they are conclusively a tiny
         * service/update clip. A quality is filtered only when MovieBox
         * explicitly tells us it is below 720p. Unknown labels are preserved
         * instead of being converted to 0 and accidentally dropping every
         * source (the v19 no-link regression).
         */
        for (resolved in resolvedStreams.sortedByDescending {
            explicitPlaybackQuality(it.stream.resolutions)
                ?: getQualityFromName(it.stream.resolutions)
        }) {
            val source = resolved.stream
            val streamUrl = source.url ?: continue
            val explicitQuality = explicitPlaybackQuality(source.resolutions)
            if (explicitQuality == null && hasKnown720PlusH5) {
                Log.i(
                    "MovieBox",
                    "MOVIEBOX_V20_SKIP_UNKNOWN raw=${source.resolutions} reason=known-720plus-exists"
                )
                continue
            }
            if (explicitQuality != null && explicitQuality < 720) {
                Log.i(
                    "MovieBox",
                    "MOVIEBOX_V20_SKIP_LOW raw=${source.resolutions} urlHost=${runCatching { URI(streamUrl).host }.getOrNull()}"
                )
                continue
            }

            val parsedQuality = explicitQuality ?: getQualityFromName(source.resolutions)
            val quality = parsedQuality.takeIf { it > 0 } ?: Qualities.Unknown.value

            if (shouldRejectH5PlaybackUrl(streamUrl, mediaType)) {
                Log.w(
                    "MovieBox",
                    "MOVIEBOX_V20_REJECT_NOTICE flow=h5 raw=${source.resolutions} quality=$quality cdn=${runCatching { URI(streamUrl).host }.getOrNull()}"
                )
                continue
            }

            Log.i(
                "MovieBox",
                "MOVIEBOX_V20_EMIT flow=h5 raw=${source.resolutions} quality=$quality apiHost=${resolved.host} cdn=${runCatching { URI(streamUrl).host }.getOrNull()}"
            )
            emitLink(
                streamUrl,
                quality,
                buildString {
                    append(this@MovieboxProvider.name)
                    source.resolutions
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append(" ").append(it) }
                }
            )
        }

        /*
         * Playback-only JS fallback. Search/detail remain untouched. Resource
         * calls use the working JS client region (sp_code 90101), no spoofed
         * X-Forwarded-For, and their own token/host preference. Add only a
         * missing 1080p/720p quality; do not replace working H5 metadata.
         */
        for (quality in listOf(1080, 720)) {
            if (quality in emittedQualities) continue
            val replacement = findVerifiedPlaybackResource(
                subjectId = subjectId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                requestedResolution = quality,
                emittedUrls = emittedUrls
            ) ?: continue

            val actualQuality = replacement.resolution.takeIf { it >= 720 } ?: quality
            Log.i(
                "MovieBox",
                "MOVIEBOX_V20_EMIT flow=resource-fallback requested=$quality actual=$actualQuality resourceId=${replacement.resourceId} cdn=${runCatching { URI(replacement.resourceLink).host }.getOrNull()}"
            )
            emitLink(
                replacement.resourceLink,
                actualQuality,
                "${this@MovieboxProvider.name} ${actualQuality}p Direct"
            )
        }

        /* Captions are still 100% the original H5 stream-id/format flow. */
        loadCaptionsAcrossHosts(
            subjectId = subjectId,
            seeds = resolvedStreams
        ).forEach { subtitle ->
            val subtitleUrl = subtitle.url ?: return@forEach
            val language = allowedSubtitleLanguage(subtitle) ?: return@forEach
            subtitleCallback.invoke(newSubtitleFile(language, subtitleUrl))
        }

        Log.i(
            "MovieBox",
            "MOVIEBOX_V20_DONE links=${emittedUrls.size} qualities=${emittedQualities.sortedDescending().joinToString(",")}"
        )
        return emittedUrls.isNotEmpty()
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null,
        val apiHost: String? = null
    )

    data class XUserHeader(
        @JsonProperty("token") val token: String? = null,
    )

    data class Media(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
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
        @JsonProperty("duration") val duration: Any? = null,
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
