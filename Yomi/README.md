# Yomi v8 — homepage recovery and diagnostics candidate

## v8 homepage changes

Input: diagnose_Full_timeline_FullTrace_20261012_022121.txt, installed v7. All four cached HTTP 200 responses produced zero cards and zero embedded entries. This trace does not include response bodies and cannot establish whether the original data was empty or decoding failed.

- On empty or unavailable HTTP data, revalidate once using normal Cache-Control/Pragma headers. Do not clear shared app caches.
- If still empty, render the exact category URL in a disposable WebView with LOAD_NO_CACHE. Wait for a real anime-card image, with a 12-second render bound and a 28-second total queue/render bound for the two-slot renderer. No invented alternate categories or APIs.
- Decode multiple Next text pushes per script, whitespace variants, arrays split across chunks, and later populated initialAnime arrays after an earlier empty one. Accept validated anime entries only. Recognise an unwrapped text stream when present.
- Use the verified anime-card selector without depending on the main wrapper.
- Report HTTP status, response length, card count, chunk count, decode errors, array count, and resulting items for each attempt. No full HTML, tokens, or cookies are logged.
- If every attempt fails, throw a catalogue error instead of returning PASS with an empty first-page row. The app may fail/cancel the combined homepage when a row is unavailable; this version no longer silently suppresses that failure. Pagination remains disabled.

Live browser inspection on 2026-10-12 Malaysia time: Trending rendered 24 cards and decoded 24 valid embedded entries; existing card markup is unchanged. Movies returned initialAnime: [] and no cards. Top Rated displayed "Could not load anime. Please try again."; one normal Retry changed it to "No anime found". Therefore upstream empty data is a real observed condition. This patch cannot manufacture missing upstream results. Direct shell HTTP inspection returned 404 and did not provide the device's response body.

Validation: live Trending data/selector checked, embedded catalogue scan JavaScript syntax and ZIP paths checked. No Android/Kotlin build or device verification is available. Subtitle behavior remains v7; video routing remains unchanged.

## v7 subtitle changes

Input: diagnose_Full_timeline_FullTrace_20261011_024053.txt, installed v6. English caption requests returned HTTP 403 in both tested players. The diagnostic does not expose outgoing transport headers and does not establish the precise denial cause.

- Validate discovered English/Malay/Indo caption files before callback: successful HTTP and WebVTT, SRT, or ASS content are required. Reject HTML/error bodies.
- Deduplicate attempts by URL plus headers, rather than URL alone. A failed request can be retried when another extractor supplies different headers. A successful caption is emitted once, retaining the headers that passed validation.
- Three independent caption workers continue after a video's master HLS stops its producer. Each request has a four-second bound. Video callbacks do not wait for caption validation.
- Log YOMI_SUBTITLE_READY / YOMI_SUBTITLE_REJECT with language, host, status or timeout, and header names. Do not log caption URLs, cookies, or header values.
- Existing six-server Sub-only routes and master HLS behavior are unchanged. Alternatives come only from those existing extraction routes; no alternate caption endpoints are invented.

This change rejects unusable subtitles and allows improved-header candidates to succeed. It does not guarantee a replacement when every server denies the caption. A successful validation request is also not proof of later playback access. Native MegaPlay headers have not been confirmed against the live original player: browser inspection timed out.

Validation: source wiring and ZIP root paths checked. No local Kotlin compiler is available; build and device caption playback remain unverified.

Sub only. Inherits independent per-server callbacks and first verified master HLS selection from v5.

## Changes for the v4 FullTrace report

- Trending: decode the actual initialAnime data in Next RSC text chunks, including split chunks and escaped strings. Use it when HTML card selectors return no cards. Catalogue metadata is retained in a bounded 256-entry, 10-minute cache.
- Details: reuse cached catalogue metadata and known released episode counts. Finished titles use their catalogue episode count; releasing titles use nextAiringEpisode minus one; unreleased titles have no episodes. Unknown counts still use the site's rendered watch list, with a 12-second total bound including queue time. Metadata HTTP is bounded to 8 seconds and skipped when catalogue metadata is available.
- Subtitles: preserve source headers through language filtering. Native MegaPlay captions include its player Referer/Origin/User-Agent. Registered extractors get a player Referer/User-Agent when no headers are provided. WebView captions retain captured transport headers. English, Malay and Indonesian only; v7 replaces URL-only deduplication with validated requests.
- Server discovery: start all six Sub routes directly from verified site player URL templates. Nontongo can no longer disappear because a WebView scanner skipped its last iframe transition. No guessed APIs or Dub URLs are added.
- Mirrors: initialise an actual 1280x720 WebView viewport for lazy players, deny popups, and preserve captured headers when DOM information enriches a media candidate.
- TryEmbed: reuse AnimeXTV's existing public bootstrap/nonce/cookie transport as a bounded native route before registered extraction/WebView. The first verified master stops that server's further work.

## Evidence and validation

Input diagnostic: diagnose_Full_timeline_FullTrace_20261011_013516.txt; installed plugin v4. It showed two working first frames, 50-second extraction, empty Trending, some 28–37-second metadata failures, subtitle 403 and missing/unresolved mirrors.

Live Trending data decoded to 24 valid catalogue entries (7 finished, 16 releasing, 1 unreleased) on 2026-10-11 Malaysia time. The six embed URL forms were previously read by selecting the actual Yomi server buttons. Cloudstream's official SubtitleFile API exposes a headers property; source headers are now kept instead of discarded.

Embedded JavaScript syntax, source routing and ZIP root paths are checked. Build is not validated: Gradle distribution access failed in the previous attempt and no local Kotlin compiler is present. Device playback and subtitle 403 resolution need a new trace.

## Limits

This repairs identifiable code defects and adds a normal native extraction route. It does not prove all six backends are currently playable. The existing trace does not establish the cause of TryEmbed buffering; buffering is not claimed fixed. Backend denials or unavailable videos may remain. Pagination remains first-page only because a live pagination request is not verified.
