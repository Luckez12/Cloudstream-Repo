package com.msm21

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

object MsmRuntime {
    @Volatile
    var context: Context? = null

    fun resolveContext(): Context? {
        context?.let { return it }

        val resolved = cloudstreamContext() ?: activityThreadContext()
        if (resolved != null) context = resolved.applicationContext
        return context
    }

    private fun cloudstreamContext(): Context? = runCatching {
        val appClass = Class.forName("com.lagradost.cloudstream3.CloudStreamApp")
        val companionField = appClass.getDeclaredField("Companion").apply { isAccessible = true }
        val companion = companionField.get(null)
        val getter = companion.javaClass.methods.firstOrNull {
            it.name == "getContext" && it.parameterCount == 0
        } ?: return@runCatching null
        getter.invoke(companion) as? Context
    }.getOrNull()

    private fun activityThreadContext(): Context? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val method = threadClass.getDeclaredMethod("currentApplication").apply { isAccessible = true }
        method.invoke(null) as? Context
    }.getOrNull()
}

@CloudstreamPlugin
class msm21plugin : BasePlugin() {
    override fun load() {
        MsmRuntime.resolveContext()
        registerMainAPI(msm21())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Bysesukior())
        registerExtractorAPI(MixDropTop())
    }
}
