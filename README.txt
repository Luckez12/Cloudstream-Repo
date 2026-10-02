MSM21 v18 — Abyss Hook Readiness and Bounded Probe Diagnostics

INSTALL
Extract at ROOT of Cloudstream-Repo-main; merge/replace MSM21/.
Build using the repository workflow, update extension, verify version 18.
Full MSM21 folder is included. The v17 literal HTML injection fix is retained.

SCOPE
Abyss only: new diagnostics and readiness-aware wait budget. Existing extraction,
media validation and source selection are carried forward. This is not full
support for Abyss's custom segmented transport, and does not claim playback.
Byse's successful WebView path and other servers' wait budgets are retained.
Playe 2, Mixdr 8 and Full HD remain excluded from the test plan.

NEW SIGNALS
MSM21_V18_ABYSS_HOOK: hook execution acknowledged by the JS bridge, elapsed time.
MSM21_V18_ABYSS_VIDEO: ready/network states, numeric media error, paused flag,
source kind (none/direct/virtual/blob). State changes only; no video URLs/tokens.
MSM21_V18_ABYSS_ERROR: script resource/runtime error or rejected promise, once
per category. Error details and rejected promise values are not logged.
MSM21_V18_ABYSS_LOAD_ERROR: WebView loading failure for player page or JS file,
error code and host only. Does not classify all loading failures as bot blocks.
MSM21_V18_ABYSS_END: reason, hook_ready, capture count and elapsed time.
Reasons: hook_not_ready_timeout, ready_timeout, hard_timeout, stream_observed,
verification_required, setup_error, cancelled. stream_observed means a captured
candidate; subsequent independent media validation can still reject it.

WAIT BUDGET
Abyss: initial no-hook timeout 12 seconds; first hook-ready signal schedules up
to 10 seconds more, capped at 20 seconds from probe creation. Later ready signals
do not reset it. Hard timer and cancellation cleanup remain active.
Other servers retain their existing 8-second probe timeout.
Timing runs on Android main Handler and is subject to main-thread scheduling.

VALIDATION
PASS: actual hook reproduces v16 JVM regex error; escaped replacement preserves
its bytes. Uppercase/no-head/metacharacter/first-head cases pass.
PASS: execute hook from JVM-injected HTML: prior HLS, tracker rejection,
verification, not-found and virtual source captures pass.
PASS: Abyss ready/video/error signals, absent/blob/media-error states, redacted
payloads, error deduplication and diagnostics disabled for other-player fixtures.
Run: sh validation/run_checks.sh (JDK 17+ and Node).
Android build, Handler lifecycle/timing and device playback are NOT VERIFIED in
this workspace. JVM+JS checks do not constitute a compiled Android test.

PHONE TEST
Fresh-load Crazy Rich, Incredibly Broke (2026), wait for link loading to complete,
then export Full Timeline. Focus on Abyss and MSM21_V18_ABYSS_* signals.
Do not treat inject/hook_ready as proof the media player is ready or playable.
Briefly confirm Byse and the working four servers still start when available.
If export again says plugin_log_collector=unavailable, provider messages may be
missing. This extension patch does not repair Android's Logcat permission or
CS Diagnose collection; use original Logcat if signals are absent.
