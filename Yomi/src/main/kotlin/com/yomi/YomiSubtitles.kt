package com.yomi

import android.util.Log
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/** Validate each distinct request, rather than letting a failed URL hide later headers. */
internal class YomiSubtitles(private val callback: (SubtitleFile) -> Unit) {
    private val lock = Any()
    private val attempted = mutableSetOf<Pair<String, Map<String, String>>>()
    private val delivered = mutableSetOf<String>()

    suspend fun accept(sub: SubtitleFile) {
        val uri = runCatching { URI(sub.url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https")) return
        val headers = sub.headers.orEmpty().entries.associate { it.key.lowercase() to it.value }
        synchronized(lock) {
            if (sub.url in delivered || !attempted.add(sub.url to headers)) return
        }
        val host = uri.host.orEmpty()
        try {
            val response = withTimeoutOrNull(4_000L) { app.get(sub.url, headers = headers) }
            if (response == null) {
                Log.i("Yomi", "YOMI_SUBTITLE_REJECT language=${sub.lang} host=$host reason=timeout")
                return
            }
            val body = response.text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            val valid = response.code in 200..299 && body.length <= 2_000_000 &&
                !body.startsWith("<") && (
                    body.startsWith("WEBVTT") && (body.length == 6 || body[6].isWhitespace()) ||
                    Regex("(?m)^\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s+-->\\s+\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(body) ||
                    body.startsWith("[Script Info]") && body.contains("[Events]") && body.contains("Dialogue:")
                )
            if (!valid) {
                Log.i("Yomi", "YOMI_SUBTITLE_REJECT language=${sub.lang} host=$host status=${response.code} reason=${if (response.code !in 200..299) "http" else "invalid_caption"} header_names=${headers.keys.sorted().joinToString("+")}")
                return
            }
            synchronized(lock) {
                if (delivered.add(sub.url)) {
                    callback(SubtitleFile(sub.lang, sub.url).apply { this.headers = headers })
                    Log.i("Yomi", "YOMI_SUBTITLE_READY language=${sub.lang} host=$host status=${response.code} header_names=${headers.keys.sorted().joinToString("+")}")
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.i("Yomi", "YOMI_SUBTITLE_REJECT language=${sub.lang} host=$host reason=${e.javaClass.simpleName}")
        }
    }
}
