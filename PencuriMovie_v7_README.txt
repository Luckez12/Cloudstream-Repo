PencuriMovie v7 - Native HLS Discovery Before Extractor Fallback

Extract at repository root; replace included files. Retains v6 selection and compact
names (DoodStream 2, HGCloud 3 - Auto). Keep the root JVM 11 build fix.

Every non-direct player now has a bounded discovery pass BEFORE loadExtractor.
It scans explicit hls/file/src/source/url fields in HTML, inline scripts and unpacked
player code, plus video/source elements. It probes up to eight native advertised URLs,
three at a time, for real HLS master structure or media playlists.
Verified native masters end extraction for that player; if none is found, native
media playlists are kept and the existing Cloudstream extractor still runs.
The v6 per-choice selection then prefers verified master over fixed HLS and MP4.
No guessed CDN/master paths, manufactured playlists, arbitrary JS execution or
cross-domain video-ID substitution is introduced. All discovered server choices
remain eligible; there are no preferred host families.

IMPORTANT LIMITS
This is an extractor-pipeline discovery upgrade, not a complete rewrite of every
host-specific API decoder. It cannot find sources available only through an unknown
encrypted API or executable external player script. Existing host extractors remain
responsible for those formats. Dood may only offer VIDEO/MP4, and a native master
cannot be promised for all hosts. Dood vide0.net was Cloudflare-blocked in the audit;
Dsvplay returned a gateway failure. Live Dood HLS availability remains unconfirmed.
Discovery can add up to eight seconds per player attempt, within existing overall
timeouts. Playback and segment availability are not guaranteed by playlist structure.

DIAGNOSTICS
PM_V7_NATIVE_PAGE: player host and HTTP status
PM_V7_NATIVE_BLOCKED: challenge detected; proceed to existing extractor
PM_V7_NATIVE_SCAN: explicit native HLS candidate count
PM_V7_NATIVE_PROBE: HTTP status, master/media structure
PM_V7_NATIVE_DONE: verified native result counts
PM_V6_SELECT / PLAYLIST / SERVER: final selection and timing as before
Signed URLs, cookies and tokens are not logged.

VALIDATION
Source reviewed and ZIP integrity checked. Full Gradle validation was attempted but
blocked because this environment cannot download the Gradle distribution.
Compile in the GitHub workflow, update the extension, then run fresh (uncached)
loadLinks for Polong, Nobody and old titles. Compare native discovery logs and
actual first frame / seek playback. Do not infer all hosts provide HLS from version 7.
