MSM21 Cloudstream v23 — Abyss wrapper routing, labels and source validation

Apply: copy the MSM21 directory over the repository MSM21 directory, then build/install plugin version 23.

Changes:
- Recognize abyss.msmbot.club in the existing datas/decrypt path used by MSM JS.
- Log V23_ROUTE and V23_ABYSS_START/result without logging payloads or signed URLs.
- Format Playmate as Playmate • MalaySub; retain original website labels in discovery logs.
- Validate captured WebView sources; reject DNS failures instead of emitting them as playable.
- Select one verified source per mirror as soon as one is ready. Prefer a master among already completed checks. Cancel remaining candidate probes. This does not terminate other server pipelines or remove Cloudstream's overall loadLinks wait.
- Use the same 3-second HTTP request timeout for PlayerX/Byse/Abyss APIs; no server-specific extension.

Validation:
12 Kotlin fixture regression cases passed (wrapper domains, deceptive domains, decrypt JSON, signatures, rejection, cancellation, names, fast-source selection, DNS).
The tests compile the policy/API/label components against fixtures, not the complete Android plugin.

Limits:
The episode website denied live inspection with HTTP 403. API 404/502 endpoint causes remain unresolved; no replacement endpoint was guessed.
Abyss now reaches the missing route, but actual datas availability, decrypt-service response and playback remain unverified on the user's device.
Independent range checks can still consume their existing bounded network budget when a direct-video probe cannot cancel immediately.
