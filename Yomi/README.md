# Yomi v3 — full provider implementation candidate

Implemented catalogue homepage with isolated row failures, search via the site's published search URL, metadata from Yomi JSON-LD, episode lists from the rendered watch page, and link extraction for the actual embeds discovered by that page.

## Verified live site contract (2026-10-11 Malaysia time)

Sample: https://yomi.to/watch/the-apothecary-diaries-season-3-195516/1

- Watch page showed episode links `/watch/anime-195516/1` through `/12`.
- Six player buttons use `title="Server N ..."`.
- Embed hosts observed: ani.pm, megaplay.buzz, tryembed.us.cc, flixera.co, cinextream.cc, nontongo.win.
- Server 4 and 6 are Sub only; the sample's primary server also marked Dub unavailable. The provider discovers availability from the actual player controls and never fabricates Dub URLs.
- Details pages returned an unavailable message in this browser. The provider uses server-rendered metadata and the working watch episode list instead.

## Runtime

The extension remains self-contained in `Yomi/`. Android context is resolved through the same runtime pattern already used by other modules in this repository. A maximum of two disposable WebViews render JavaScript pages. Details rendering returns once episode links appear; server discovery has a 28-second bound and each media capture an 18-second bound. Views and callbacks are destroyed on completion or coroutine cancellation. No CAPTCHA solver, credential input, or certificate bypass is added.

MegaPlay reuses the repository's existing native transport implementation. Other embeds try registered Cloudstream extractors, then capture ordinary HLS/MP4 requests from a disposable player WebView. Signed URLs and request headers pass to the native player. Exposed captions are restricted to English, Malay and Indonesian. Sources that require unsupported media transports may still return no links and need device logs.

Homepage remains first-page only; the site's catalogue pagination request has not been verified. The episode list is the list displayed by Yomi, not proof every episode/server currently plays.

## Validation and limitations

Live DOM selectors and the six Sub embed URLs were checked. Embedded JavaScript syntax and ZIP root paths were checked. Attempted `:Yomi:compileDebugKotlin`; it stopped before compilation because the Gradle distribution download failed with `Network is unreachable`. No successful Android build or Cloudstream device playback test is claimed.

This is the first complete implementation candidate, not a playback-certified release. Export full Yomi trace after opening details and trying an episode. Useful log markers: `YOMI_DETAIL`, `YOMI_PLAYER`, `YOMI_NATIVE_FALLBACK`, `YOMI_SERVER_RESULT`, `YOMI_SERVER_FAILED`, `YOMI_PLAYER_DONE`.
