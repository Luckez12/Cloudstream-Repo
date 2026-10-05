MSM21 Cloudstream v25 — Verified direct URL and quiet extraction WebView

Install: copy the included MSM21 folder over the repository MSM21 folder, build,
then install provider version 25. This is source code, not a compiled .cs3 plugin.
Includes the v24 general source-name formatting and v23 extraction fixes.

Direct video / Abyss:
- Reuse the exact final URL from the existing successful 512-byte video probe.
  This removes the repeated redirect lookup when native playback opens the source.
- Keep signed query bytes, playback headers, referer, quality and track metadata.
- Retain the original URL for cross-origin redirects with explicit Cookie or
  Authorization headers, avoiding moving those headers to a different origin.
- Log V25_VIDEO_PROBE status, final host, redirect, Content-Range, Accept-Ranges.
- Applies to verified direct VIDEO sources generally, without a per-server table.
- No extra request, retry, or larger timeout. Existing fastest verified source per
  server and source naming remain unchanged.
- A successful header probe does not establish first-frame speed. Remote CDN
  throughput, range handling and MP4 layout may still cause slow startup.
- The external decrypt API's HTTP 500 is not repaired by this patch. It continues
  to produce no candidates on that failure, without delaying other servers.

Loading-screen sound / popups:
- Inject quiet JS before scripts in intercepted target player/nested-player HTML
  and the wrapper. Handle head tags with attributes, preserve those attributes.
- Force HTML audio/video mute and volume zero; retain play() so extraction works.
- Route WebAudio destination connections through a zero-gain node.
- Disable JS popup permission, return null from window.open, reject native
  WebView onCreateWindow (including popups initiated by synthetic taps).
- Reinjection is idempotent. Pause/destroy cleanup is idempotent; individual
  failures cannot prevent later cleanup steps. No global pauseTimers or device
  audio changes. Native movie playback is outside this WebView.
- Cancellation from the host (including Skip Loading when it cancels extraction)
  retains the existing cleanup callback. No changes to Cloudstream Skip Loading.
- Quiet hooks cover instrumented documents; arbitrary third-party iframe realms
  not intercepted by the existing probe may need further device investigation.

Evidence from latest v24 device log:
Abyss Supergirl first frame 4.223s; Crazy Rich Incredibly Broke 35.550s.
The slow request redirected from sssrr.org to trycloudflare.com, read ~4 MB over
22.679s, then reopened from offset 0. This supports eliminating repeated redirects,
but does not prove that redirects explain all startup delay or the sound source.

Validation:
17 Kotlin fixture cases passed (actual Abyss API, media policy and label code).
Tests include a real local HTTP redirect, signed query preservation, metadata,
credential guard, strict verification, cancellation, DNS rejection and fast choice.
Node VM quiet-hook test passed: existing/future media, forced mute, WebAudio,
popups, idempotence and realm isolation. These tests do not run Android WebView.
Complete Android provider build and real device playback are not verified here.

Device check:
Build/install v25; test Abyss on the same two titles. Confirm source naming,
first-frame time, sound during loading, normal movie audio, and Skip Loading.
Send a fresh full diagnose log if startup/sound persists. Look for
MSM21_V25_VIDEO_PROBE / WEBVIEW_POPUP_BLOCKED / WEBVIEW_DESTROY.

Run fixtures:
KOTLIN_ABYSS_LIB_DIR=/path/to/cached/jars bash validation/msm22/run.sh
node validation/quiet-webview.test.js
