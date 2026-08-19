# Luckez12 CloudStream Repo

CloudStream extension repository maintained by Luckez12.

## Repository URL

Add this URL to CloudStream:

`https://raw.githubusercontent.com/Luckez12/Cloudstream-Repo/main/repo.json`

## Providers

This repository contains independent CloudStream provider modules. Each provider lives in its own top-level folder and has its own `build.gradle.kts`.

Current modules include:

- 4KHDHub
- Anichin
- KissKH
- MovieBox
- OneTouchTV
- OppaDrama
- PencuriMovie

## Development

Provider-specific metadata and dependencies should stay inside the corresponding provider module whenever practical.

GitHub Actions in this repository are intended only for repository CI/CD tasks such as building, validating, packaging, and publishing the CloudStream extensions.
