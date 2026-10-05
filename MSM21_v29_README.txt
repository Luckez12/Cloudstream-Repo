MSM21 Cloudstream v29 — Clean names for all known MSM servers

Install: replace the repository MSM21 folder with the included MSM21 folder, build
and install provider version 29. Source code only, not a compiled .cs3 plugin.
Based on v27, with v26 cancellation and v25 quiet extraction WebView retained.
Does not include v28 metadata-name changes.

Restored optional name mapping, case-insensitive:
rpmpl/RPM -> RPM
seekp/Seek -> Seek
p2pst/P2P -> P2P
upns -> Upns
abyss -> Abyss
byses/Byse/Bysesukior -> Byse
playm/Playmate -> Playmate
playe/Player -> Player
mixdr/MixDrop -> MixDrop
dsvpl/Dsvplay/DoodStream -> DoodStream
ezpla/Ezplayer -> Ezplayer
larhu -> Larhu

These twelve website identities cover the known MSM server slugs in the supplied
logs. An alias list cannot cover unknown future brands; they are not rejected.
Unknown servers still use meaningful extractor names, else their website identity
with the initial letter capitalized. No server-name eligibility gate.

Every output source and name uses:
Server • Language • Auto / known height / Unknown
Language is omitted when absent. Master HLS must be verified to show Auto.
Internal default quality 400 is Unknown, not 400p. Option ordinals are omitted;
meaningful server digits remain. Known alias names take precedence over extractor
transport names. Already formatted values do not gain duplicate suffixes.

Examples:
RPM • MalaySub • Auto
Seek • MalaySub • Auto
P2P • MalaySub • Auto
Upns • MalaySub • Auto
Abyss • Malay Dub • Unknown
Playmate • MalaySub • 720p (only if actual quality is known)

Scope: naming only. Stream URLs, verification, fastest source selection per server,
master preference, cancellation behavior, source budgets, timeout values and
loading-WebView audio/popup mitigation remain as in v27.

Validation:
26 Kotlin regression cases, including a matrix across all twelve known aliases,
Sub/Dub languages, Auto/720p/Unknown, uppercase slugs, new unknown servers,
matching output source/name, signed URLs, Player API and stalled-probe cancellation.
Quiet WebView JavaScript fixture passed. Complete Android build and device
rendering/playback are not verified here.

Run:
KOTLIN_ABYSS_LIB_DIR=/path/to/cached/jars bash validation/msm22/run.sh
node validation/quiet-webview.test.js
