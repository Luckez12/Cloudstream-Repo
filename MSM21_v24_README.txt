MSM21 Cloudstream v24 — General source labels and Abyss outcome diagnostics

Install: copy the MSM21 folder over the existing repository MSM21 folder; build and install version 24.
Includes the v23 extraction/validation fixes.

Formatting:
- Separate website server identity from Sub/Dub language labels, including attached MalaySub and Malay Dub.
- All server labels go through the same formatting path. Unknown servers use meaningful extractor source/name when available; otherwise retain their website server identity.
- Existing short-name aliases remain for known legacy slugs; they no longer decide whether formatting applies. New servers do not require adding an alias to get a consistent format.
- Preserve meaningful numbers in server names; omit option ordinals only after a recognized language tag.
- Keep resolution on renditions, omit it for master HLS.
- Keep raw website labels in provider diagnostic logs.

Abyss:
- Preserve the shared standard budget.
- Log V24_ABYSS_RESULT reason=standard_budget_exhausted/no_verified_source/verified_source.
- This is an outcome logging improvement, not a new transport implementation or proof of working playback.

Validation:
15 Kotlin fixture regression cases passed, including unknown servers, Malay Dub, English Sub/Dub, duplicate names, resolutions, extractor identity, DNS rejection, signatures, cancellation and fast-source choice.
Complete Android plugin compilation and playback on the device have not been verified.

Latest supplied log:
V23 Abyss route runs; decrypt produced 4/5 candidates in some requests and HTTP 500 in another. No Abyss link was emitted. Some source probes timed out.
RPM first frames observed; other received links are not all playback-tested.
Whole-provider loadLinks waits for other server pipelines and took 21.958/46.526 seconds; v24 does not change that behavior.
