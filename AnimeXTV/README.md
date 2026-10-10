# AnimeXTV v4 — Source names and subtitle languages

Patch for AnimeXTV v3. Paths start with `AnimeXTV/` at the repository root.

## Changes

- Vidnest mirror display labels use their source names: AniWave, Anitaku or MegaPlay. Internal extraction groups stay separate.
- Subtitle callbacks from every extraction path accept only English, Malay and Indonesian, displayed as `English`, `Malay` and `Indo`.
- Recognizes common language names/codes and locale suffixes. Unlabelled tracks may use a language code in their filename. Explicit unsupported language labels are rejected.
- Subtitle URL deduplication remains in place. Distinct subtitle URLs for the same language remain available.
- MegaPlay tracks without a language label are no longer automatically assumed to be English.
- Extension version increments from 3 to 4.

Examples: `AniWave · Sub · Auto`, `Anitaku · Sub · Auto`, `MegaPlay · Sub · Auto`.

Master HLS priority, additional-master fallback ordering, extraction endpoints, headers, catalog and episode logic are unchanged. Each stream keeps one final quality label: Auto, Unknown or a resolution.

## Validation

Source and archive checks completed against v3, including the central subtitle callback, display-only naming changes, preserved extraction/playlist selection and root paths. Language regex fixtures are checked using Java's regex engine, also used by Kotlin Regex. No CloudStream Gradle build or Android playback test was run for v4.
