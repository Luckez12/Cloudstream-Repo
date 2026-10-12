# AniwatchTV.ro v1

Self-contained Cloudstream module for https://aniwatchtv.ro/. Adds catalogue, search, details, published episodes and Sub-only player extraction. It does not modify Yomi.

## Implementation

- Categories: Latest Episodes, Most Popular, Movies. Search uses the site's published WordPress ?s= route. Parse actual film_list/flw-item cards, excluding explicitly Dub-only cards.
- Resolve an episode search result to its actual anime detail link before loading details. Read metadata from anisc-detail/anisc-info, and the anime ID from the site's embedded data.
- Fetch the actual OtakuThemes episode/list/{animeId} JSON endpoint. Use only returned ep-item links/IDs/numbers; no generated episode counts.
- Fetch episode/servers?episodeId={id}. Decode returned data-hash using the same Base64 operation as the site's player. Select data-type=sub only.
- Native MegaPlay.su JWPlayer extraction plus native Vidmoly file/packed-source inspection and registered Cloudstream extractors. Independently emit each server's result; verify playlist contents before calling a link Auto. Stop that server after the first verified master. If no master is found, emit its collected fallback links.
- Display actual host names, Sub, and Auto/Unknown/resolution. Source attribution uses the server site instead of grouping everything under the provider name.
- Captions: English/Malay/Indo only. Validate HTTP and caption content, preserve headers, and retry a previously failed URL when different headers arrive. Never invent missing caption URLs.
- Log HTTP, episode counts, Sub server counts, master/fallback results and caption errors. Do not log full transport URLs or cookie values.

## Live evidence — 2026-10-12 Malaysia time

Homepage, three category routes, search, detail, episode, episode-list API and server-list API returned HTTP 200. Search for Black Clover returned three film cards. The sampled Black Clover Season 2 returned two published episodes. Episode 2 returned MegaPlay.su and Vidmoly as Sub servers; the native MegaPlay page contained one HLS URL and tracks: []. Its HLS returned HTTP 200 with #EXTINF segments: a media playlist, not a master. External English/Malay/Indo captions are therefore not claimed available for this sample; subtitles may be burned into the video, which has not been watched here.

Only returned next-page signals enable pagination. Movie page 2 is explicitly linked in HTML. No endpoint or selector is borrowed from the similarly named Aniwatch/HiAnime sites.

## Validation and limits

Source wiring and ZIP root checked. No Android/Kotlin compilation or on-device playback validation was possible in this environment. Playlist access is not proof of continuous playback. Vidmoly playback has not been verified. Unknown future hosts use registered Cloudstream extractors and may require a dedicated extractor later; there is no universal WebView fallback. Per-server native/registered timeouts and master verification prevent indefinite waits, but slow hosts can still delay the overall loadLinks completion.

The ZIP starts at AniwatchTV/ and relies on this repository's existing automatic module discovery. No root build or workflow files are included.
