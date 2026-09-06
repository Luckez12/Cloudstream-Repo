package com.fourkhdhub

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Compile-safe bridge to Cloudstream's Android-only CloudflareKiller.
 *
 * Current extension builds compile against the common Cloudstream library,
 * while CloudflareKiller lives in the Android app module. Resolve it at runtime
 * when available, otherwise behave as a normal pass-through interceptor.
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
