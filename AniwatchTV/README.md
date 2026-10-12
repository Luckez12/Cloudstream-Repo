# AniwatchTV.ro v2 — server coverage repair candidate

## v2 diagnostic changes

Input diagnose_Full_timeline_FullTrace_20261012_174931.txt (installed v1): Mushoku Tensei found seven unique Sub URLs and returned zero links; Kanata kara found two MegaPlay.buzz routes and returned zero links; Black Clover returned one working MegaPlay.su stream and no Vidmoly stream.

- Retain every returned Sub server option's data-server-name and index, rather than discarding choices when their host or URL matches. Exact duplicate URLs share extraction work and fan out to all original options.
- Keep distinct MegaPlay mal/s-2/TCDN/BCDN URLs as independent requests. Do not remove query parameters or manufacture substitute routes.
- Add MegaPlay.buzz native source/decryption/signing transport from this repository's previously inspected MegaPlay implementation. Get the actual data-id from the returned embed page.
- Try native JW/packed file extraction on the other returned hosts; registered extraction remains available. Add disposable WebView media-request capture as the final fallback for every returned Sub URL, including ZokoAnime, GogoAnime.me.uk, VidTube and Vidmoly. It is a generic actual-player capture, not a verified dedicated API implementation for those three new hosts.
- Four bounded WebView slots; 18 seconds per player, 45 seconds including queue time per route. Stop that route's renderer after a verified master. Callback results from successful routes arrive independently; overall completion still waits for the remaining routes.
- Source/display names include the actual host identity and original site choice. Log each choice/index/result, including zero-link routes and WebView timeout.

Live server-list API for episode 10388 returned eight Sub options (seven unique URLs), including duplicate HD-1/HD-2 mal links and separate s-2/default/TCDN/BCDN links. Live shell access to MegaPlay.buzz, ZokoAnime, GogoAnime.me.uk and VidTube timed out. Their current extraction/playback is unverified. No missing links are labelled as working.

Self-contained Cloudstream module for https://aniwatchtv.ro/. Adds catalogue, search, details, published episodes and Sub-only player extraction. It does not modify Yomi.

## Implementation

- Categories: Latest Episodes, Most Popular, Movies. Search uses the site's published WordPress ?s= route. Parse actual film_list/flw-item cards, excluding explicitly Dub-only cards.
- Resolve an episode search result to its actual anime detail link before loading details. Read metadata from anisc-detail/anisc-info, and the anime ID from the site's embedded data.
- Fetch the actual OtakuThemes episode/list/{animeId} JSON endpoint. Use only returned ep-item links/IDs/numbers; no generated episode counts.
- Fetch episode/servers?episodeId={id}. Decode returned data-hash using the same Base64 operation as the site's player. Select data-type=sub only.
- Native MegaPlay.su JWPlayer and MegaPlay.buzz source extraction, generic file/packed-source inspection, registered Cloudstream extractors and actual-player WebView fallback. Independently emit each server's result; verify playlist contents before calling a link Auto. Stop that server after the first verified master. If no master is found, emit its collected fallback links.
- Display actual host names, Sub, and Auto/Unknown/resolution. Source attribution uses the server site instead of grouping everything under the provider name.
- Captions: English/Malay/Indo only. Validate HTTP and caption content, preserve headers, and retry a previously failed URL when different headers arrive. Never invent missing caption URLs.
- Log HTTP, episode counts, Sub server counts, master/fallback results and caption errors. Do not log full transport URLs or cookie values.

## Live evidence — 2026-10-12 Malaysia time

Homepage, three category routes, search, detail, episode, episode-list API and server-list API returned HTTP 200. Search for Black Clover returned three film cards. The sampled Black Clover Season 2 returned two published episodes. Episode 2 returned MegaPlay.su and Vidmoly as Sub servers; the native MegaPlay page contained one HLS URL and tracks: []. Its HLS returned HTTP 200 with #EXTINF segments: a media playlist, not a master. External English/Malay/Indo captions are therefore not claimed available for this sample; subtitles may be burned into the video, which has not been watched here.

Only returned next-page signals enable pagination. Movie page 2 is explicitly linked in HTML. No endpoint or selector is borrowed from the similarly named Aniwatch/HiAnime sites.

## Validation and limits

Source wiring, captured server identity mapping, embedded JavaScript syntax and ZIP root checked. No Android/Kotlin compilation or on-device playback validation was possible in this environment. Playlist access is not proof of continuous playback. Vidmoly and newly covered hosts still need device logs. The WebView fallback is not a guarantee that every player can be extracted; hosts with different request shapes or backend denials may still need dedicated work. Slow hosts can delay overall loadLinks completion.

The ZIP starts at AniwatchTV/ and relies on this repository's existing automatic module discovery. No root build or workflow files are included.
