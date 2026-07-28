# ZenTen Vault (Android, Kotlin) — Proof of Concept

Native Android proof of concept for ZenTen Vault, a deterministic key-management
app.

## Cross-platform product handoff

The implementation-agnostic product documentation for iOS, Android, or any other
client is in [`docs/`](docs/README.md):

- [`docs/product-brief.md`](docs/product-brief.md) — product purpose, users, scope, and vocabulary
- [`docs/product-requirements.md`](docs/product-requirements.md) — complete product behavior
- [`docs/user-experience.md`](docs/user-experience.md) — screens, flows, states, and accessibility
- [`docs/data-and-backup-contract.md`](docs/data-and-backup-contract.md) — portable data and remote-backup contract
- [`docs/security-privacy-quality.md`](docs/security-privacy-quality.md) — security, reliability, and acceptance requirements

The files below document this Android PoC and its implementation history:

- [`architecture-overview.md`](architecture-overview.md) — architecture & invariants
- [`feature-specification.md`](feature-specification.md) — feature source material F-001…F-021
- [`ui-ux-flows.md`](ui-ux-flows.md) — UI source material
- [`implementation-plan.md`](implementation-plan.md) — phased Android PoC plan

## Requirements

- JDK 17 (host unit tests auto-provision a JDK 21 toolchain — Quartz ships
  JVM 21 bytecode; enabled via the Foojay resolver in `settings.gradle.kts`)
- Android SDK with platform 36 + build-tools 36.0.0
- `local.properties` with `sdk.dir=<your Android SDK path>` (not committed)

## Build & test

```bash
./gradlew :app:testDebugUnitTest   # unit tests (JUnit 5)
./gradlew :app:assembleDebug       # build debug APK
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Package layout

```
com.zenten.vault
├── VaultApplication / MainActivity
└── ui/
    ├── VaultDestinations / VaultNavGraph
    ├── theme/            Material 3 theme
    ├── components/       shared UI (PlaceholderScreen for now)
    ├── root/             entry + setup-state routing (stub)
    ├── onboarding/       welcome + ssh setup (stub)
    ├── accounts/         list + detail (stub)
    └── newaccount/       create sub-account (stub)
```

Later phases add `crypto/`, `data/`, `network/`, `domain/`, `background/`, and
`state/` per `implementation-plan.md §3`.
