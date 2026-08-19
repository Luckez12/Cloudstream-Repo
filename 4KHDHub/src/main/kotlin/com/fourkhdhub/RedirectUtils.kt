package com.fourkhdhub

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private val redirectRegex = Regex(
    """s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)'""",
)

suspend fun resolveFourKRedirect(url: String): String {
    val html = try {
        app.get(url, timeout = 15L).text
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        println("[4KHDHub] redirect page failed: ${error.message}")
        return url
    }

    val joined = buildString {
        redirectRegex.findAll(html).forEach { match ->
            append(
                match.groups[1]?.value
                    ?: match.groups[2]?.value
                    ?: "",
            )
        }
    }

    if (joined.isBlank()) {
        return url
    }

    return try {
        val first = base64Decode(joined)
        val second = base64Decode(first)
        val rotated = rot13(second)
        val payload = base64Decode(rotated)
        val json = JSONObject(payload)

        val direct = decodeBase64Safe(
            json.optString("o"),
        ).trim()

        if (direct.isNotBlank()) {
            direct
        } else {
            val redirectData = decodeBase64Safe(
                json.optString("data"),
            ).trim()

            val blogUrl = json.optString("blog_url").trim()

            if (redirectData.isBlank() || blogUrl.isBlank()) {
                url
            } else {
                val result = app.get(
                    "$blogUrl?re=$redirectData",
                    timeout = 15L,
                ).document.text().trim()

                result.ifBlank { url }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        println(
            "[4KHDHub] redirect decode failed: ${error.message}",
        )
        url
    }
}

private fun decodeBase64Safe(value: String): String {
    if (value.isBlank()) return ""
    return runCatching { base64Decode(value) }.getOrDefault("")
}

private fun rot13(value: String): String {
    return buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    in 'A'..'Z' ->
                        'A' + ((char - 'A' + 13) % 26)
                    in 'a'..'z' ->
                        'a' + ((char - 'a' + 13) % 26)
                    else -> char
                },
            )
        }
    }
}
