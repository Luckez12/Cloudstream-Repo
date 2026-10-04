package com.msm21

/** Format every server, including unknown ones; website labels remain in diagnostics. */
internal object MsmServerLabels {
    // Optional legacy display aliases, not a list of servers eligible for formatting.
    private val aliases = mapOf(
        "rpmpl" to "RPM", "rpm" to "RPM", "seekp" to "Seek", "seek" to "Seek",
        "upns" to "Upns", "p2pst" to "P2P", "p2p" to "P2P", "byses" to "Byse",
        "byse" to "Byse", "playm" to "Playmate", "playmate" to "Playmate",
        "abyss" to "Abyss", "larhu" to "Larhu", "ezpla" to "Ezplayer",
        "playe" to "Player", "mixdr" to "MixDrop"
    )
    private val language = Regex("(?:Malay\\s*(?:Sub|Dub)|[A-Z][a-z]+\\s*(?:Sub|Dub)|(?<=\\s)[\\p{L}]+\\s+(?:Sub|Dub))")
    private val foldedLanguage = Regex("Malay\\s*(?:Sub|Dub)", RegexOption.IGNORE_CASE)
    private val resolution = Regex("(?<![0-9])(2160|1440|1080|720|480|360|240|144)p?(?![0-9])", RegexOption.IGNORE_CASE)
    private val genericExtractor = Regex("^(?:auto|video|hls|native|playerx|playerx\\s+.*|in-house|google|tiktok|\\d+p?)$", RegexOption.IGNORE_CASE)
    private data class Parts(val server: String, val audio: String?)

    private fun parts(label: String): Parts {
        val clean = label.trim().replace('_', ' ').replace(Regex("\\s+"), " ")
        val match = foldedLanguage.find(clean) ?: language.find(clean)
        if (match == null) return Parts(clean, null)
        val server = clean.substring(0, match.range.first).trim().trimEnd('•', '-', '|').trim()
        // Remove an option ordinal only after an identified language tag.
        val tail = clean.substring(match.range.last + 1).trim()
        if (tail.isNotEmpty() && !tail.matches(Regex("\\d+"))) return Parts(clean, null)
        val audio = match.value.replace(Regex("\\s+"), " ").trim()
        val suffix = if (audio.endsWith("Dub", true)) "Dub" else "Sub"
        val locale = audio.dropLast(3).trim().replaceFirstChar { it.uppercase() }
        return Parts(server, if (locale.equals("Malay", true) && suffix == "Sub") "MalaySub" else "$locale $suffix")
    }

    private fun extractorServer(value: String, label: String): String? {
        val clean = value.trim()
        if (clean.isBlank() || clean.equals(label.trim(), true) || genericExtractor.matches(clean)) return null
        val name = parts(clean).server.replace(Regex("\\s+(?:Auto|\\d{3,4}p)$", RegexOption.IGNORE_CASE), "").trim()
        return name.takeUnless { it.isBlank() || genericExtractor.matches(it) }
    }

    fun display(label: String, extractorSource: String = "", extractorName: String = ""): String {
        val part = parts(label)
        val server = aliases[part.server.lowercase()]
            ?: extractorServer(extractorSource, label)
            ?: extractorServer(extractorName, label)
            ?: part.server
        val safeServer = server.ifBlank { label.trim() }
        return part.audio?.let { "$safeServer • $it" } ?: safeServer
    }

    fun linkName(label: String, originalName: String, master: Boolean, quality: Int,
        extractorSource: String = ""): String {
        val display = display(label, extractorSource, originalName)
        if (master) return display
        val height = resolution.find(originalName)?.groupValues?.get(1)?.toIntOrNull()
            ?: quality.takeIf { it in setOf(2160, 1440, 1080, 720, 480, 360, 240, 144) }
        return if (height == null) display else "$display • ${height}p"
    }
}
