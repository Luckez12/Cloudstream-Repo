MSM21 v12 — All Website Players / Media Filtering

Install
Extract this ZIP into the provider repository root and replace MSM21 files.
Commit/build, update the MSM21 extension in Cloudstream, and use a fresh link load
(clear old link cache or restart the app). Keep the existing JVM 11 root fix.
The plugin diagnostic marker is MSM21_PLUGIN_LOADED version=12.

Changes
- Try every real AJAX player option, including Abyss. Remove all host preference
  lists, the three-WebView cap, and the rule that skipped unresolved JS mirrors
  after another server succeeded.
- Process options independently: four native pipelines and two live WebViews at
  most. Emit each completed mirror immediately; do not wait for a whole WebView
  batch before callbacks. Link loading timeout increases from 60 to 90 seconds
  to allow every real option to be attempted. This is a cap, not a minimum wait.
- Reject Yandex Metrica/known telemetry hosts from native callbacks, JS bridge,
  WebView interception and output selection. Inspect URL paths instead of entire
  queries when recognising media extensions. A tracker query containing an MP4
  URL no longer counts as a video request.
- All WebView capture-map updates now run on the main thread.
- Discover native playlist sources advertised in player HTML/inline or unpacked
  scripts before built-in extraction. No guessed master paths or host changes.
- Verify HLS playlist contents. Choose a real master inside each mirror when
  available and label it Auto; retain non-master alternatives when no verified
  master exists. Preserve original media headers, referer and extractor data.
  No MP4 source is relabelled as HLS.

Live website audit — Crazy Rich, Incredibly Broke (2026)
https://pencurimoviesubmalay26.site/movies/crazy-rich-incredibly-broke-2026/
Eight AJAX options returned embed URLs and their HTML pages returned HTTP 200:
1 Abyss: abyss.msmbot.club
2 Playe: playerx.player4me.online
3 RPMPL: playerx.rpmplay.online
4 Seekp: playerx.seekplays.online
5 P2PST: playerx.p2pstream.online
6 UPNS: playerx.upns.live
7 Byses: bysesukior.com
8 Mixdr: mixdrop.top -> mxdrop.top
The Full HD entry is data-nume=fake; it is excluded as before, not counted as a
ninth real AJAX option. UPNS/RPM HTML includes Yandex counters 98086865/99122264,
matching the false video URLs in the user's playback trace.
HTTP 200 embed HTML proves discovery/reachability, not video playback.

Validation and limits
The actual injected JavaScript was executed in Node against regression fixtures:
Yandex URLs were rejected from JW config, fetch, and even spoofed video MIME;
master HLS, MP4 and extensionless HLS response capture remained available.
Static integration checks and ZIP integrity passed.
Gradle compilation could not run: Gradle 8.12 distribution download failed with
Network is unreachable. Build success is NOT verified here.
Android WebView playback and all eight servers remain to be tested in Cloudstream.
Generic native discovery does not decode every external/encrypted player API;
those mirrors rely on the existing WebView path and can still time out, expire,
return 403 or fail at the host. MP4 byte-level playback is not verified by this
patch. A playable source must actually exist on the server; master HLS cannot
be produced if the host only offers progressive video.

Next diagnostic
Freshly load this title after updating. Send Full Timeline (including LINKS),
looking for MSM21_V12_OPTION_DONE, MSM21_V12_SELECT and MSM21_WEBVIEW_DONE.
A failed option must not stop the others. mc.yandex.ru must not appear as a source.
