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

    override fun intercept(chain: Interceptor.Chain): Response {
        val active = delegate
        return if (active != null) {
            active.intercept(chain)
        } else {
            chain.proceed(chain.request())
        }
    }
}
