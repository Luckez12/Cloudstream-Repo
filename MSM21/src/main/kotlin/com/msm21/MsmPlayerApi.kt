package com.msm21

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Player endpoints and response formats observed on MSM21's actual embeds.
 * PlayerX encrypts API JSON; its native HLS proxy includes tokens in child URLs.
 * Do not turn telemetry/config strings into movie sources.
 */
internal object MsmPlayerApi {
    private val byseFrames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun nestedFrame(url: String): String? = byseFrames[url]

    private val playerXHosts = setOf("playerx.player4me.online", "playerx.rpmplay.online",
        "playerx.seekplays.online", "playerx.p2pstream.online", "playerx.upns.live")

    fun supports(url: String): Boolean = runCatching {
        URI(url).host?.lowercase().let { it in playerXHosts || it == "bysesukior.com" }
    }.getOrDefault(false)

    suspend fun extract(url: String, pageUrl: String): List<ExtractorLink> {
        if (!supports(url)) return emptyList()
        return try {
            val uri = URI(url)
            val links = when (uri.host.lowercase()) {
                in playerXHosts -> playerX(uri, pageUrl)
                else -> byse(uri, pageUrl)
            }
            Log.i("MSM21", "MSM21_V15_API host=${uri.host} candidates=${links.size}")
            links
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Never log response bodies, API credentials, playback keys or signed URLs.
            Log.w("MSM21", "MSM21_V15_API_FAILED host=${runCatching { URI(url).host }.getOrNull()} " +
                "error=${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun origin(uri: URI): String = "${uri.scheme}://${uri.rawAuthority}"
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun headers(base: String) = mapOf("User-Agent" to USER_AGENT,
        "Referer" to "$base/", "Origin" to base)

    private suspend fun playerX(uri: URI, pageUrl: String): List<ExtractorLink> {
        val id = uri.fragment.orEmpty().substringBefore('&')
        if (!id.matches(Regex("[A-Za-z0-9_-]{2,100}")) || uri.scheme != "https") {
            Log.w("MSM21", "MSM21_V16_PLAYERX host=${uri.host} reason=invalid_video_id")
            return emptyList()
        }
        val base = origin(uri)
        val parent = URI(pageUrl).host.orEmpty().removePrefix("www.")
        val response = app.get("$base/api/v1/video?id=${enc(id)}&w=1080&h=1080&r=${enc(parent)}",
            headers = headers(base), timeout = 3L)
        if (response.code !in 200..299) {
            Log.w("MSM21", "MSM21_V16_PLAYERX host=${uri.host} status=${response.code} reason=${if (response.code == 404) "not_found" else "api_http_error"}")
            return emptyList()
        }
        val hex = response.text.trim()
        if (hex.length !in 32..2_000_000 || hex.length % 32 != 0 ||
            !hex.matches(Regex("[0-9a-fA-F]+"))) {
            Log.w("MSM21", "MSM21_V15_API_RESPONSE host=${uri.host} reason=non_encrypted_response")
            return emptyList()
        }
        // Derived from the published PlayerX Z()/J() functions for https + #videoId.
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.UTF_8)))
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val data = JSONObject(String(cipher.doFinal(bytes), Charsets.UTF_8).removePrefix("\uFEFF"))
        val config = runCatching { JSONObject(data.optString("streamingConfig", "{}")) }
            .getOrElse { JSONObject() }.optJSONObject("adjust")
        val pk = data.optJSONObject("pk")
        val options = mutableListOf<Pair<String, String>>()
        // This is the API's native HLS proxy, rather than a guessed master URL.
        val cfDisabled = config?.optJSONObject("Cloudflare")?.optBoolean("disabled", false) == true
        if (!cfDisabled) options.add("Native" to data.optString("cfNative"))
        listOf("In-House" to "source", "Tiktok" to "hlsVideoTiktok", "Google" to "hlsVideoGoogle")
            .forEach { (kind, field) ->
                val adjust = config?.optJSONObject(kind)
                if (adjust?.optBoolean("disabled", false) == true) return@forEach
                var raw = data.optString(field)
                if (raw.isBlank()) return@forEach
                val domain = adjust?.optString("domain").orEmpty()
                if (domain.isNotBlank()) raw = raw.replace("/hls/", "/hlsmod/$domain/")
                val params = adjust?.optJSONObject("params")
                params?.keys()?.forEach { key -> raw = query(raw, key, params?.optString(key).orEmpty()) }
                val resolved = uri.resolve(raw)
                if (resolved.path.contains("/v4/")) {
                    listOf("k", "kx").forEach { key ->
                        pk?.optString(key)?.takeIf { it.isNotBlank() }?.let { raw = query(raw, key, it) }
                    }
                }
                options.add(kind to raw)
            }
        Log.i("MSM21", "MSM21_V16_PLAYERX host=${uri.host} " +
            "source_fields=${listOf("cfNative", "source", "hlsVideoTiktok", "hlsVideoGoogle").count { data.optString(it).isNotBlank() }} " +
            "enabled_candidates=${options.count { it.second.isNotBlank() }} " +
            "reason=${if (options.none { it.second.isNotBlank() }) "no_enabled_source" else "sources_available"}")
        return options.filter { it.second.isNotBlank() }.distinctBy { it.second }.mapNotNull { (kind, raw) ->
            val media = uri.resolve(raw).toString()
            if (MsmMediaPolicy.isRejected(media)) return@mapNotNull null
            newExtractorLink(source = "PlayerX", name = "PlayerX $kind", url = media,
                type = ExtractorLinkType.M3U8) { referer = "$base/"; this.headers = headers(base) }
        }
    }

    private fun query(raw: String, key: String, value: String): String {
        val uri = URI(raw)
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() &&
            it.substringBefore('=') != enc(key) }.toMutableList()
        pairs.add("${enc(key)}=${enc(value)}")
        return raw.substringBefore('#').substringBefore('?') + "?" + pairs.joinToString("&")
    }

    private suspend fun byse(uri: URI, pageUrl: String): List<ExtractorLink> {
        byseFrames.remove(uri.toString())
        val base = origin(uri)
        val code = uri.path.trimEnd('/').substringAfterLast('/')
        if (!code.matches(Regex("[A-Za-z0-9_-]{2,100}"))) return emptyList()
        val detailsResponse = app.get("$base/api/videos/$code/embed/details",
            referer = pageUrl, headers = headers(base), timeout = 3L)
        if (detailsResponse.code !in 200..299) {
            Log.w("MSM21", "MSM21_V16_BYSE stage=details status=${detailsResponse.code} reason=${if (detailsResponse.code == 404) "not_found" else "api_http_error"}")
            return emptyList()
        }
        val details = JSONObject(detailsResponse.text)
        val frame = URI(details.getString("embed_frame_url"))
        if (MsmMediaPolicy.isRejected(frame.toString())) return emptyList()
        val frameCode = frame.path.trimEnd('/').substringAfterLast('/')
        if (!frameCode.matches(Regex("[A-Za-z0-9_-]{2,100}"))) return emptyList()
        if (frame.scheme != "https" || frame.host.isNullOrBlank()) return emptyList()
        byseFrames[uri.toString()] = frame.toString()
        val frameBase = origin(frame)
        val embedHeaders = headers(frameBase) + mapOf(
            "Referer" to frame.toString(), "X-Embed-Parent" to uri.toString(),
            "X-Embed-Origin" to URI(pageUrl).host.orEmpty(), "X-Embed-Referer" to pageUrl)
        val settingsResponse = app.get("$frameBase/api/videos/$frameCode/embed/settings",
            headers = embedHeaders, timeout = 3L)
        if (settingsResponse.code !in 200..299) {
            Log.w("MSM21", "MSM21_V16_BYSE stage=settings status=${settingsResponse.code} reason=${if (settingsResponse.code == 404) "not_found" else "api_http_error"}")
            return emptyList()
        }
        if (settingsResponse.code in 200..299 &&
            JSONObject(settingsResponse.text).optBoolean("captcha_required", false)) {
            Log.w("MSM21", "MSM21_V15_BYSE_BLOCKED reason=human_verification_required")
            // Keep the actual frame for the browser fallback. Do not fabricate an
            // attestation or submit a CAPTCHA response on the user's behalf.
            return emptyList()
        }
        val response = app.get("$frameBase/api/videos/$frameCode/embed/playback",
            headers = embedHeaders,
            timeout = 3L)
        if (response.code !in 200..299) {
            Log.w("MSM21", "MSM21_V15_BYSE_PLAYBACK status=${response.code} reason=${if (response.code == 405) "method_not_allowed" else if (response.code == 404) "not_found" else "api_unavailable"}")
            return emptyList()
        }
        val data = JSONObject(response.text).getJSONObject("playback")
        val parts = data.getJSONArray("key_parts")
        val version = data.optString("version").toIntOrNull()
        val indices = if (version != null && version in 1..20 &&
            version <= parts.length() && 31 - version <= parts.length())
            listOf(version - 1, 30 - version) else (0 until parts.length()).toList()
        val key = indices.fold(ByteArray(0)) { bytes, index -> bytes + b64(parts.getString(index)) }
        if (key.size !in listOf(16, 24, 32)) return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64(data.getString("iv"))))
        val decoded = JSONObject(String(cipher.doFinal(b64(data.getString("payload"))), Charsets.UTF_8)
            .removePrefix("\uFEFF"))
        val sources = decoded.optJSONArray("sources") ?: return emptyList()
        return (0 until sources.length()).mapNotNull { index ->
            val source = sources.getJSONObject(index)
            val raw = source.optString("url")
            if (raw.isBlank()) return@mapNotNull null
            val media = frame.resolve(raw).toString()
            if (MsmMediaPolicy.isRejected(media)) return@mapNotNull null
            val mime = source.optString("mime_type")
            val type = if (mime.contains("mpegurl", true) || URI(media).path.endsWith(".m3u8", true))
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            newExtractorLink(source = "Byse", name = source.optString("label", "Byse"), url = media,
                type = type) { referer = "$frameBase/"; this.headers = headers(frameBase)
                quality = source.optInt("height", 400) }
        }
    }

    private fun b64(raw: String): ByteArray = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP)

}
