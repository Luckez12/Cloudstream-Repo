package com.anichin

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Compile-safe bridge to Cloudstream's Android-only CloudflareKiller.
 *
 * Extension builds compile against the common Cloudstream library, while
 * CloudflareKiller is supplied by the Android app at runtime. Reflection keeps
 * this module buildable and falls back to a normal request if the runtime does
 * not expose the interceptor.
 */
class CloudflareCompat : Interceptor {
    private val fallbackCookies = ConcurrentHashMap<String, Map<String, String>>()

    private val delegate: Interceptor? by lazy {
        runCatching {
            val clazz = Class.forName("com.lagradost.cloudstream3.network.CloudflareKiller")
            clazz.getDeclaredConstructor().newInstance() as? Interceptor
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    val savedCookies: MutableMap<String, Map<String, String>>
        get() {
            val active = delegate ?: return fallbackCookies

            return runCatching {
                val getter = active.javaClass.methods.firstOrNull {
                    it.name == "getSavedCookies" && it.parameterCount == 0
                }
                val value = getter?.invoke(active) ?: run {
                    val field = active.javaClass.getDeclaredField("savedCookies").apply {
                        isAccessible = true
                    }
                    field.get(active)
                }
                value as? MutableMap<String, Map<String, String>>
            }.getOrNull() ?: fallbackCookies
        }

    private val challengeLock = Any()
    @Volatile private var clearanceGeneration = 0L

    private fun withCookies(request: okhttp3.Request): okhttp3.Request {
        val cookies = savedCookies[request.url.host].orEmpty()
        if (cookies.isEmpty()) return request
        val merged = linkedMapOf<String, String>()
        request.header("Cookie")?.split(';')?.forEach { entry ->
            val parts = entry.trim().split('=', limit = 2)
            if (parts.size == 2) merged[parts[0]] = parts[1]
        }
        merged.putAll(cookies)
        val builder = request.newBuilder().header(
            "Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        )
        // Clearance is bound to the WebView UA that solved the challenge.
        val clearanceHeaders = runCatching {
            delegate?.javaClass?.getMethod("getCookieHeaders", String::class.java)
                ?.invoke(delegate, request.url.toString()) as? okhttp3.Headers
        }.getOrNull()
        clearanceHeaders?.get("User-Agent")?.let { builder.header("User-Agent", it) }
        return builder.build()
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.header("cf-mitigated").equals("challenge", true)) return true
        if (response.code != 403 && response.code != 503) return false
        val body = response.peekBody(16_384).string()
        return body.contains("cf-chl-", true) ||
            body.contains("/cdn-cgi/challenge-platform", true) ||
            body.contains("Just a moment", true)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val active = delegate ?: return chain.proceed(chain.request())
        val original = chain.request()
        val host = original.url.host
        val generationAtStart = clearanceGeneration
        val cookiesAtStart = savedCookies[host]?.toMap()
        val initial = chain.proceed(withCookies(original))
        if (!isChallenge(initial)) return initial
        initial.close()

        // Normal requests stay parallel. Only confirmed challenges enter the
        // WebView lane, including after a previously valid clearance expires.
        return synchronized(challengeLock) {
            if (clearanceGeneration != generationAtStart ||
                savedCookies[host]?.toMap() != cookiesAtStart) {
                val retry = chain.proceed(withCookies(original))
                if (!isChallenge(retry)) return@synchronized retry
                retry.close()
            }
            // The runtime interceptor otherwise keeps retrying an expired
            // cached cookie instead of entering its solver.
            savedCookies.remove(host)
            val resolved = active.intercept(chain)
            if (resolved.isSuccessful && !isChallenge(resolved)) clearanceGeneration++
            resolved
        }
    }
}
