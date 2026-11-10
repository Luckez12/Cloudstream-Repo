Anichin v66 — master-first correction (cumulative source patch)

Extract into repository root and overwrite. Includes v64/v65 changes with the
v65 OK.ru dual-output behavior replaced. Earlier patches need not be applied.

Behavior per server:
- Emit only the master HLS when present. No companion MP4 choice.
- When a master callback arrives, cancel and join remaining discovery work
  belonging to that same server pipeline. Other server families continue.
- OK.ru: return immediately when inline metadata or HTML supplies HLS.
- Only if inline HLS is absent, probe metadataUrl then API inside ONE shared
  time budget: 1.5 seconds if MP4 candidates exist, otherwise 4 seconds.
- Skip API if metadataUrl already supplied HLS. Emit MP4 only when master
  discovery completes or reaches its local timeout without finding HLS.
- The previously increased pipeline limits remain upper bounds for slow or
  unsuccessful discovery (OK.ru 9 seconds, Dailymotion 13, preferred group 14).
  A successful server stops earlier; these are not mandatory waiting periods.
- Existing Rumble master-first and Dailymotion native auto master behavior,
  Cloudflare changes, homepage cache and server priority are retained.
- Poster/TV-background changes are not included.

Validation:
All Anichin Kotlin sources passed syntax parsing. Source paths were reviewed
for inline master, metadataUrl master, API master and no-master fallback.
Cancellation uses structured coroutines: the server pipeline is cancelled and
joined, with no detached work. Full Gradle compilation and Android playback
remain unverified; the prior build was blocked downloading Gradle.
This ZIP is source, not a compiled extension.
