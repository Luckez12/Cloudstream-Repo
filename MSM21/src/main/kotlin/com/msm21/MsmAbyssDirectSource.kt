package com.msm21

import java.net.URI

/** A browser-advertised object is only usable if strict independent range probes pass.
 * This does not implement or pretend to support Abyss's custom segmented transport.
 */
internal object MsmAbyssDirectSource {
    private const val PREFIX = "msm21:abyss-direct:"
    data class Candidate(val size: Long, val label: String)

    fun parse(raw: String): Candidate? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host != "storage.googleapis.com" ||
            !uri.path.startsWith("/mediastorage/") || !uri.path.endsWith(".mp4") ||
            uri.userInfo != null || uri.port != -1) return@runCatching null
        val fields = uri.fragment.orEmpty().substringBefore('?').split('/')
        if (fields.size != 7 || fields[0] != "mp4" || fields[1] != "r2" ||
            fields[2] != "1" || !fields[3].matches(Regex("[0-9]+")) ||
            !fields[5].matches(Regex("[0-9]{3,4}p")) || fields[6] != "h264") return@runCatching null
        val size = fields[4].toLongOrNull()?.takeIf { it > 1024 } ?: return@runCatching null
        Candidate(size, fields[5])
    }.getOrNull()

    fun marker(size: Long): String = "$PREFIX$size"
    fun expectedSize(marker: String?): Long? = marker?.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.toLongOrNull()?.takeIf { it > 1024 }

    fun matchesRange(header: String?, start: Long, end: Long, total: Long): Boolean =
        header?.trim()?.equals("bytes $start-$end/$total", ignoreCase = true) == true
}
