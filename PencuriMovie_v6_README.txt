PencuriMovie v6 — All Servers / Verified HLS Selection

Extract into the repository root and replace the three included files.
Keep the earlier root JVM 11 build.gradle.kts fix. This ZIP does not replace it.
Provider version is now 6; run the provider build workflow and update the extension.

All discovered website choices continue to run without preferred host families.
Each choice probes its extracted HLS playlists with original referer and headers.
A real #EXT-X-STREAM-INF master with an HTTP(S) variant is labeled HLS Auto.
A verified master replaces other extracted links for that same website choice.
Without a master, the highest known quality verified media playlist is retained.
Unverified playlists are preserved; if no verified HLS exists, other links including
MP4/DASH remain available. Only explicit HTTP 404/410 probes are discarded.
Separate website choices on the same host remain separate (global exact URL dedupe).
Native HGCloud .m3u8 candidates are all exposed for playlist inspection.
Original native URLs, headers, referer, audio tracks and extractor data are preserved.
No synthetic master or guessed URL is created. No media segment/playback test is added.

PM_V6_PLAYLIST: master / media / unverified / invalid / other
PM_V6_SELECT: candidate count, master count and selected link count
PM_V6_SERVER: success and elapsed milliseconds including playlist probes

Limits: playlist probes can add latency (4-second timeout, at most 3 per host lane).
Verifying a master proves its structure, not that variants/segments play successfully.
Deleted files cannot be restored by this patch. Test Polong and older titles in-app.
Build validation was attempted but blocked by Gradle distribution download access.
Compilation and playback must therefore be confirmed in GitHub Actions and the app.
