MSM21 v14 — Native Player API patch

Apply
Extract this ZIP over the repository root, retaining the MSM21/ directory.
Build with the repository's GitHub workflow, update the provider in Cloudstream,
and reload links without using an old cached source list.
This ZIP contains Kotlin source files; it is not a compiled .cs3 extension.

Changes
- Adds native PlayerX API extraction for playe (2), rpmpl (3), seekp (4),
  p2pst (5), and upns (6), including encrypted API JSON and advertised HLS tokens.
- Uses an advertised, verified master playlist when available for each mirror.
  Each mirror is attempted independently; no particular host is globally preferred.
- Adds native API/payload handling for Byse and Abyss, retaining existing
  extractor/WebView fallback when native results are rejected or unavailable.
- Rejects telemetry hosts, non-video responses and explicit unavailable responses.
  VIDEO candidates are probed with a small byte range; timeout/non-final server
  errors remain unverified rather than being treated as proven playable.
- Rejects Abyss virtual #mp4 source strings as direct playback URLs.
- Detects common human-verification screens and stops automatic player clicks.
  This does not solve or bypass CAPTCHA.
- Keeps progressive callbacks as each mirror finishes and diagnostic tag MSM21.

Validation and current limits
During the website audit before this patch was packaged, both the advertised
native HLS proxy and in-house source for PlayerX mirrors 2–6 returned a master,
child playlist and first media segment. This is network validation, not proof
of Android playback, every segment, every title, or every network connection.
For the sampled title Crazy Rich, Incredibly Broke (2026), Abyss native sources
returned 404 and Byse playback API returned 405. Those two remain unresolved.
Mixdrop 8 reported the video unavailable. Full HD was a fake/ad player entry
rather than another movie source. Neither is claimed fixed by this patch.
The seven website players are not all confirmed working in Cloudstream.

The actual injected JavaScript passed regression checks for rejecting Yandex
tracking, preserving HLS/MP4 captures, and detecting a human-verification screen.
Android/Kotlin compilation could not run: Gradle 8.12 distribution download
failed with Network is unreachable. GitHub build and on-device playback are
still required, especially playlist init fragments, seeking and older titles.
No expiring playback URLs, API response fixtures or user cookies are included.
