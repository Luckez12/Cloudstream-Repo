package com.msm21

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Dsvplay : DoodLaExtractor() {
    override var mainUrl = "https://dsvplay.com"
}

class Bysesukior : ByseSX() {
    override val name = "Bysesukior"
    override val mainUrl = "https://bysesukior.com"
}

class MixDropTop : MixDrop() {
    override var mainUrl = "https://mixdrop.top"
}

/**
 * Fallback untuk player JavaScript seperti Abyss dan rangkaian PlayerX.
 * Player dimuatkan dalam iframe kerana Abyss menolak akses top-level.
 * Permintaan media dipintas sebelum token video digunakan oleh WebView.
 */
object MsmWebViewProbe {
    private const val MAX_WAIT_MS = 14_000L
    private const val FINISH_AFTER_FIRST_STREAM_MS = 1_200L

    data class CapturedStream(
        val label: String,
        val url: String,
        val headers: Map<String, String>
    )

    private class Bridge(
        private val onCapture: (String) -> Unit
    ) {
        @JavascriptInterface
        fun capture(value: String?) {
            onCapture(value.orEmpty())
        }
    }

    suspend fun extractFast(
        url: String,
        referer: String
    ): List<CapturedStream> = withContext(Dispatchers.Main) {
        val context = MsmRuntime.context
            ?: return@withContext emptyList()

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            val streams = linkedMapOf<String, CapturedStream>()
            var finishScheduled = false

            fun safeDestroy() {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface("msmBridge")
                    webView.removeAllViews()
                    webView.destroy()
                }
            }

            fun sortedResult(): List<CapturedStream> {
                return streams.values
                    .distinctBy { it.url }
                    .sortedWith(
                        compareByDescending<CapturedStream> {
                            qualityScore(it.label, it.url)
                        }.thenBy { it.label }
                    )
            }

            fun finish() {
                handler.post {
                    if (continuation.isActive) {
                        val result = sortedResult()
                        safeDestroy()
                        continuation.resume(result)
                    }
                }
            }

            fun scheduleFinishSoon() {
                if (finishScheduled) return
                finishScheduled = true
                handler.postDelayed(
                    { finish() },
                    FINISH_AFTER_FIRST_STREAM_MS
                )
            }

            fun addStream(
                label: String,
                rawUrl: String?,
                headers: Map<String, String>
            ) {
                val fixedUrl = rawUrl
                    ?.trim()
                    ?.toAbsoluteUrl(url)
                    ?.takeIf(::isStreamUrl)
                    ?: return

                val fixedHeaders = headers.toMutableMap().apply {
                    put("User-Agent", get("User-Agent") ?: USER_AGENT)
                    put("Accept", get("Accept") ?: "*/*")
                    put("Referer", get("Referer") ?: url)
                }

                if (!streams.containsKey(fixedUrl)) {
                    streams[fixedUrl] = CapturedStream(
                        label = label.trim().ifBlank {
                            guessLabel(fixedUrl)
                        },
                        url = fixedUrl,
                        headers = fixedHeaders
                    )
                }

                scheduleFinishSoon()
            }

            fun handleBridgeCapture(value: String) {
                val clean = value.trim()
                if (clean.isBlank()) return

                when {
                    clean.startsWith("MSM_SOURCE|") -> {
                        val parts = clean.split("|", limit = 4)
                        if (parts.size >= 4) {
                            addStream(
                                label = parts[1],
                                rawUrl = parts[3],
                                headers = defaultHeaders(url)
                            )
                        }
                    }

                    clean.startsWith("MSM_VIDEO|") -> {
                        val file = clean.removePrefix("MSM_VIDEO|")
                        addStream(
                            label = guessLabel(file),
                            rawUrl = file,
                            headers = defaultHeaders(url)
                        )
                    }

                    clean.startsWith("MSM_FETCH|") ||
                        clean.startsWith("MSM_XHR|") -> {
                        val file = clean.substringAfter('|')
                        if (isStreamUrl(file)) {
                            addStream(
                                label = guessLabel(file),
                                rawUrl = file,
                                headers = defaultHeaders(url)
                            )
                        }
                    }
                }
            }

            fun clickWebView() {
                if (streams.isNotEmpty()) return

                runCatching {
                    val now = SystemClock.uptimeMillis()
                    val x = 540f
                    val y = 540f

                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(
                            now,
                            now,
                            MotionEvent.ACTION_DOWN,
                            x,
                            y,
                            0
                        )
                    )
                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(
                            now,
                            now + 80,
                            MotionEvent.ACTION_UP,
                            x,
                            y,
                            0
                        )
                    )
                }
            }

            fun captureStream(request: WebResourceRequest?) {
                val requestUrl = request?.url?.toString()?.trim().orEmpty()
                if (!isStreamUrl(requestUrl)) return

                val headers = request?.requestHeaders
                    .orEmpty()
                    .toMutableMap()

                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(requestUrl)
                }.getOrNull().orEmpty()

                if (cookie.isNotBlank()) {
                    headers["Cookie"] = cookie
                }

                addStream(
                    label = guessLabel(requestUrl),
                    rawUrl = requestUrl,
                    headers = headers
                )
            }

            continuation.invokeOnCancellation {
                handler.post { safeDestroy() }
            }

            @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
            fun setup() {
                WebView.setWebContentsDebuggingEnabled(false)

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.addJavascriptInterface(
                    Bridge(::handleBridgeCapture),
                    "msmBridge"
                )
                webView.layout(0, 0, 1080, 1080)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = USER_AGENT
                }

                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        pageUrl: String?,
                        favicon: Bitmap?
                    ) = Unit

                    override fun onPageFinished(
                        view: WebView?,
                        pageUrl: String?
                    ) = Unit

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString().orEmpty()

                        if (shouldInjectAbyssPage(requestUrl)) {
                            return runCatching {
                                injectIntoAbyssPage(
                                    pageUrl = requestUrl,
                                    referer = referer
                                )
                            }.getOrNull()
                        }

                        if (isStreamUrl(requestUrl)) {
                            captureStream(request)
                            return WebResourceResponse(
                                "video/mp4",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        pageUrl: String?
                    ): Boolean = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false
                }

                val wrapper = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            html, body, iframe {
                                margin: 0;
                                padding: 0;
                                width: 100%;
                                height: 100%;
                                background: #000;
                                border: 0;
                                overflow: hidden;
                            }
                        </style>
                    </head>
                    <body>
                        <iframe
                            id="msm_player_frame"
                            src="${htmlEscape(url)}"
                            allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
                            allowfullscreen>
                        </iframe>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(
                    referer,
                    wrapper,
                    "text/html",
                    "UTF-8",
                    null
                )

                listOf(
                    650L,
                    1_300L,
                    2_200L,
                    3_400L,
                    5_000L,
                    7_000L,
                    9_500L,
                    12_000L
                ).forEach { delay ->
                    handler.postDelayed({ clickWebView() }, delay)
                }

                handler.postDelayed({ finish() }, MAX_WAIT_MS)
            }

            runCatching { setup() }
                .onFailure { finish() }
        }
    }

    private fun injectIntoAbyssPage(
        pageUrl: String,
        referer: String
    ): WebResourceResponse {
        val connection = URL(pageUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        connection.setRequestProperty(
            "Accept-Language",
            "ms-MY,ms;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val html = connection.inputStream
            .bufferedReader()
            .use { it.readText() }

        connection.headerFields
            .filterKeys { it?.equals("Set-Cookie", true) == true }
            .values
            .flatten()
            .forEach { cookie ->
                runCatching {
                    CookieManager.getInstance().setCookie(pageUrl, cookie)
                }
            }
        runCatching { CookieManager.getInstance().flush() }
        connection.disconnect()

        val injected = if (html.contains("<head>", true)) {
            html.replaceFirst(
                Regex("<head>", RegexOption.IGNORE_CASE),
                "<head>$HOOK_JS"
            )
        } else {
            "$HOOK_JS$html"
        }

        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }

    private fun shouldInjectAbyssPage(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("abyss") &&
            (value.contains("?v=") || value.contains("&v="))
    }

    private fun isStreamUrl(rawUrl: String?): Boolean {
        val value = rawUrl?.lowercase().orEmpty()
        if (value.isBlank()) return false
        if (BLOCKED_MEDIA_PARTS.any(value::contains)) return false

        return value.contains("/sora/") ||
            value.contains(".m3u8") ||
            value.contains(".mp4") ||
            value.contains(".m4v")
    }

    private fun guessLabel(url: String): String {
        val value = url.lowercase()
        return when {
            value.contains("2160") -> "2160p"
            value.contains("1440") -> "1440p"
            value.contains("1080") -> "1080p"
            value.contains("/1421764806/") || value.contains("720") -> "720p"
            value.contains("480") -> "480p"
            value.contains("/677311756/") || value.contains("360") -> "360p"
            else -> "Auto"
        }
    }

    private fun qualityScore(label: String, url: String): Int {
        val value = "${label.lowercase()} ${url.lowercase()}"
        return when {
            value.contains("2160") -> 2160
            value.contains("1440") -> 1440
            value.contains("1080") -> 1080
            value.contains("720") || value.contains("/1421764806/") -> 720
            value.contains("480") -> 480
            value.contains("360") || value.contains("/677311756/") -> 360
            else -> 0
        }
    }

    private fun String.toAbsoluteUrl(baseUrl: String): String {
        val value = trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", true) -> value
            else -> runCatching {
                URI(baseUrl).resolve(value).toString()
            }.getOrDefault(value)
        }
    }

    private fun defaultHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to referer
        )
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private val BLOCKED_MEDIA_PARTS = listOf(
        "googlesyndication",
        "doubleclick.net",
        "google-analytics",
        "googletagmanager",
        "vast",
        "pixel.morphify",
        "decafeligiblyhad",
        "algiersreests",
        "morestamping"
    )

    private const val HOOK_JS = """
<script>
(function() {
  if (window.__msmHooked) return;
  window.__msmHooked = true;

  function cap(value) {
    try {
      if (window.msmBridge && window.msmBridge.capture) {
        window.msmBridge.capture(String(value));
      }
    } catch(e) {}
  }

  function abs(url) {
    if (!url) return "";
    url = String(url);
    if (url.indexOf("//") === 0) return "https:" + url;
    return url;
  }

  function sendSources(list) {
    try {
      if (!list || !list.length) return;
      for (var i = 0; i < list.length; i++) {
        var source = list[i] || {};
        var label = source.label || source.name || source.height || "Auto";
        var type = source.type || "";
        var file = source.file || source.url || "";
        if (file) {
          cap("MSM_SOURCE|" + label + "|" + type + "|" + abs(file));
        }
      }
    } catch(e) {}
  }

  function inspectPlayer() {
    try {
      if (typeof window.jwplayer === "function") {
        var player = window.jwplayer();
        if (player) {
          if (player.getPlaylist) {
            var playlist = player.getPlaylist() || [];
            for (var i = 0; i < playlist.length; i++) {
              var item = playlist[i] || {};
              sendSources(item.sources);
              sendSources(item.allSources);
            }
          }
          if (player.getPlaylistItem) {
            var current = player.getPlaylistItem() || {};
            sendSources(current.sources);
            sendSources(current.allSources);
          }
          if (player.getConfig) {
            var config = player.getConfig() || {};
            sendSources(config.sources);
            if (config.playlist && config.playlist.length) {
              for (var c = 0; c < config.playlist.length; c++) {
                sendSources((config.playlist[c] || {}).sources);
                sendSources((config.playlist[c] || {}).allSources);
              }
            }
          }
        }
      }

      var videos = document.querySelectorAll("video");
      for (var v = 0; v < videos.length; v++) {
        var src = videos[v].currentSrc || videos[v].src || "";
        if (src) cap("MSM_VIDEO|" + abs(src));
      }
    } catch(e) {}
  }

  try {
    var oldFetch = window.fetch;
    if (oldFetch) {
      window.fetch = function() {
        try { cap("MSM_FETCH|" + abs(arguments[0])); } catch(e) {}
        return oldFetch.apply(this, arguments);
      };
    }
  } catch(e) {}

  try {
    var oldOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, requestUrl) {
      try { cap("MSM_XHR|" + abs(requestUrl)); } catch(e) {}
      return oldOpen.apply(this, arguments);
    };
  } catch(e) {}

  inspectPlayer();
  setTimeout(inspectPlayer, 300);
  setTimeout(inspectPlayer, 700);
  setTimeout(inspectPlayer, 1200);
  setTimeout(inspectPlayer, 2000);
  setInterval(inspectPlayer, 1000);
})();
</script>
    """
}
