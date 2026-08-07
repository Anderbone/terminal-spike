# Project rules

- Use native Kotlin Android only.
- Use Jetpack Compose for surrounding application UI and a custom/native renderer for the terminal hot path.
- Never introduce per-cell or per-line Compose state in the terminal hot path.
- Do not add unreviewed network, analytics, AI, cloud, advertising, sync, or account features.
- Do not add a GPL or AGPL dependency without an explicit architecture and licensing decision.
- Dependency versions must be stable: no alpha, beta, RC, preview, snapshot, or nightly versions.
- Record every new direct dependency, its purpose, source, licence, and notice obligations in `docs/DEPENDENCIES.md` and `THIRD_PARTY_NOTICES.md` as applicable.
- Preserve the terminal buffer, viewport, batching, workload, smoke, and future performance tests.
- Run unit tests, lint, and a build before declaring work complete.
- Do not broaden scope without an explicit task.
- Do not copy source from Termius, LobiShell, ConnectBot, Termux, or other terminal applications.
- Keep SDK paths, signing material, secrets, build outputs, APKs, and machine-specific IDE files out of Git.
