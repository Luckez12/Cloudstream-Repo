Anichin v64 — Cloudflare & Rumble source patch

INSTALL
Extract into the existing Cloudstream-Repo repository root; overwrite the four
Anichin files. Build/publish the extension using the usual repository workflow.
This ZIP contains source changes, not a compiled .cs3 or APK.

CHANGES
- Stop adding cs_latest query parameters and no-cache headers to site requests.
- Cache nonempty first-page homepage sections for 60 seconds per section;
  simultaneous requests for the same section reuse the result. Later pages,
  search, details and player data are not cached by this change.
- Keep ordinary page requests parallel. Coordinate confirmed Cloudflare
  challenges through one lock, including expired-clearance recovery.
- Reuse clearance cookies with the runtime WebView User-Agent when available.
- Retry clearance refreshed by another request before invoking the solver.
- Do not erase clearance or repeat requests after unrelated HTTP/network errors.
- Rumble: request exact-video API metadata first; retain embed parser fallback.
- Check master/auto HLS before fixed playlists; try higher fixed qualities first.
- Skip checking fixed qualities below the existing 720p minimum. The native
  master remains intact, including all server-provided adaptive variants.
- Provider version raised from 63 to 64; diagnostics use ANICHIN_V64.

VALIDATION
- Kotlin syntax parsing passed for all Anichin .kt files.
- Live website check: canonical homepage and previous cache-busted homepage
  returned the same 20 latest-release links.
- Live Rumble check: 100.000 Years of Refining Qi episode 393 exact-video API
  supplies the auto master; master returned HTTP 200 and contained 1080p/720p
  variants. New ordering selects that master before fixed alternatives.
- API member names used by the homepage cache were checked against upstream
  MainAPI.kt. Clearance cookie/User-Agent behavior was checked against upstream
  CloudflareKiller.kt.
- Full Gradle build could not run: Gradle distribution download was blocked
  by the execution environment's Java network/proxy access. No compiled-build
  or Android/TV playback verification is claimed.

LIMITS
First-time or expired Cloudflare challenges still depend on the website and
WebView. Homepage content can remain cached for up to 60 seconds locally, plus
any caching performed by the website. Changes reduce redundant work; they do
not guarantee a specific load time. Poster/TV background changes are excluded.
