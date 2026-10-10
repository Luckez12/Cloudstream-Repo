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
    fun initial(document: Document): List<JsonNode> {
        val text = document.select("script").mapNotNull { script ->
            val raw = script.data()
            val prefix = "self.__next_f.push([1,"
            val start = raw.indexOf(prefix).takeIf { it >= 0 }?.plus(prefix.length) ?: return@mapNotNull null
            val end = raw.lastIndexOf("])").takeIf { it > start } ?: return@mapNotNull null
            runCatching { mapper.readTree(raw.substring(start, end)).asText() }.getOrNull()
        }.joinToString("")
        val start = text.indexOf("\"initialAnime\":").takeIf { it >= 0 } ?: return emptyList()
        val arrayStart = text.indexOf('[', start)
        if (arrayStart < 0) return emptyList()
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in arrayStart until text.length) {
            val c = text[index]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return runCatching {
                    mapper.readTree(text.substring(arrayStart, index + 1)).toList()
                }.getOrDefault(emptyList()) }
            }
        }
        return emptyList()
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
