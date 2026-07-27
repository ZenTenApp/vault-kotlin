# ZenTen Vault (Android, Kotlin) — Proof of Concept

Native Android reimplementation of ZenTen Vault, a deterministic key-management
app. See the specs and plan:

- [`architecture-overview.md`](architecture-overview.md) — architecture & invariants
- [`feature-specification.md`](feature-specification.md) — features F-001…F-021
- [`ui-ux-flows.md`](ui-ux-flows.md) — screens & navigation
- [`implementation-plan.md`](implementation-plan.md) — the phased build plan (PoC scope)

## Status: Phase 1 — Cryptographic core (in progress)

Scaffold builds; the Nostr crypto library is chosen and parity-gated:

- Gradle 8.13 wrapper, AGP 8.11.2, Kotlin 2.2.21, JDK 17
- Jetpack Compose + Material 3, single-activity Navigation-Compose
- Hilt (DI), Coroutines, kotlinx.serialization
- DataStore, Security-Crypto (EncryptedSharedPreferences), WorkManager
- OkHttp, BouncyCastle 1.79 (SSH side), `fr.acinq.secp256k1`
- **Quartz** (`com.vitorpamplona.quartz`) owns the standard Nostr half
  (NIP-06/19/44 + events), verified byte-for-byte in `QuartzParityTest`
- Placeholder screens for every PoC route; smoke + parity unit tests

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

## Next: finish Phase 1 — Cryptographic core

The Nostr half is done (Quartz, parity-proven). Remaining: implement
`SshKeyCodec` (the crux — raw 32-byte Ed25519 seed extraction) and `Seedify`,
plus thin `NostrKeyDerivation`/`Nip44` adapters over Quartz, all gated on golden
test vectors. Note the PoC uses **bcrypt_pbkdf = 1 round**.
