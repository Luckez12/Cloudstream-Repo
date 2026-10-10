package com.yomi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.os.Message
import android.view.View
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Disposable rendering sessions; no challenge solving or persistent JS bridge. */
internal object YomiWeb {
    private val slots = Semaphore(2)
    data class Embed(val url: String, val audio: String)
    data class Watch(val html: String, val embeds: List<Embed>)
    data class Media(val url: String, val headers: Map<String, String>, val kind: String = "video", val language: String = "")

    private fun context(): Context? = runCatching {
        val cls = Class.forName("com.lagradost.cloudstream3.CloudStreamApp")
        val companion = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        companion.javaClass.methods.first { it.name == "getContext" && it.parameterCount == 0 }
            .invoke(companion) as? Context
    }.getOrNull()?.applicationContext ?: runCatching {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(url: String, referer: String, scanWatch: Boolean, collectServers: Boolean = true,
                               onEmbed: ((Embed) -> Unit)? = null, onMedia: ((Media) -> Unit)? = null): String = slots.withPermit {
        withContext(Dispatchers.Main) {
            val ctx = context() ?: return@withContext ""
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val view = WebView(ctx)
                // Unattached views otherwise have a zero-sized viewport; lazy players may never initialise.
                view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1280, 720)
                val media = linkedMapOf<String, Media>()
                val discovered = mutableSetOf<String>()
                var closed = false
                var pending = false
                fun destroy() {
                    if (closed) return
                    closed = true
                    handler.removeCallbacksAndMessages(null)
                    view.stopLoading()
                    view.removeAllViews()
                    view.destroy()
                }
                fun finish(value: String) {
                    if (closed) return
                    destroy()
                    if (continuation.isActive) continuation.resume(value)
                }
                fun mediaResult() = mapper.writeValueAsString(media.values.toList())
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                }
                val userAgent = view.settings.userAgentString
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url ?: return true
                        // Keep the main frame on the requested host; embeds may load their own frames.
                        return request.isForMainFrame && target.host != android.net.Uri.parse(url).host
                    }
                    override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request == null) return null
                        val target = request.url.toString()
                        val path = request.url.path.orEmpty().lowercase()
                        val caption = path.endsWith(".vtt") || path.endsWith(".srt")
                        if (!(path.endsWith(".m3u8") || path.endsWith(".mp4") || caption)) return null
                        if (scanWatch) return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        val headers = request.requestHeaders.filterKeys { !it.equals("Range", true) }.toMutableMap()
                        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = userAgent
                        CookieManager.getInstance().getCookie(target)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                        handler.post {
                            if (!closed) {
                                val captured = Media(target, headers, if (caption) "subtitle" else "video")
                                if (media.put(target, captured) == null) onMedia?.invoke(captured)
                                if (onMedia == null && !caption && !pending) {
                                    pending = true
                                    handler.postDelayed({ finish(mediaResult()) }, 900L)
                                }
                            }
                        }
                        // Leave video bytes to the native player and preserve one-use URLs.
                        return if (caption) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (closed) return
                        view.evaluateJavascript(if (scanWatch) { if (collectServers) WATCH_SCAN else WATCH_FAST } else MEDIA_SCAN) { raw ->
                            if (!closed) {
                                val value = runCatching { mapper.readTree(raw).asText("") }.getOrDefault("")
                                if (scanWatch && value.isNotBlank()) {
                                    val snapshot = runCatching { mapper.readTree(value) }.getOrNull()
                                    snapshot?.path("embeds")?.forEach { item ->
                                        val src = item.path("url").asText("")
                                        if (src.startsWith("https://") && discovered.add(src))
                                            onEmbed?.invoke(Embed(src, item.path("audio").asText("sub")))
                                    }
                                    if (!collectServers || snapshot?.path("done")?.asBoolean() == true) finish(value)
                                    else handler.postDelayed(this, 750L)
                                }
                                else {
                                    if (!scanWatch && value.isNotBlank()) runCatching {
                                        mapper.readTree(value).forEach { track ->
                                            val src = track.path("url").asText("")
                                            if (src.startsWith("https://") || src.startsWith("http://")) {
                                                val kind = track.path("kind").asText("video")
                                                val captured = Media(src, mapOf("Referer" to track.path("referer").asText(url)), kind, track.path("language").asText(""))
                                                val previous = media[src]
                                                val enriched = if (previous != null) captured.copy(headers = captured.headers + previous.headers) else captured
                                                media[src] = enriched
                                                if (previous == null || (kind == "subtitle" && captured.language.isNotBlank() && previous.language != captured.language))
                                                    onMedia?.invoke(enriched)
                                                if (onMedia == null && kind == "video" && !pending) {
                                                    pending = true
                                                    handler.postDelayed({ finish(mediaResult()) }, 900L)
                                                }
                                            }
                                        }
                                    }
                                    handler.postDelayed(this, 750L)
                                }
                            }
                        }
                    }
                }
                continuation.invokeOnCancellation { handler.post { destroy() } }
                handler.postDelayed({
                    if (scanWatch) view.evaluateJavascript("JSON.stringify({html:document.documentElement.outerHTML,embeds:(window.__yomiScan||{}).embeds||[]})") { raw ->
                        finish(runCatching { mapper.readTree(raw).asText("") }.getOrDefault(""))
                    } else finish(mediaResult())
                }, if (scanWatch) 28_000L else 18_000L)
                view.loadUrl(url, mapOf("Referer" to referer))
                handler.postDelayed(poll, 750L)
            }
        }
    }

    suspend fun watch(url: String, collectServers: Boolean = true, onEmbed: ((Embed) -> Unit)? = null): Watch {
        val seen = mutableSetOf<String>()
        val discovered: (Embed) -> Unit = { embed -> if (seen.add(embed.url)) onEmbed?.invoke(embed) }
        val raw = render(url, "https://yomi.to/", true, collectServers, onEmbed = discovered)
        val root = runCatching { mapper.readTree(raw) }.getOrNull()
        val embeds = root?.path("embeds")?.mapNotNull {
            val src = it.path("url").asText("")
            if (src.startsWith("https://")) Embed(src, it.path("audio").asText("sub")) else null
        }.orEmpty()
        embeds.forEach(discovered)
        return Watch(root?.path("html")?.asText("").orEmpty(), embeds)
    }

    suspend fun streams(url: String, referer: String, accept: suspend (Media) -> Boolean) = coroutineScope {
        val events = Channel<Media>(Channel.UNLIMITED)
        val producer = launch {
            try { render(url, referer, false, onMedia = { events.trySend(it) }) }
            finally { events.close() }
        }
        try {
            for (media in events) if (accept(media)) break
        } finally {
            producer.cancelAndJoin()
            events.cancel()
        }
    }

    private val WATCH_FAST = """
        (function(){if(!document.querySelector('a[href^="/watch/"][title^="Episode "]'))return '';
          return JSON.stringify({html:document.documentElement.outerHTML,embeds:[]});})()
    """.trimIndent()
    private val WATCH_SCAN = """
        (function(){
          var links=document.querySelectorAll('a[href^="/watch/"]');
          var buttons=Array.from(document.querySelectorAll('button[title]')).filter(b=>/^Server [1-6](?:\s|${'$'})/.test(b.title));
          if(!links.length||!buttons.length)return '';
          var s=window.__yomiScan;
          if(!s){s=window.__yomiScan={index:0,phase:0,embeds:[]};}
          function audio(t){return Array.from(document.querySelectorAll('main button')).find(b=>b.textContent.trim()===t);}
          function capture(channel){
            var frame=document.querySelector('main iframe[src^="https://"]');
            if(frame&&!s.embeds.some(e=>e.url===frame.src))s.embeds.push({url:frame.src,audio:channel});
          }
          if(s.index>=buttons.length)return JSON.stringify({done:true,html:document.documentElement.outerHTML,embeds:s.embeds});
          if(s.phase===0){buttons[s.index].click();var sub=audio('SUB');if(sub&&!sub.disabled)sub.click();s.phase=1;return '';}
          capture('sub');s.index++;s.phase=0;return JSON.stringify({done:false,embeds:s.embeds});
        })()
    """.trimIndent()
    private val MEDIA_SCAN = """
        (function(){
          var found=[];
          function scan(doc){
            doc.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{});});
            doc.querySelectorAll('video[src],video source[src],track[src]').forEach(el=>{
              var src=el.src;if(!/^https?:/.test(src))return;
              found.push({url:src,referer:doc.URL,kind:el.tagName==='TRACK'?'subtitle':'video',language:el.label||el.srclang||''});
            });
            doc.querySelectorAll('button[aria-label="Play"],button[title="Play"],.jw-icon-display').forEach(b=>b.click());
            doc.querySelectorAll('iframe').forEach(f=>{try{if(f.contentDocument)scan(f.contentDocument);}catch(e){}});
          }scan(document);return JSON.stringify(found);
        })()
    """.trimIndent()
}
