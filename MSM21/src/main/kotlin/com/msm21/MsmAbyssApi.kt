package com.msm21

import android.util.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Same datas -> decrypt service path as the user's working MSM JS extractor.
 * Returned URLs are candidates, never proof that a video is playable.
 */
internal object MsmAbyssApi {
    private val hosts = setOf("abyss.to", "abyssplayer.com", "playhydrax.com", "abyss.msmbot.club")
    private val datasPatterns = listOf(
        Regex("""const\s+datas\s*=\s*"([^"]+)""", RegexOption.IGNORE_CASE),
        Regex("""datas\s*[:=]\s*'([^']+)'""", RegexOption.IGNORE_CASE)
    )
    private val mediaPath = Regex("""\.(?:m3u8|mp4|m4v|mkv|webm)(?:[?#]|$)|/sora/|[?&](?:mime|type)=video""", RegexOption.IGNORE_CASE)
    private val embeddedUrl = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    fun supports(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme in listOf("https", "http") && uri.userInfo == null &&
            hosts.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun datas(html: String): String? = datasPatterns.firstNotNullOfOrNull {
        it.find(html)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
    }

    fun mediaUrls(result: Any?): List<String> {
        val found = linkedSetOf<String>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 7 || value == null || value == JSONObject.NULL) return
            when (value) {
                is String -> {
                    val clean = value.replace("&amp;", "&").replace("&#038;", "&")
                        .replace("\\/", "/").trim()
                    fun add(raw: String) {
                        if (!MsmMediaPolicy.isRejected(raw) && mediaPath.containsMatchIn(raw)) found.add(raw)
                    }
                    if (clean.startsWith("https://", true) || clean.startsWith("http://", true)) add(clean)
                    embeddedUrl.findAll(clean).forEach { add(it.value) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it), depth + 1) }
                is JSONObject -> value.keys().forEach { visit(value.opt(it), depth + 1) }
            }
        }
        visit(result, 0)
        return found.toList()
    }

    suspend fun extract(url: String, label: String = "Abyss"): List<ExtractorLink> {
        if (!supports(url)) return emptyList()
        val host = URI(url).host
        Log.i("MSM21", "MSM21_V23_ABYSS_START host=$host")
        try {
            val uri = URI(url)
            val origin = "${uri.scheme}://${uri.rawAuthority}"
            val page = app.get(url, headers = mapOf("Accept" to "text/html,*/*",
                "Origin" to origin, "Referer" to "$origin/", "User-Agent" to USER_AGENT), timeout = 3L)
            if (page.code !in 200..299) {
                Log.i("MSM21", "MSM21_V23_ABYSS host=$host stage=page status=${page.code}")
                return emptyList()
            }
            val payload = datas(page.text) ?: run {
                Log.i("MSM21", "MSM21_V23_ABYSS host=$host reason=no_datas")
                return emptyList()
            }
            val response = app.post("https://enc-dec.app/api/dec-abyss",
                headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json",
                    "User-Agent" to USER_AGENT),
                json = mapOf("text" to payload), timeout = 3L)
            if (response.code !in 200..299) {
                Log.i("MSM21", "MSM21_V23_ABYSS host=$host stage=decrypt status=${response.code}")
                return emptyList()
            }
            val data = JSONObject(response.text)
            if (data.optInt("status") != 200) return emptyList()
            val urls = mediaUrls(data.opt("result"))
            Log.i("MSM21", "MSM21_V23_ABYSS host=$host candidates=${urls.size}")
            return urls.map { media ->
                val path = runCatching { URI(media).path }.getOrNull().orEmpty()
                val type = if (path.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                newExtractorLink(source = label, name = label, url = media, type = type) {
                    referer = url
                    headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*", "Referer" to url)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Do not expose encrypted payloads, signed URLs, or API response bodies.
            Log.w("MSM21", "MSM21_V23_ABYSS_FAILED host=$host error=${error.javaClass.simpleName}")
            return emptyList()
        }
    }
}
