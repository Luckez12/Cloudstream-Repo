package com.yomi

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.mapper
import org.jsoup.nodes.Document

/** Decode Next's published text chunks, including arrays split across script tags. */
internal object YomiCatalogue {
    private data class CachedAnime(val node: JsonNode, val time: Long)
    private val cache = object : LinkedHashMap<Int, CachedAnime>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedAnime>?): Boolean = size > 256
    }
    fun cached(id: Int): JsonNode? = synchronized(cache) {
        cache[id]?.takeIf { System.currentTimeMillis() - it.time < 600_000L }?.node
    }
    fun remember(nodes: List<JsonNode>) = synchronized(cache) {
        nodes.forEach { node -> node.path("id").asInt().takeIf { it > 0 }?.let { cache[it] = CachedAnime(node, System.currentTimeMillis()) } }
    }
    data class Decoded(val items: List<JsonNode>, val chunks: Int, val errors: Int, val arrays: Int)
    fun initial(document: Document): List<JsonNode> = decode(document).items

    fun decode(document: Document, rawBody: String = ""): Decoded {
        val chunks = mutableListOf<String>()
        var errors = 0
        // Match each push separately; whitespace and several pushes in one script are legal.
        val push = Regex("self\\.__next_f\\.push\\s*\\(\\s*\\[\\s*1\\s*,\\s*")
        document.select("script").forEach { script ->
            val raw = script.data()
            push.findAll(raw).forEach pushes@ { match ->
                val start = match.range.last + 1
                if (raw.getOrNull(start) != '"') { errors++; return@pushes }
                var escaped = false
                var end = -1
                for (index in start + 1 until raw.length) {
                    val c = raw[index]
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') { end = index; break }
                }
                val decoded = if (end >= 0) runCatching {
                    mapper.readTree(raw.substring(start, end + 1)).asText()
                }.getOrNull() else null
                if (decoded == null) errors++ else chunks.add(decoded)
            }
        }
        // Next can also return its text stream instead of an HTML document.
        val text = if (chunks.isNotEmpty()) chunks.joinToString("") else rawBody
        val marker = Regex("\"initialAnime\"\\s*:\\s*")
        var arrays = 0
        for (match in marker.findAll(text)) {
            val start = match.range.last + 1
            if (text.getOrNull(start) != '[') { errors++; continue }
            var depth = 0
            var quoted = false
            var escaped = false
            var end = -1
            for (index in start until text.length) {
                val c = text[index]
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { end = index; break } }
                }
            }
            if (end < 0) { errors++; continue }
            val nodes = runCatching { mapper.readTree(text.substring(start, end + 1)).toList() }.getOrNull()
            if (nodes == null) { errors++; continue }
            arrays++
            val valid = nodes.filter { it.path("id").asInt() > 0 && title(it).isNotBlank() }
            if (valid.isNotEmpty()) return Decoded(valid, chunks.size, errors, arrays)
        }
        return Decoded(emptyList(), chunks.size, errors, arrays)
    }
    fun title(node: JsonNode): String = node.path("title").path("english").asText("")
        .ifBlank { node.path("title").path("romaji").asText("") }
        .ifBlank { node.path("title").path("native").asText("") }
    fun count(node: JsonNode): Int? = when (node.path("status").asText()) {
        "NOT_YET_RELEASED" -> 0
        "FINISHED" -> node.path("episodes").asInt().takeIf { it > 0 }
        "RELEASING" -> node.path("nextAiringEpisode").path("episode").asInt().takeIf { it > 0 }?.minus(1)
        else -> null
    }
}
