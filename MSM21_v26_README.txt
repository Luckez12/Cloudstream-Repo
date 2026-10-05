MSM21 Cloudstream v26 — Prompt cancellation of losing direct-video probes

Install: copy the MSM21 folder over the repository MSM21 folder, build and install
provider version 26. This archive is source code, not a compiled .cs3 plugin.
Includes v25 verified final URL / quiet WebView and v24 general source labels.

Problem:
The direct-video probe used blocking HttpURLConnection reads inside withContext(IO).
Cancelling a losing coroutine did not stop those reads. The structured coroutine
scope and explicit joins therefore waited on the loser. In the latest v25 log,
Crazy Rich had successful video probes but emitted no Abyss link when its shared
standard budget elapsed while the slow probe was still finishing.

Change:
- Bridge blocking video probes through suspendCancellableCoroutine and cancellable
  executor tasks. Cancellation completes the coroutine promptly, so losing network
  reads cannot keep selection waiting or discard an already selected winner.
- Each selection has at most three video worker threads, created only when used.
  Cancel tasks and shut down the executor in finally, including parent timeout and
  cancellation. Queued tasks are cancelled. Keep socket disconnect in worker finally.
- Cancellation requests thread interruption. An in-flight HttpURLConnection read
  may continue until its existing I/O limit or return; native socket teardown is
  not guaranteed to be instant. It no longer holds up the winning result.
- Workers check cancellation before processing results and suppress stale failure
  diagnostics after cancellation.
- Keep the exact verified final URL from v25 via checked-result metadata; preserve
  signature bytes, referer, headers, quality, extractorData and audio tracks.
- Add MSM21_V26_WINNER before cleanup to distinguish winner selection from timeout.

Preserved:
Fastest verified source per server, ready master HLS preference, strict rejection,
three concurrent probes per selection, existing 3-second connect/read limits and
provider budgets, general name formatting, loading WebView mute/popup cleanup,
and host-driven Skip Loading cancellation. No Abyss priority or extended timeout.

Limits:
This patch fixes source-selection cleanup, not remote CDN throughput, MP4 layout,
external decrypt HTTP 500 or slow native first-frame playback. A verified 512-byte
header does not promise quick playback. Device testing is still required.

Validation:
20 Kotlin fixture regression cases passed against the actual Abyss API, media
policy and formatter files. Three new real local HTTP tests cover stalled response
headers, stalled response bodies, and parent cancellation. The fast winner must
return within 800ms under a 900ms outer budget while its loser stalls for 2s;
parent cancellation must return within 800ms under a 200ms outer budget.
Existing signed URL, credential guard, metadata, DNS, naming and HLS tests pass.
The unchanged quiet WebView JavaScript fixture also passed.
Full Android provider compilation and physical-device playback are unverified.

Run fixtures:
KOTLIN_ABYSS_LIB_DIR=/path/to/cached/jars bash validation/msm22/run.sh
node validation/quiet-webview.test.js

Device check:
Retest Supergirl and Crazy Rich. For each Abyss request, compare V26_WINNER,
V23_SELECT and V24_ABYSS_RESULT with received links. Check native first-frame time
separately. Confirm movie audio, loading-screen sound, and Skip Loading behavior.
