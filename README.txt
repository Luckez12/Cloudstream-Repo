MSM21 v21 — All Servers, Standard Budgets and Clean Source Names

INSTALL
Replace the repository MSM21/ folder with MSM21/ from this ZIP.
If merging, delete the obsolete file from earlier experiment patches:
MSM21/src/main/kotlin/com/msm21/MsmAbyssDirectSource.kt
ZIP extraction cannot delete an existing repository file.
Build using your workflow, update extension and verify installed version 21.
Use this instead of v20. No CS Diagnose change is included.

SCOPE
All real website options participate in ordinary discovery and extraction,
including Abyss. There is no server-name/host exclusion list.
The website's fake Full HD advertising option is still ignored as before.
Existing bounded concurrency is retained: options are processed in one request,
with queues limiting simultaneous network/WebView work.
A successful native path does not suppress unresolved options' WebView fallback.

No special Abyss timer, lifecycle diagnostics, direct-object/range experiment
or custom transport is included. Abyss uses the ordinary native/extractor and
WebView fallback paths. Its experimental native /sora generator is not restored.
Valid ordinary media links can be emitted; custom #mp4/ metadata is not treated
as proof that a direct video object exists. The common validation policy applies.

BUDGETS
Every mirror retains the standard native pipeline budget of 18 seconds,
standard extractor attempt budget of 5 seconds and WebView budget of 8 seconds.
These cover different stages; they are not an 8-second total loading guarantee.
No server receives an extra/extended timeout. Existing first-capture finish,
cancellation, verification handling and media validation are retained.
Network, semaphore queueing and remaining fallback options affect total time.

SOURCE NAMES
RPM • MalaySub / Seek • MalaySub / Upns • MalaySub /
P2P • MalaySub / Byse • MalaySub.
HLS masters omit redundant Auto and don't claim a specific rendition height.
Known rendition resolutions are retained, e.g. Byse • MalaySub • 720p.
Website option labels remain raw in discovery/diagnostics for attribution.
Unmapped server names keep their original label.

VALIDATION
PASS: server exclusion/helper references and Abyss diagnostic timers removed.
PASS: PlayerX/Byse API and native discovery retained from the cleaned v20 source.
PASS: JVM literal injection and JS Byse verification/error/HLS/tracker fixtures.
Run: sh validation/run_checks.sh (JDK 17+ and Node).
Android build and device playback have not been performed in this workspace.

DEVICE CHECK
Fresh-load a title; confirm version 21 and cleaned source names.
Abyss should participate in the ordinary pipeline, with no V20_OPTION_SKIP and
no V19_ABYSS readiness/20-second timeout logs. If it returns no valid media,
ignore it; there is no special retry or additional waiting for that server.
Test the available working sources and export Full Timeline to compare timing.
Playe 2, Mixdr 8 and Full HD remain outside the playback test plan.
