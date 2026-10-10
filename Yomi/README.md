# Yomi — stage 1

Homepage test provider for the existing CloudStream repository. Module discovery in settings.gradle.kts is automatic; no root build or workflow changes are needed.

Rows: Trending, Top Rated, New Releases and Movies, using Yomi's observed Browse links. Reads `main a.anime-card`, title from h3/image alt, poster from img src and actual anime href. Links are restricted to Yomi anime paths and duplicates are removed within each row.

Only the first catalogue page is enabled. Load More, search, details, episodes and playback are not implemented at this stage. Clicking a card reports that details are pending rather than pretending playback is ready.

Validation: live browser inspection showed 24 Trending cards and 24 Movies cards with the expected selectors, titles, URLs and images. The embedded Next.js data also includes initialAnime and initialHasMore. Direct HTTP from this environment returned 403, so raw HTTP behaviour on Android remains unverified. No Android Gradle build or device test was run. Source/path checks only; the first app test must confirm the rows and posters load.

Logs: YOMI_HOME records section, HTTP status and parsed item count, without full page dumps or account data.
