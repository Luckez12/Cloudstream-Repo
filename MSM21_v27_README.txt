MSM21 Cloudstream v27 — Dynamic server names and explicit resolution suffix

Install: copy the included MSM21 folder over the repository MSM21 folder, build
and install provider version 27. Source patch only, not a compiled .cs3 plugin.
Includes the previous v26 probe cancellation and v25 quiet WebView improvements.

Display:
- No legacy server-name alias map, per-server formatting gate or per-host branding
  table. Do not infer Playmate from playm, RPM from rpmpl, or Seek from seekp.
- Use meaningful extractor source/name as available metadata. Otherwise retain the
  parsed website server identity. Preserve its spelling/case and meaningful digits.
- Player API and native HLS discovery now receive the website label, instead of
  assigning fixed display brands by endpoint. Their fallback identity is host data.
- Custom extractor name overrides derive their identity from their own mainUrl.
- Strip the option ordinal after a recognized language label, as before.
- Output: Server • Language • Resolution; omit Language when it is absent.
- Both link.source and link.name contain the same complete formatted text, so
  source lists that display either field receive the resolution suffix.
- Auto only for a verified master HLS playlist. A media playlist or direct video
  uses known height (e.g. 720p/1080p) or Unknown. An old label saying Auto alone
  is not evidence of a master playlist.
- Internal Qualities.Unknown (400) is not presented as 400p. Do not guess a height
  from numbers embedded in server identities such as Host720.
- Reformatting does not duplicate language or resolution suffixes.

Examples (case comes from actual metadata):
Website rpmplMalaySub 3, no separate identity -> rpmpl • MalaySub • Auto
Extractor name Playmate, website playmMalaySub 10 -> Playmate • MalaySub • Auto
Extractor NewHost 720p, website futureMalay Dub 8 -> NewHost • Malay Dub • 720p
Direct Abyss source without a known height -> abyss • MalaySub • Unknown

Scope:
Display changes only. Protocol host support/routing is still needed for extraction;
these host lists do not map names or control formatting. Extraction order, probes,
verification, stream URLs, timeouts and existing playback headers are unchanged.
Abyss/CDN first-frame delay is not addressed by this naming patch.

Validation:
24 Kotlin fixture cases passed against Abyss API, media policy, formatter and
Player API. Includes no legacy aliases, new servers, language/ordinal parsing,
master Auto, numeric rendition, Unknown, no 400p default, idempotent formatting,
matching source/name, metadata/URL preservation and v26 cancellation regression.
Player API identity is tested through a valid encrypted fixture response.
Quiet WebView JavaScript fixture passed. Full Android provider compilation and
actual device rendering/playback have not been verified here.

Run:
KOTLIN_ABYSS_LIB_DIR=/path/to/cached/jars bash validation/msm22/run.sh
node validation/quiet-webview.test.js

Device check:
Confirm provider v27. Check every Sources row ends with Auto, known height or
Unknown, and unfamiliar servers use the same format. Website slugs may remain
visible when no distinct extractor name exists; this is intentional without aliases.
