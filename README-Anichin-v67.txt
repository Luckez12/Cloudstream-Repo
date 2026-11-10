Anichin v67 cumulative source patch. Extract into repository root; overwrite.
Includes previous Cloudflare/Rumble/Dailymotion and master-first fixes.

OK.ru now returns inline master immediately. If no inline master exists but
MP4 is available, emit MP4 and return without metadataUrl/API requests.
Only when neither is available are metadataUrl/API recovery requests attempted,
inside one shared 4-second budget. If metadataUrl returns HLS, skip API.
Per-server master cancellation and original server priority are retained.
No poster changes. Kotlin syntax parsing passed. Full Gradle compilation and
Android playback are unverified; previous Gradle download was network-blocked.
