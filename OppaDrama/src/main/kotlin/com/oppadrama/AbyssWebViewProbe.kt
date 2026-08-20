package com.oppadrama

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

object AbyssWebViewProbe {

    private const val MAX_WAIT_MS = 14000L
    private const val FINISH_AFTER_FIRST_STREAM_MS = 900L

    data class AbyssStream(
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
    ): List<AbyssStream> = withContext(Dispatchers.Main) {
        val context = OppaRuntime.context
            ?: return@withContext emptyList()

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            val streams = linkedMapOf<String, AbyssStream>()
            var finishScheduled = false

            fun safeDestroy() {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface("oppaBridge")
                    webView.removeAllViews()
                    webView.destroy()
                }
            }

            fun sortedResult(): List<AbyssStream> {
                return streams.values
                    .distinctBy { it.url }
                    .sortedWith(
                        compareByDescending<AbyssStream> {
                            qualityScore(it.label, it.url)
                        }.thenBy { it.label }
                    )
            }

            fun finish() {
                handler.post {
                    if (continuation.isActive) {
                        val result = sortedResult()
                        Log.i(
                            TAG,
                            "OPPA_HYDRAX_FINISH = streams=${result.size} | " +
                                result.joinToString { "${it.label}:${it.url.take(55)}" }
                        )
                        safeDestroy()
                        continuation.resume(result)
                    }
                }
            }

            fun scheduleFinishSoon() {
                if (finishScheduled) return
                finishScheduled = true

                handler.postDelayed({
                    finish()
                }, FINISH_AFTER_FIRST_STREAM_MS)
            }

            fun addStream(
                label: String,
                rawUrl: String?,
                headers: Map<String, String>,
                source: String
            ) {
                val fixedUrl = rawUrl
                    ?.trim()
                    ?.toAbsoluteStreamUrl()
                    ?.takeIf { isStreamUrl(it) }
                    ?: return

                val fixedHeaders = headers
                    .toMutableMap()
                    .apply {
                        put("User-Agent", get("User-Agent") ?: USER_AGENT)
                        put("Accept", get("Accept") ?: "*/*")
                        put("Referer", get("Referer") ?: url)
                    }

                val cleanLabel = label
                    .trim()
                    .ifBlank { guessLabel(fixedUrl) }

                if (!streams.containsKey(fixedUrl)) {
                    Log.i(
                        TAG,
                        "OPPA_HYDRAX_ADD_STREAM = $source | $cleanLabel | $fixedUrl | " +
                            fixedHeaders.keys.joinToString(",")
                    )

                    streams[fixedUrl] = AbyssStream(
                        label = cleanLabel,
                        url = fixedUrl,
                        headers = fixedHeaders
                    )
                }

                scheduleFinishSoon()
            }

            fun handleBridgeCapture(value: String) {
                val clean = value.trim()
                if (clean.isBlank()) return

                if (clean.startsWith("OPPA_SOURCE|")) {
                    val parts = clean.split("|", limit = 4)
                    if (parts.size >= 4) {
                        val label = parts[1]
                        val file = parts[3]

                        addStream(
                            label = label,
                            rawUrl = file,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
                                "Referer" to url
                            ),
                            source = "bridge"
                        )
                    }
                    return
                }

                if (clean.startsWith("OPPA_VIDEO|")) {
                    val file = clean.removePrefix("OPPA_VIDEO|")
                    addStream(
                        label = guessLabel(file),
                        rawUrl = file,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
                            "Referer" to url
                        ),
                        source = "video"
                    )
                    return
                }

                if (clean.startsWith("OPPA_FETCH|") || clean.startsWith("OPPA_XHR|")) {
                    val file = clean.substringAfter("|")
                    if (isStreamUrl(file)) {
                        addStream(
                            label = guessLabel(file),
                            rawUrl = file,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
                                "Referer" to url
                            ),
                            source = "network-hook"
                        )
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
                        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    )

                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
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
                    headers = headers,
                    source = "webview-request"
                )
            }

            continuation.invokeOnCancellation {
                safeDestroy()
            }

            @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
            fun setup() {
                WebView.setWebContentsDebuggingEnabled(false)

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.addJavascriptInterface(
                    Bridge { value ->
                        handler.post {
                            handleBridgeCapture(value)
                        }
                    },
                    "oppaBridge"
                )

                webView.layout(0, 0, 1080, 1080)

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.loadsImagesAutomatically = true
                webView.settings.javaScriptCanOpenWindowsAutomatically = true
                webView.settings.setSupportMultipleWindows(false)
                webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webView.settings.userAgentString = USER_AGENT

                webView.webChromeClient = WebChromeClient()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        requestUrl: String?,
                        favicon: Bitmap?
                    ) = Unit

                    override fun onPageFinished(
                        view: WebView?,
                        requestUrl: String?
                    ) = Unit

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString()

                        if (
                            requestUrl?.contains("abyssplayer.com/?v=", true) == true
                        ) {
                            return runCatching {
                                injectIntoAbyssPage(
                                    pageUrl = requestUrl,
                                    referer = referer
                                )
                            }.onFailure {
                                Log.e(TAG, "OPPA_HYDRAX_INJECT_FAILED = ${it.message}", it)
                            }.getOrNull()
                        }

                        /*
                         * Capture stream requests and block WebView from spending the token.
                         * We still wait a few seconds after first capture because the injected
                         * JWPlayer hook may expose the other qualities.
                         */
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
                        requestUrl: String?
                    ): Boolean = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false
                }

                val escapedUrl = htmlEscape(url)

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
                            id="oppa_abyss_frame"
                            src="$escapedUrl"
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
                    1300L,
                    2200L,
                    3400L,
                    5000L,
                    7000L,
                    9500L,
                    12000L
                ).forEach { delay ->
                    handler.postDelayed({
                        clickWebView()
                    }, delay)
                }

                handler.postDelayed({
                    finish()
                }, MAX_WAIT_MS)
            }

            runCatching {
                setup()
            }.onFailure {
                Log.e(TAG, "OPPA_HYDRAX_SETUP_FAILED = ${it.message}", it)
                finish()
            }
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
        connection.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val html = connection.inputStream.bufferedReader().use { it.readText() }

        connection.headerFields
            .filterKeys { it?.equals("Set-Cookie", true) == true }
            .values
            .flatten()
            .forEach { cookie ->
                runCatching {
                    CookieManager.getInstance().setCookie(pageUrl, cookie)
                }
            }

        runCatching {
            CookieManager.getInstance().flush()
        }

        val injected = when {
            html.contains("<head>", true) ->
                html.replaceFirst(
                    Regex("<head>", RegexOption.IGNORE_CASE),
                    "<head>$HOOK_JS"
                )

            else -> "$HOOK_JS$html"
        }

        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
        ).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*"
            )
        }
    }

    private fun isStreamUrl(requestUrl: String?): Boolean {
        val value = requestUrl?.lowercase().orEmpty()

        return value.contains("sssrr.org/sora/") ||
            value.contains("/sora/") ||
            value.contains(".m3u8") ||
            value.contains(".mp4")
    }

    private fun guessLabel(url: String): String {
        val value = url.lowercase()

        return when {
            value.contains("/1421764806/") -> "720p"
            value.contains("/677311756/") -> "360p"
            value.contains("1080") -> "1080p"
            value.contains("720") -> "720p"
            value.contains("480") -> "480p"
            value.contains("360") -> "360p"
            else -> "Auto"
        }
    }

    private fun qualityScore(
        label: String,
        url: String
    ): Int {
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

    private fun String.toAbsoluteStreamUrl(): String {
        val value = trim()

        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", true) -> value
            value.startsWith("/") -> "https://abyssplayer.com$value"
            else -> value
        }
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private const val TAG = "OppaDrama"

    private const val HOOK_JS = """
<script>
(function() {
  if (window.__oppaHooked) return;
  window.__oppaHooked = true;

  function cap(value) {
    try {
      if (window.oppaBridge && window.oppaBridge.capture) {
        window.oppaBridge.capture(String(value));
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
          cap("OPPA_SOURCE|" + label + "|" + type + "|" + abs(file));
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
        if (src) cap("OPPA_VIDEO|" + abs(src));
      }
    } catch(e) {
      cap("OPPA_ERROR|" + e.message);
    }
  }

  try {
    var oldFetch = window.fetch;
    if (oldFetch) {
      window.fetch = function() {
        try { cap("OPPA_FETCH|" + abs(arguments[0])); } catch(e) {}
        return oldFetch.apply(this, arguments);
      };
    }
  } catch(e) {}

  try {
    var oldOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, requestUrl) {
      try { cap("OPPA_XHR|" + abs(requestUrl)); } catch(e) {}
      return oldOpen.apply(this, arguments);
    };
  } catch(e) {}

  cap("OPPA_HOOK_READY|" + location.href);

  inspectPlayer();
  setTimeout(inspectPlayer, 300);
  setTimeout(inspectPlayer, 700);
  setTimeout(inspectPlayer, 1200);
  setTimeout(inspectPlayer, 2000);
  setInterval(inspectPlayer, 1000);
})();
</script>
    """

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
}
