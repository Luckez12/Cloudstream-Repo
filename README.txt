MSM21 v17 — Literal WebView HTML Injection Fix

V17 CHANGE
Fix IllegalArgumentException: Illegal group reference during HTML injection.
Escape the full replacement payload using Regex.escapeReplacement before
Regex.replaceFirst, preserving all JavaScript dollar signs and backslashes.
Only this injection fix and extension version markers changed in production
relative to v16. The v16 hook/extraction behavior is retained.

V17 VALIDATION
PASS: JVM regression reproduces the original failure using the actual hook.
PASS: quoted replacement preserves the actual hook bytes exactly.
PASS: uppercase head, no-head fallback, dollar/backslash payload and replacing
only the first head.
PASS: execute the hook extracted from the JVM-produced HTML in Node VM:
not-found/verification detection, Abyss capture, HLS and tracker/blob rejection.
Run from patch root: sh validation/run_checks.sh (requires JDK 17+ and Node).
These are JVM replacement + JS integration checks, not an Android build.
Full Android build and phone playback remain unverified here.

PHONE TEST
Install built version 17. Fresh-load the title, test Abyss and Byse 7, then
export Full Timeline. Check that MSM21_WEBVIEW_INJECT_ERROR with Illegal group
reference is gone. A successful injection does not prove working playback.
Playe 2, Mixdr 8 and Full HD are excluded from the test plan as requested.
Abyss custom segmented transport is still NOT implemented. Byse may still
require verification or have unavailable content. No successful playback claim.

V16 FUNCTIONALITY RETAINED — Guarded Abyss Direct Candidate + PlayerX/Byse Diagnostics

INSTALL
Extract at ROOT of Cloudstream-Repo-main; merge/replace MSM21/.
Build through the repository workflow and update the MSM21 extension.
Complete MSM21 folder from v15 is included, plus one new helper.

CHANGES
- Accept only the observed HTTPS storage.googleapis.com/mediastorage/...mp4
  source with #mp4/r2/1/.../size/quality/h264 metadata as a direct candidate.
  Strip the fragment from HTTP URLs; retain expected object size.
- This candidate must pass HTTP 206 + exact Content-Range + 512 bytes at both
  start and end, matching advertised size, with a media signature at the start.
  Errors/timeouts/incorrect ranges are rejected, never emitted as unverified.
  Other virtual formats remain unsupported. Capture alone is not success.
- PlayerX logs invalid ID, API HTTP failure, populated source-field count and
  enabled-candidate count. No API bodies, signed URLs or keys are logged.
  Existing source extraction for servers 3–6 is preserved.
- Byse logs details/settings failures; distinguishes 404 from verification.
  405 is labelled method_not_allowed instead of assuming its cause. Clear
  stale frame entries before each details refresh. Keep existing CAPTCHA logic.
- Browser diagnostics distinguish Page not found/video unavailable from human
  verification. These observations do not bypass verification or repair files.
- Extension version and loaded-version marker are now 17.

LIMITS — THIS IS NOT FULL ABYSS TRANSPORT SUPPORT
The site's custom segmented transport is NOT implemented. This is a guarded
fallback ONLY when the browser-advertised object independently works through
ordinary HTTP ranges. If custom transport is required, Abyss can still fail.
No working Android Abyss playback is claimed. Playe 2 is diagnosed, not claimed
repaired. Byse still fails when unavailable or verification is required.
No CAPTCHA solving, fabricated attestation, proxy server or app modification.
Mixdr 8/Full HD are excluded from testing as requested. Existing provider paths
are not removed. data-nume=fake remains excluded.

VALIDATION
PASS: actual JS capture hook tested with Node VM for Byse not-found, human
verification, Abyss virtual capture, HLS preservation, tracker/blob rejection
and avoiding not-found classification for FAQ text without an error heading.
Android compilation: NOT VERIFIED — no Gradle/Kotlin toolchain or full repo
available in this workspace. Android playback/range probes: NOT VERIFIED.
Browser readyState=4 from review is not proof of Cloudstream native playback.

TEST AFTER BUILD
Fresh-load Crazy Rich, Incredibly Broke (2026); verify installed version 17.
Test Abyss and Byse 7; export Full Timeline.
Look for MSM21_V16_ABYSS_DIRECT, MSM21_V16_PLAYERX, MSM21_V16_BYSE/PAGE_STATE.
If Abyss emits a source, test first frame and seek near the end.
PASS LINKS/range_media_verified does not prove uninterrupted playback.
Do one regression check of RPM, Seek, P2P and Upns.
Do not test Playe 2, Mixdr 8 or Full HD for this patch.

REPRODUCE HOOK CHECK
From extracted patch root: node validation/test_hook.cjs
