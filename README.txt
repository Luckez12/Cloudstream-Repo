MSM21 v19 — Abyss Player Lifecycle and Bounded Startup Patch

INSTALL
Extract at Cloudstream-Repo-main ROOT; merge/replace MSM21/.
Build with your repository workflow and update the extension. Confirm version 19.
This ZIP includes the full MSM21 folder based on v18, not CS Diagnose.

SCOPE
Abyss only. Byse and all other server wait/click paths retain their existing
behavior (8-second WebView timeout). No transport rewrite is included.
Mixdr 8, Playe 2 and Full HD remain excluded from the test plan.
The v17 literal injection fix and strict Abyss media validation are retained.

BEHAVIOR
Hook-ready acknowledgment no longer starts an early 10-second finish timer.
Abyss gets at most 20 seconds from probe creation, subject to Android main
thread scheduling. Captures, verification, cancellation and setup failures
can end earlier. The budget never resets with a page/player readiness signal.
Clicks are scheduled after document load or first video element discovery.
A shared queue replaces old pending clicks at the next milestone, spaces
clicks at least 650ms apart and does not click at/after the hard deadline.
No script initialization functions, anti-bot checks or ad scripts are rewritten.

DIAGNOSTICS (MSM21_V19_ABYSS_*)
HOOK: injected JS executed; does not imply website/player readiness.
PAGE: dom (DOMContentLoaded) or loaded (window.load), once per stage/document.
PLAYER: assets_ready (SoTrym function exists), player_present (video element
exists), source_present (playlist or video exposes a source). These are
observations, not proof of playable media. Changes only, URL/token-free fields.
VIDEO: media ready/network/error states, source kind and paused flag.
ERROR / LOAD_ERROR: JS failure categories or WebView loading error/host.
END: reason, hook_ready, page_loaded, player_present, source_present, captures,
elapsed_ms. Player/source flags mean observed at least once during the probe.
Timeout reasons: hook_not_ready_timeout, page_not_loaded_timeout,
player_not_ready_timeout, source_not_ready_timeout, capture_timeout.
Other reasons: stream_observed, verification_required, setup_error, cancelled.
Captured candidates still undergo independent validation and can be rejected.

VALIDATION
Run sh validation/run_checks.sh with JDK 17+ and Node.
PASS: real hook survives JVM literal injection; prior HLS/verification/error/
virtual captures; lifecycle stages and deduplication; late player/source
appearance; empty-video distinction; diagnostics disabled for other players.
Android compilation, touch timing, device lifecycle and playback are unverified
in this workspace. Tests do not prove full Abyss transport support.

DEVICE TEST
Keep CS Diagnose latest. Fresh-load Crazy Rich, Incredibly Broke (2026), let
link loading finish, export Full Timeline. Check version 19 and PAGE/PLAYER/END.
If Abyss appears, attempt playback and record first frame; a captured link alone
is not success. Briefly check working RPM/Seek/P2P/Upns as regression checks.
Byse can be checked on a title known to work; no timeout increase is needed.
