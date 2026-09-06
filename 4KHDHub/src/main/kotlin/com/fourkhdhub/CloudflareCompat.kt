package com.fourkhdhub

import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Library-compatible Cloudflare fallback for this extension.
 *
 * The old CloudflareKiller lives in the Cloudstream app module and is not part
 * of the current extension library artifact. This keeps the same basic flow:
 * reuse cf_clearance when available, otherwise let WebViewResolver solve the
 * challenge and retry with the resulting cookies.
 */
class CloudflareCompat : Interceptor {
    companion object {
        private val errorCodes = setOf(403, 503)
        private val cloudflareServers = setOf("cloudflare-nginx", "cloudflare")

        private fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(';').mapNotNull { part ->
                val pieces = part.trim().split("=", limit = 2)
                val key = pieces.getOrNull(0)?.trim().orEmpty()
                val value = pieces.getOrNull(1)?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
    }

    val savedCookies: MutableMap<String, Map<String, String>> = ConcurrentHashMap()

    init {
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    private fun normalizedHost(request: Request): String =
        request.url.host.removePrefix("www.").lowercase()

    private fun webViewCookie(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    private fun trySaveClearance(request: Request): Boolean {
        val cookieUrl = runCatching {
            val uri = URI(request.url.toString())
            "${uri.scheme ?: "https"}://${uri.host}/"
        }.getOrDefault(request.url.toString())

        val cookies = parseCookieMap(webViewCookie(cookieUrl).orEmpty())
        if (cookies["cf_clearance"].isNullOrBlank()) return false

        savedCookies[normalizedHost(request)] = cookies
        return true
    }

    private fun headersWithCookies(
        request: Request,
        cookies: Map<String, String>,
    ): Headers {
        val builder = request.headers.newBuilder()

        WebViewResolver.getWebViewUserAgent()?.let { userAgent ->
            builder.set("User-Agent", userAgent)
        }

        val requestCookies = parseCookieMap(request.header("Cookie").orEmpty())
        val mergedCookies = cookies + requestCookies
        if (mergedCookies.isNotEmpty()) {
            builder.set(
                "Cookie",
                mergedCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" },
            )
        }

        return builder.build()
    }

    private fun proceedWithCookies(
        request: Request,
        cookies: Map<String, String>,
    ): Response {
        val retry = request.newBuilder()
            .headers(headersWithCookies(request, cookies))
            .build()
        return app.baseClient.newCall(retry).execute()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (!trySaveClearance(request)) {
            WebViewResolver(
                Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex(".")),
            ).resolveUsingWebView(request.url.toString()) {
                trySaveClearance(request)
            }
        }

        val cookies = savedCookies[normalizedHost(request)] ?: return null
        return proceedWithCookies(request, cookies)
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val host = normalizedHost(request)

        savedCookies[host]?.let { cookies ->
            return@runBlocking proceedWithCookies(request, cookies)
        }

        val response = chain.proceed(request)
        val server = response.header("Server")?.lowercase()
        val isCloudflare = server in cloudflareServers ||
            response.header("CF-RAY") != null ||
            response.header("cf-mitigated") != null

        if (!isCloudflare || response.code !in errorCodes) {
            return@runBlocking response
        }

        response.close()
        bypassCloudflare(request)?.let { return@runBlocking it }

        // If WebView could not obtain clearance, return a normal retry so the
        // caller receives the real HTTP result rather than a synthetic one.
        chain.proceed(request)
    }
}
