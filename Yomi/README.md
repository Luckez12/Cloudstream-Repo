# Yomi v6 — diagnostic-driven repair candidate

Sub only. Inherits independent per-server callbacks and first verified master HLS selection from v5.

## Changes for the v4 FullTrace report

- Trending: decode the actual initialAnime data in Next RSC text chunks, including split chunks and escaped strings. Use it when HTML card selectors return no cards. Catalogue metadata is retained in a bounded 256-entry, 10-minute cache.
- Details: reuse cached catalogue metadata and known released episode counts. Finished titles use their catalogue episode count; releasing titles use nextAiringEpisode minus one; unreleased titles have no episodes. Unknown counts still use the site's rendered watch list, with a 12-second total bound including queue time. Metadata HTTP is bounded to 8 seconds and skipped when catalogue metadata is available.
- Subtitles: preserve source headers through language filtering. Native MegaPlay captions include its player Referer/Origin/User-Agent. Registered extractors get a player Referer/User-Agent when no headers are provided. WebView captions retain captured transport headers. English, Malay and Indonesian only; deduplicate subtitle URLs.
- Server discovery: start all six Sub routes directly from verified site player URL templates. Nontongo can no longer disappear because a WebView scanner skipped its last iframe transition. No guessed APIs or Dub URLs are added.
- Mirrors: initialise an actual 1280x720 WebView viewport for lazy players, deny popups, and preserve captured headers when DOM information enriches a media candidate.
- TryEmbed: reuse AnimeXTV's existing public bootstrap/nonce/cookie transport as a bounded native route before registered extraction/WebView. The first verified master stops that server's further work.

## Evidence and validation

Input diagnostic: diagnose_Full_timeline_FullTrace_20261011_013516.txt; installed plugin v4. It showed two working first frames, 50-second extraction, empty Trending, some 28–37-second metadata failures, subtitle 403 and missing/unresolved mirrors.

Live Trending data decoded to 24 valid catalogue entries (7 finished, 16 releasing, 1 unreleased) on 2026-10-11 Malaysia time. The six embed URL forms were previously read by selecting the actual Yomi server buttons. Cloudstream's official SubtitleFile API exposes a headers property; source headers are now kept instead of discarded.

Embedded JavaScript syntax, source routing and ZIP root paths are checked. Build is not validated: Gradle distribution access failed in the previous attempt and no local Kotlin compiler is present. Device playback and subtitle 403 resolution need a new trace.

## Limits

This repairs identifiable code defects and adds a normal native extraction route. It does not prove all six backends are currently playable. The existing trace does not establish the cause of TryEmbed buffering; buffering is not claimed fixed. Backend denials or unavailable videos may remain. Pagination remains first-page only because a live pagination request is not verified.
