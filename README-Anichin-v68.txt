Anichin v68 — poster selection and TV background (cumulative source patch)
Extract into repository root; overwrite. Includes v67 and previous server and
Cloudflare fixes. Earlier patches need not be applied separately.

POSTER CHANGES
- Explicit selector priority: series details/sidebar artwork before general
  images. Selectors are queried separately, avoiding HTML-order priority.
- Reject empty, data:, javascript:, about:, null/undefined values and common
  placeholder/blank/loading filenames. Continue to alternate lazy/srcset/src
  values and alternate elements instead of selecting an unusable first image.
- OG image is an alternative after scoped artwork; generic artwork fallback
  is restricted to main content rather than arbitrary sidebar widgets.
- If episode artwork is absent, fetch its linked same-host series page once,
  with a 4-second ceiling. This is conditional, not an extra request per load.
- Populate backgroundPosterUrl for both series and movie details, using the
  selected poster as fallback artwork. Preserve existing posterHeaders.
- Episode metadata inherits the selected poster as before.
- No HEAD/GET image probes on routine browsing: HTTP 404 or expired image URLs
  are not detected by filename validation. No automatic CW database migration.

VALIDATION
All Anichin Kotlin sources passed syntax parsing. Live pages for Wu Dong Qian
Kun Season 7, Lord of the Ancient God Grave (episode 499), and The Great Ruler
3D (episode 98) yielded series artwork using the new selector order. All three
selected image URLs returned HTTP 200 and image/webp. These checks use current
website artwork, not the URL stored in the user's old Continue Watching cards.
The exact reason those old CW cards were empty remains unconfirmed.
Full Gradle compilation and Android/TV playback remain unverified; the prior
build could not download Gradle in this environment. Source ZIP only.

EXISTING CW
Opening details again and playing an episode may refresh metadata. Old CW
artwork is controlled by Cloudstream persistence and is not guaranteed to be
replaced by a provider update. Do not clear watch history just to test this.
