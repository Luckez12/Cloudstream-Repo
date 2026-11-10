Anichin v65 — OK.ru and Dailymotion fix (cumulative patch)

Extract into the existing repository root and overwrite the Anichin files.
Includes v64 Cloudflare/Rumble fixes: v64 need not be applied separately.
Source ZIP only; use the normal build/publish workflow to produce the extension.

NEW IN V65
- OK.ru emits discovered MP4 links to the provider collector before optional
  metadata probes, preserving them if the parent pipeline later times out.
  Duplicate MP4 callbacks are suppressed.
- Optional metadata probes are limited to 1.5 seconds when videos exist;
  recovery probes get up to 4 seconds when no videos have been found.
  Parent cancellation still propagates; no background jobs are launched.
- OK.ru master and best eligible MP4 are both kept for manual selection.
  This does not implement automatic playback failover.
- OK.ru pipeline budget: 9 seconds. Dailymotion pipeline budget: 13 seconds,
  covering wrapper lookup plus metadata. Dailymotion HTTP metadata timeout:
  6 seconds. Extractor limit: 9 seconds. Preferred group limit: 14 seconds.
  These are upper limits, not mandatory delays; successful work returns sooner.
- Dailymotion native auto HLS master stays intact to preserve audio tracks.
- Server priority order and existing minimum video quality remain unchanged.
- Provider and diagnostic version updated to 65.

VALIDATION
- All Anichin Kotlin sources passed tree-sitter Kotlin syntax parsing.
- Fresh OK.ru embed metadata for episode 393 of 100.000 Years of Refining Qi
  contained ondemandHls plus videos[name=full].
- Fresh Dailymotion metadata for the same episode contained qualities.auto HLS.
- Full compilation and Android/TV playback have not been verified. The earlier
  Gradle build attempt was blocked at distribution download by Java networking.
- CDN playback availability depends on network/IP and URL expiry; this patch
  does not prove that the tested streams play on every device.

SCOPE
No poster or TV-background changes are included. The previous v64 changes
retain homepage sections for 60 seconds, reuse Cloudflare clearance with the
runtime WebView UA, coordinate challenge recovery, remove cache-busting site
requests and prioritize Rumble exact-video API/master HLS.
