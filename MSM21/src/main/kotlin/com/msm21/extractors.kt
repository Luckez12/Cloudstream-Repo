package com.msm21

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
import java.util.concurrent.atomic.AtomicReference
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
    private const val FINISH_AFTER_FIRST_STREAM_MS = 2_500L

    data class CapturedStream(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
        val mimeType: String? = null,
        val captureSource: String = "unknown",
        val confidence: Int = 0
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
        val context = MsmRuntime.resolveContext()
        if (context == null) {
            Log.e(TAG, "MSM21_WEBVIEW_CONTEXT_MISSING")
            return@withContext emptyList()
        }
        Log.i(TAG, "MSM21_WEBVIEW_PROBE target=${safeUrl(url)}")

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            val streams = linkedMapOf<String, CapturedStream>()
            val activePlayerUrl = AtomicReference(url)
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
                    .groupBy { canonicalMediaKey(it.url) }
                    .values
                    .mapNotNull { group ->
                        group.maxWithOrNull(
                            compareBy<CapturedStream> { it.confidence }
                                .thenBy { qualityScore(it.label, it.url) }
                        )
                    }
                    .sortedWith(
                        compareByDescending<CapturedStream> {
                            qualityScore(it.label, it.url)
                        }.thenByDescending { it.confidence }
                            .thenBy { it.label }
                    )
            }

            fun finish() {
                handler.post {
                    if (continuation.isActive) {
                        val result = sortedResult()
                        Log.i(TAG, "MSM21_WEBVIEW_FINISH target=${safeUrl(url)} streams=${result.size}")
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
                headers: Map<String, String>,
                mimeType: String? = null,
                forcePlayable: Boolean = false,
                captureSource: String = "unknown",
                confidence: Int = 0,
                finishSoon: Boolean = false
            ) {
                val playerUrl = activePlayerUrl.get()
                val fixedUrl = rawUrl
                    ?.trim()
                    ?.toAbsoluteUrl(playerUrl)
                    ?.normaliseCapturedMediaUrl()
                    ?.takeIf { forcePlayable || isStreamUrl(it) }
                    ?: return

                val fixedHeaders = headers.toMutableMap().apply {
                    put("User-Agent", get("User-Agent") ?: USER_AGENT)
                    put("Accept", get("Accept") ?: "*/*")
                    put("Referer", get("Referer") ?: playerUrl)

                    if (keys.none { it.equals("Origin", ignoreCase = true) }) {
                        originOf(playerUrl)?.let { put("Origin", it) }
                    }

                    val mediaCookie = runCatching {
                        CookieManager.getInstance().getCookie(fixedUrl)
                    }.getOrNull().orEmpty()
                    val playerCookie = runCatching {
                        CookieManager.getInstance().getCookie(playerUrl)
                    }.getOrNull().orEmpty()
                    val mergedCookie = listOf(
                        get("Cookie").orEmpty(),
                        mediaCookie,
                        playerCookie
                    ).filter { it.isNotBlank() }
                        .joinToString("; ")
                    if (mergedCookie.isNotBlank()) put("Cookie", mergedCookie)
                }

                val key = canonicalMediaKey(fixedUrl)
                val candidate = CapturedStream(
                    label = label.trim().ifBlank { guessLabel(fixedUrl) },
                    url = fixedUrl,
                    headers = fixedHeaders,
                    mimeType = mimeType,
                    captureSource = captureSource,
                    confidence = confidence
                )
                val existing = streams[key]

                if (existing == null || candidate.confidence > existing.confidence) {
                    Log.i(
                        TAG,
                        "MSM21_WEBVIEW_CAPTURE source=$captureSource confidence=$confidence " +
                            "label=${candidate.label} url=${safeUrl(fixedUrl)} mime=${mimeType.orEmpty()}"
                    )
                    streams[key] = candidate
                } else if (existing.mimeType.isNullOrBlank() && !mimeType.isNullOrBlank()) {
                    streams[key] = existing.copy(mimeType = mimeType)
                }

                // JWPlayer config URLs may be placeholders containing fragment metadata.
                // Do not terminate the WebView merely because one of those appeared.
                // Wait for the actual browser media request, which carries the URL and
                // headers that the site itself really used.
                if (finishSoon) scheduleFinishSoon()
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
                                headers = defaultHeaders(activePlayerUrl.get()),
                                mimeType = parts[2].takeIf { it.isNotBlank() },
                                captureSource = "jwplayer-config",
                                confidence = 10,
                                finishSoon = false
                            )
                        }
                    }

                    clean.startsWith("MSM_VIDEO|") -> {
                        val file = clean.removePrefix("MSM_VIDEO|")
                        addStream(
                            label = guessLabel(file),
                            rawUrl = file,
                            headers = defaultHeaders(activePlayerUrl.get()),
                            captureSource = "video-element",
                            confidence = 35,
                            finishSoon = false
                        )
                    }

                    clean.startsWith("MSM_FETCH_MEDIA|") ||
                        clean.startsWith("MSM_XHR_MEDIA|") -> {
                        val parts = clean.split("|", limit = 3)
                        if (parts.size >= 3) {
                            val mime = parts[1].trim()
                            val file = parts[2].trim()
                            if (isPlayableContentType(mime)) {
                                addStream(
                                    label = guessLabel(file),
                                    rawUrl = file,
                                    headers = defaultHeaders(activePlayerUrl.get()),
                                    mimeType = mime,
                                    forcePlayable = true,
                                    captureSource = if (clean.startsWith("MSM_FETCH_MEDIA|")) "fetch-media" else "xhr-media",
                                    confidence = 70,
                                    finishSoon = true
                                )
                            }
                        }
                    }

                    clean.startsWith("MSM_FETCH|") ||
                        clean.startsWith("MSM_XHR|") -> {
                        val file = clean.substringAfter('|')
                        if (isStreamUrl(file)) {
                            addStream(
                                label = guessLabel(file),
                                rawUrl = file,
                                headers = defaultHeaders(activePlayerUrl.get()),
                                captureSource = if (clean.startsWith("MSM_FETCH|")) "fetch" else "xhr",
                                confidence = 55,
                                finishSoon = true
                            )
                        }
                    }
                }
            }

            fun clickWebView() {
                // A JWPlayer config entry is not proof that the browser has actually
                // requested the media. Keep clicking until we capture a real network
                // request or response.
                if (streams.values.any { it.confidence >= 70 }) return

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
                    headers = headers,
                    captureSource = "webview-request",
                    confidence = 100,
                    finishSoon = true
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
                    Bridge { value ->
                        handler.post { handleBridgeCapture(value) }
                    },
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

                        if (shouldInjectPlayerPage(requestUrl, url)) {
                            return runCatching {
                                injectIntoPlayerPage(
                                    pageUrl = requestUrl,
                                    referer = referer,
                                    onFinalUrl = { finalUrl ->
                                        activePlayerUrl.set(finalUrl)
                                        Log.i(TAG, "MSM21_WEBVIEW_PLAYER_URL ${safeUrl(finalUrl)}")
                                    }
                                )
                            }.onFailure { error ->
                                Log.e(TAG, "MSM21_WEBVIEW_INJECT_ERROR target=${safeUrl(requestUrl)} error=${error.javaClass.simpleName}:${error.message}")
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
                .onFailure { error ->
                    Log.e(TAG, "MSM21_WEBVIEW_SETUP_ERROR error=${error.javaClass.simpleName}:${error.message}")
                    finish()
                }
        }
    }

    private fun injectIntoPlayerPage(
        pageUrl: String,
        referer: String,
        onFinalUrl: (String) -> Unit
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
        val finalPageUrl = connection.url.toString()
        onFinalUrl(finalPageUrl)

        connection.headerFields
            .filterKeys { it?.equals("Set-Cookie", true) == true }
            .values
            .flatten()
            .forEach { cookie ->
                runCatching {
                    CookieManager.getInstance().setCookie(finalPageUrl, cookie)
                }
            }
        runCatching { CookieManager.getInstance().flush() }
        Log.i(TAG, "MSM21_WEBVIEW_INJECT from=${safeUrl(pageUrl)} final=${safeUrl(finalPageUrl)} bytes=${html.length}")
        connection.disconnect()

        // HttpURLConnection follows redirects before returning the HTML. WebView, however,
        // still associates this intercepted response with the original iframe URL. A base
        // element keeps relative scripts, API calls and media paths pointed at the final host.
        val baseTag = if (Regex("<base\\s", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            ""
        } else {
            "<base href=\"${htmlEscape(finalPageUrl)}\">"
        }
        val injected = if (html.contains("<head>", true)) {
            html.replaceFirst(
                Regex("<head>", RegexOption.IGNORE_CASE),
                "<head>$baseTag$HOOK_JS"
            )
        } else {
            "$baseTag$HOOK_JS$html"
        }

        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }

    private fun shouldInjectPlayerPage(requestUrl: String, targetUrl: String): Boolean {
        fun normalise(value: String): String = value
            .substringBefore('#')
            .trim()
            .trimEnd('/')

        return normalise(requestUrl).equals(
            normalise(targetUrl),
            ignoreCase = true
        )
    }

    private fun String.normaliseCapturedMediaUrl(): String {
        val value = trim()
        if (!value.startsWith("http", ignoreCase = true)) return value

        // URI fragments are never sent in HTTP requests. Abyss/JWPlayer can expose
        // source strings such as `video.mp4#mp4/.../480p/h264`. Passing the fragment
        // through to Media3 can make diagnostics look like a signed URL even though
        // the browser actually requests only the object URL before '#'.
        return if (value.contains('#')) value.substringBefore('#') else value
    }

    private fun canonicalMediaKey(raw: String): String {
        return raw.trim().substringBefore('#')
    }

    private fun originOf(rawUrl: String): String? {
        return runCatching {
            val uri = URI(rawUrl)
            val scheme = uri.scheme ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            val port = uri.port
            if (port == -1 || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)) {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        }.getOrNull()
    }

    private fun isPlayableContentType(raw: String?): Boolean {
        val value = raw?.lowercase().orEmpty()
        return value.contains("application/vnd.apple.mpegurl") ||
            value.contains("application/x-mpegurl") ||
            value.contains("application/dash+xml") ||
            value.contains("video/mp4") ||
            value.contains("video/webm")
    }

    private fun isStreamUrl(rawUrl: String?): Boolean {
        val value = rawUrl?.lowercase().orEmpty()
        if (value.isBlank()) return false
        if (BLOCKED_MEDIA_PARTS.any(value::contains)) return false

        return value.contains("/sora/") ||
            value.contains(".m3u8") ||
            value.contains(".mpd") ||
            value.contains(".mp4") ||
            value.contains(".m4v") ||
            value.contains("/manifest/") ||
            value.contains("/master.m3u") ||
            value.contains("playlist.m3u")
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

    private fun safeUrl(value: String): String {
        return runCatching {
            val uri = URI(value)
            "${uri.host.orEmpty()}${uri.path.orEmpty().take(90)}"
        }.getOrDefault(value.substringBefore('?').take(120))
    }

    private const val TAG = "MSM21_TRACE"

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

      var sources = document.querySelectorAll("source[src]");
      for (var s = 0; s < sources.length; s++) {
        var sourceUrl = sources[s].src || sources[s].getAttribute("src") || "";
        var sourceType = sources[s].type || sources[s].getAttribute("type") || "";
        if (sourceUrl) {
          cap("MSM_SOURCE|Auto|" + sourceType + "|" + abs(sourceUrl));
        }
      }
    } catch(e) {}
  }

  function mediaType(contentType) {
    contentType = String(contentType || "").toLowerCase();
    return contentType.indexOf("application/vnd.apple.mpegurl") >= 0 ||
      contentType.indexOf("application/x-mpegurl") >= 0 ||
      contentType.indexOf("application/dash+xml") >= 0 ||
      contentType.indexOf("video/mp4") >= 0 ||
      contentType.indexOf("video/webm") >= 0;
  }

  try {
    var oldFetch = window.fetch;
    if (oldFetch) {
      window.fetch = function() {
        var requestUrl = "";
        try {
          requestUrl = arguments[0] && arguments[0].url ? arguments[0].url : arguments[0];
          cap("MSM_FETCH|" + abs(requestUrl));
        } catch(e) {}

        return oldFetch.apply(this, arguments).then(function(response) {
          try {
            var contentType = response && response.headers ?
              (response.headers.get("content-type") || "") : "";
            var finalUrl = response && response.url ? response.url : requestUrl;
            if (mediaType(contentType)) {
              cap("MSM_FETCH_MEDIA|" + contentType + "|" + abs(finalUrl));
            }
          } catch(e) {}
          return response;
        });
      };
    }
  } catch(e) {}

  try {
    var oldOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, requestUrl) {
      try {
        this.__msmRequestUrl = requestUrl;
        cap("MSM_XHR|" + abs(requestUrl));

        if (!this.__msmMediaHooked) {
          this.__msmMediaHooked = true;
          this.addEventListener("readystatechange", function() {
            try {
              if (this.readyState !== 2 && this.readyState !== 4) return;
              var contentType = this.getResponseHeader("content-type") || "";
              if (mediaType(contentType)) {
                var finalUrl = this.responseURL || this.__msmRequestUrl || "";
                cap("MSM_XHR_MEDIA|" + contentType + "|" + abs(finalUrl));
              }
            } catch(e) {}
          });
        }
      } catch(e) {}
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
