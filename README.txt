MSM21 v15 — Abyss / Byse extraction corrections

INSTALL
Extract at the ROOT of Cloudstream-Repo-main; merge/replace MSM21/.
ZIP paths start with MSM21/, not Cloudstream-Repo-main/MSM21/.
Build through the repository GitHub workflow and update the MSM21 extension.

CHANGES
- Preserve the actual JSON number/string type when deriving Abyss native tokens.
  The bundled player MD5 hashes Number inputs differently from UTF-8 strings.
- Read Byse embed settings and report human verification explicitly.
- Supply X-Embed-Origin, X-Embed-Referer and X-Embed-Parent context.
- Inject the media-capture hook into the exact nested frame advertised by Byse
  details, rather than only the outer SPA page.
- Observe already-decrypted browser source data, preserve HLS MIME types,
  and keep tracking rejection and HTTP media validation.
- Extension version and loaded-version marker are 15.
- Five PlayerX API flows from v14 are preserved.

VERIFICATION
- Numeric-size MD5 parity against actual Abyss player implementation: PASS
  for the four sizes advertised by the test title.
- Hook tests: tracking rejected, HLS/MP4 preserved, human check detected: PASS.
- Decryption hook: original result preserved, HLS MIME captured, tracking rejected: PASS.
- Android build: NOT VERIFIED. Gradle 8.12 download failed (network unreachable).
- Android playback: NOT VERIFIED; requires installed-extension testing.

IMPORTANT LIMITS — THIS IS NOT COMPLETE ABYSS/BYSE SUPPORT
Title: Crazy Rich, Incredibly Broke (2026).
Fresh native range probes, including corrected numeric-size keys, still returned
404 for all four Abyss qualities. The player also advertises segmented/custom
transport. That transport is not implemented by this patch. Invalid native
URLs remain rejected and fallback extraction is still attempted.
Byse settings returned captcha_required=true. An unattested GET playback
request returned method-not-allowed. The player uses POST when a real browser
attestation is available. This patch does not fabricate that attestation, solve
CAPTCHAs, or add a visible verification dialog. The hidden fallback may still
finish without a source when verification blocks the player.
No successful playback for server 1 or 7 is claimed.

TEST AFTER INSTALL
Fresh-load the same title, then Save Full Timeline. Check plugin version=15,
MSM21_V15_BYSE_BLOCKED, WebView frame/capture records, and actual PLAYER_READY
or playback errors. Confirm servers 2–6 still play. PASS LINKS alone is not
proof that a source plays.
