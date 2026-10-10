# Yomi v5 — Sub-only server callbacks and master-first extraction

Scope: Yomi loadLinks scheduling and per-server HLS selection only. Homepage/details and subtitle transport behavior from v4 are unchanged.

- Start a server task immediately when the watch scanner discovers its Sub embed.
- Every server emits independently; joining tasks only completes the overall loadLinks operation.
- Verify HLS masters from a successful #EXTM3U response containing master tags, never from the filename alone.
- Stop that server's registered extractor or disposable WebView after its first verified master. Emit exactly that master and discard buffered media/MP4 candidates. Skip all remaining extraction routes for that server.
- Continue other servers. If no verified master is found within that server's bounded routes, emit its deduplicated fallback candidates.
- Sub only. Captions remain limited to English, Malay, Indonesian.

MSM21 in this repository provided the reference for callbacks inside independent server tasks. Native MegaPlay uses the existing repository transport implementation. WebView concurrency remains capped at two, with cancellation cleanup.

Validation: live watch DOM contract was verified for v3/v4. v5 incremental scanner behavior is checked with the actual embedded JavaScript against a six-server DOM fixture. Master-before-fallback routing and local producer cancellation are reviewed in source. ZIP contains only the Yomi module at repo-root paths. Android build/device playback have not been verified; the previous build attempt could not download Gradle due to unavailable network access.

Known v4 diagnostic issues remain for future patches: empty Trending, some details timing out, English subtitle 403, and unresolved mirrors. This patch does not claim to fix those problems.

Useful logs: YOMI_SERVER_MASTER action=emitted_stop_server; YOMI_SERVER_RESULT master=... fallbacks=...; YOMI_PLAYER_DONE.
