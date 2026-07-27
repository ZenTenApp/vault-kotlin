# ZenTen Vault (Android, Kotlin) — Proof of Concept

Native Android reimplementation of ZenTen Vault, a deterministic key-management
app. See the specs and plan:

- [`architecture-overview.md`](architecture-overview.md) — architecture & invariants
- [`feature-specification.md`](feature-specification.md) — features F-001…F-021
- [`ui-ux-flows.md`](ui-ux-flows.md) — screens & navigation
- [`implementation-plan.md`](implementation-plan.md) — the phased build plan (PoC scope)

## Status: Phase 0 — Project bootstrap ✅

Scaffold is in place and builds:

- Gradle 8.9 wrapper, AGP 8.7.3, Kotlin 2.0.21, JDK 17
- Jetpack Compose + Material 3, single-activity Navigation-Compose
- Hilt (DI), Coroutines, kotlinx.serialization
- DataStore, Security-Crypto (EncryptedSharedPreferences), WorkManager
- OkHttp, BouncyCastle 1.79, `fr.acinq.secp256k1`
- Placeholder screens for every PoC route; smoke unit test

## Requirements

- JDK 17
- Android SDK with platform 35 + build-tools 35.0.0
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

## Next: Phase 1 — Cryptographic core

Highest-risk, highest-priority. Implement `SshKeyCodec`, `Seedify`,
`NostrKeyDerivation`, and `Nip44` with golden test-vector parity before any
downstream work depends on them. Note the PoC uses **bcrypt_pbkdf = 1 round**.
