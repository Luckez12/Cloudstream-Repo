# AnimeXTV v3 — Sub, masters and mirrors

Source patch for the existing CloudStream repository. Archive paths start with `AnimeXTV/`. No root Gradle or YAML changes.

## Changes

- Keeps the existing AniList/MAL catalog and Sub-only episodes.
- MegaPlay native extraction remains supported.
- Adds native Vidnest extraction for AniWave, MegaPlay and Anitaku, using the same public backend as both Vidnest page layouts. Requests each backend once.
- Adds native FrameXTV provider discovery and source extraction. Only active Sub providers are queried, at most 12 with three concurrent requests. Backend fallback aliases use the actual returned provider name and duplicate URLs are removed.
- Adds TryEmbed's public embed/bootstrap/stream_data flow, anonymous cookies/nonce handling, ready/idle providers, direct URLs and stream tokens. This native flow is not confirmed against the live bootstrap endpoint here (403). Generic CloudStream extraction is retained if no native TryEmbed links are returned.
- Rejects explicit Dub, non-Japanese audio labels and Hindi-quality sources even when a backend incorrectly labels them Sub.
- Preserves upstream referers, source headers, FrameXTV's player proxy and subtitles.

## Playlist selection and names

For each returned server group, probes HLS playlists with a two-second timeout and at most three concurrent probes. A parsed master is preferred over an API URL that merely names a master. Known master URLs remain candidates if a probe is inaccessible, since network access can differ between this environment and the Android app.

Keeps complete masters rather than expanding them into individual resolutions. When masters are available, redundant individual renditions are omitted. Additional distinct masters in the same group get `Fallback 1`, `Fallback 2`, etc. If no master is available, known-resolution or unknown sources remain usable.

Names:

- `MegaPlay · Sub · Auto`
- `Vidnest · AniWave · Sub · Auto`
- `TryEmbed · Astra · Fallback 1 · Sub · Auto`
- `Server · Sub · 720p`
- `Server · Sub · Unknown`

There is exactly one final quality label. Adaptive masters always use Auto, even if the API labels them 1080p. Fallbacks are ordinary alternative CloudStream links; automatic retry/selection remains controlled by the app. Identical MegaPlay paths with renewed HMAC tokens are deduplicated. Anonymous TryEmbed session headers are not attached to direct external video URLs.

Requests are bounded, and logs contain short server summaries only, including emitted links and verified master counts. No HTML dumps, full stream URLs, tokens or cookies are logged. TryEmbed stops further lazy requests near its deadline while retaining sources already collected.

## Validation

Kotlin JVM 11 compilation with CloudStream API stubs passed. Fixture tests cover catalog/search/detail and MAL fallback, Sub-only metadata and payloads, actual MegaPlay response decryption, token identity, live Vidnest response decoding, FrameXTV proxy construction and fallback alias deduplication, Dub/Hindi rejection, TryEmbed bootstrap/parser and header handling, master priority, multiple-master fallback, rendition suppression and Auto/resolution/Unknown names.

Live public Vidnest AniWave response and master playlist were retrieved; its master contains 360p/480p/720p/1080p renditions. A live FrameXTV master playlist was also retrieved. Vidnest Anitaku returned a source but its CDN playlist could not be verified here. Vidnest MegaPlay API returned 502 for the sample. TryEmbed embed loaded with the original-site Referer; POST bootstrap returned 403 here. Its parser and handshake have fixture coverage only.

The user's v1 app log confirmed two MegaPlay playback first frames and 360–1080p master parsing. Android/GitHub Gradle build and v3 playback in the app have not been run here. New server availability depends on anime, episode and the current upstream service. Neither a retrieved master nor a first frame guarantees uninterrupted playback.
