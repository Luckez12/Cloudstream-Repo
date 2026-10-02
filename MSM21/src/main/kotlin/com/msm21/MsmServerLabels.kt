package com.msm21

/** Keep website labels for discovery/logs; format only emitted source names. */
internal object MsmServerLabels {
    private val aliases = mapOf(
        "rpmpl" to "RPM", "rpm" to "RPM",
        "seekp" to "Seek", "seek" to "Seek",
        "upns" to "Upns", "p2pst" to "P2P", "p2p" to "P2P",
        "byses" to "Byse", "byse" to "Byse"
    )
    private val prefix = Regex("^(rpmpl|rpm|seekp|seek|upns|p2pst|p2p|byses|byse)(?=malaysub|\\b)", RegexOption.IGNORE_CASE)
    private val resolution = Regex("(?<![0-9])(2160|1440|1080|720|480|360|240|144)p?(?![0-9])", RegexOption.IGNORE_CASE)

    fun display(label: String): String {
        val clean = label.trim()
        val slug = prefix.find(clean)?.value?.lowercase() ?: return clean
        val name = aliases.getValue(slug)
        return if (clean.contains("MalaySub", ignoreCase = true)) "$name • MalaySub" else name
    }

    fun linkName(label: String, originalName: String, master: Boolean, quality: Int): String {
        val display = display(label)
        // A master chooses quality automatically; don't label it as one rendition.
        if (master) return display
        val height = resolution.find(originalName)?.groupValues?.get(1)?.toIntOrNull()
            ?: quality.takeIf { it in setOf(2160, 1440, 1080, 720, 480, 360, 240, 144) }
        if (height != null) return "$display • ${height}p"
        if (prefix.containsMatchIn(label.trim())) return display
        // Preserve unfamiliar server names, while removing a redundant Auto suffix.
        val detail = originalName.trim().takeUnless { it.isBlank() || it.equals("Auto", true) || it == label }
        return if (detail == null) display else "$display $detail"
    }
}
