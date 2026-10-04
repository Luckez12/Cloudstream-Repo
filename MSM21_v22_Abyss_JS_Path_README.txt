MSM21 Cloudstream v22 — Abyss extraction from working MSM JS

Based on supplied Cloudstream-Repo-main (19), MSM21 v21.
Apply this ZIP at the repository root with the existing patch workflow, build/update MSM21, and confirm installed version 22 before testing.

Changes:
- Added MsmAbyssApi: GET the Abyss embed, read datas, POST JSON {text: datas} to the same enc-dec.app/api/dec-abyss service used by MSM JS, and recursively collect candidate media URLs.
- Integrated before existing native/standard/WebView fallbacks. Existing fallbacks still run when no verified API candidate is selected.
- Preserves website labels, signed URL query strings and the embed page Referer. Uses normal Cloudstream User-Agent.
- Candidates use the existing media policy: master/media HLS validation or the existing 512-byte video signature probe. Unknown/unverified results are not accepted from this new API path. Prefer a verified master when available; emit at most one selected API source per Abyss mirror.
- No new Abyss timeout constant. This path uses the existing STANDARD_EXTRACTOR_TIMEOUT_MS budget and stays inside the existing MIRROR_PIPELINE_TIMEOUT_MS. Individual HTTP calls use the same 3-second timeout already used by common media checks. Provider, pipeline, extractor and WebView limits are unchanged.
- API logs identify MSM21_V22_ABYSS / MSM21_V22_ABYSS_RESULT with host, stage, status, candidate or emitted counts. Payloads and signed URLs are not logged by the new extractor.
- MediaPolicy's optional requireVerified flag defaults to false: other providers/server paths retain their previous selection behavior.

Validation:
- New extractor plus modified media policy compiled with Kotlin 2.3.0 using lightweight Android/Cloudstream API fixtures.
- Nine Kotlin regression cases passed: host matching; datas formats; nested URLs and signatures; telemetry rejection; JSON request and headers; failed responses; coroutine cancellation; common HLS checks; real local HTTP Range/video signature probes.
- Other provider files are byte-for-byte unchanged.
- This is a source patch, not a compiled .cs3 plugin. A full Android/Cloudstream Gradle build was not performed locally. Real service availability and on-device playback remain to be tested.
- Discovery checks do not prove all later video segments will play. The API path also depends on the external decrypt service being available within the common budget.

Testing:
1. Use the same title that produced a working Abyss source in MSM JS.
2. Confirm Abyss source appears and starts playback; try seeking.
3. Check existing RPM/Seek/P2P/Upns/Byse sources as a regression check.
4. If Abyss is missing or fails, capture full diagnose log including MSM21_V22_ABYSS entries. No special timeout increase is proposed.

Optional fixture test, with Java 17+ and curl:
  sh validation/msm22/run.sh
