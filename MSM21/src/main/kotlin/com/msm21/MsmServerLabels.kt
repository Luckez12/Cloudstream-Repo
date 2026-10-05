package com.msm21

import com.lagradost.cloudstream3.utils.Qualities

/** One formatter for every server; no server-name aliases or eligibility table. */
internal object MsmServerLabels {
    private val language = Regex("(?:Malay\\s*(?:Sub|Dub)|[A-Z][a-z]+\\s*(?:Sub|Dub)|(?<=\\s)[\\p{L}]+\\s+(?:Sub|Dub))")
    private val foldedLanguage = Regex("Malay\\s*(?:Sub|Dub)", RegexOption.IGNORE_CASE)
    private val resolution = Regex("(?<![\\p{L}0-9])([1-9][0-9]{1,3})p\\b", RegexOption.IGNORE_CASE)
    private val suffix = Regex("(?:\\s*[•|]\\s*|\\s+)(?:Auto|Unknown|[1-9][0-9]{1,3}p)$", RegexOption.IGNORE_CASE)
    private val descriptor = Regex("^(?:auto|unknown|video|hls|native|in-house|\\d+p?)$", RegexOption.IGNORE_CASE)
    private data class Parts(val server: String, val audio: String?)

    private fun withoutSuffix(value: String): String = value.trim().replace(suffix, "").trim()

    private fun parts(label: String): Parts {
        val clean = withoutSuffix(label.trim().replace('_', ' ').replace(Regex("\\s+"), " "))
        val match = foldedLanguage.find(clean) ?: language.find(clean)
        if (match == null) return Parts(clean, null)
        val server = clean.substring(0, match.range.first).trim().trimEnd('•', '-', '|').trim()
        // Website option ordinals follow the language, not the server identity.
        val tail = clean.substring(match.range.last + 1).trim()
        if (tail.isNotEmpty() && !tail.matches(Regex("\\d+"))) return Parts(clean, null)
        val audio = match.value.replace(Regex("\\s+"), " ").trim()
        val tag = if (audio.endsWith("Dub", true)) "Dub" else "Sub"
        val locale = audio.dropLast(3).trim().replaceFirstChar { it.uppercase() }
        return Parts(server, if (locale.equals("Malay", true) && tag == "Sub") "MalaySub" else "$locale $tag")
    }

    private fun extractorServer(value: String, label: String): String? {
        if (value.isBlank() || value.trim().equals(label.trim(), true)) return null
        return parts(value).server.takeUnless { it.isBlank() || descriptor.matches(it) }
    }

    fun display(label: String, extractorSource: String = "", extractorName: String = ""): String {
        val part = parts(label)
        val server = extractorServer(extractorSource, label)
            ?: extractorServer(extractorName, label)
            ?: part.server.takeUnless { it.isBlank() || descriptor.matches(it) }
            ?: "Unknown"
        val audio = part.audio ?: parts(extractorSource).audio ?: parts(extractorName).audio
        return audio?.let { "$server • $it" } ?: server
    }

    fun linkName(label: String, originalName: String, master: Boolean, quality: Int,
        extractorSource: String = ""): String {
        val base = display(label, extractorSource, originalName)
        val explicitHeight = resolution.find(originalName)?.groupValues?.get(1)?.toIntOrNull()
            ?: originalName.trim().toIntOrNull()
        val height = explicitHeight?.takeIf { it in 1..4320 && it != Qualities.Unknown.value }
            ?: quality.takeIf { it in 1..4320 && it != Qualities.Unknown.value }
        val rendition = if (master) "Auto" else height?.let { "${it}p" } ?: "Unknown"
        return "$base • $rendition"
    }
}
