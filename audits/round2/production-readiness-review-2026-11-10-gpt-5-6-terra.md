# Production Readiness Audit — ZenTen Vault

- **Reviewed artifact:** `vault-kotlin-main.zip` (app module `:app`, package `app.zenten.vault`, `versionName 0.0.1-dev.1`)
- **Review date:** 2026-11-10
- **Method:** static review of source, Gradle, manifest, R8 rules, CI workflows, tests and docs. No files were modified.

---

## Executive Summary

**Release recommendation: NOT READY FOR RELEASE.**

The app has thoughtful security controls: Keystore-backed AES-GCM storage, screenshot protection, device authentication, encrypted backups, no plaintext passphrase persistence, signature-protected provider access, and defensive backup reconciliation.

However, production release is blocked by an unsigned release artifact, missing release-variant CI/device validation, and a confirmed main-thread bcrypt path that can freeze the UI or cause ANRs. The review also found a durable-state consistency flaw around setup completion.

No confirmed Critical vulnerability was identified in the reviewed source.

## Scope and Limitations

- Reviewed the supplied archive after extraction.
- The original workspace was empty; no application source files were modified.
- Reviewed 126 production/config/resource files and 43 Kotlin test sources.
- Static review covered manifest, Gradle, DI, storage, crypto, networking, Workers, domain flows, Compose/navigation, and CI.
- Dynamic build/test execution was attempted but could not run because the review environment has no Java runtime installed. Compilation, lint, R8 behavior, and test results could not be independently confirmed.
- No live CVE scan or real backup-service interoperability test was performed.

## Project Understanding

The project is a single-module Android application using Kotlin, Jetpack Compose, Navigation Compose, Hilt, WorkManager, DataStore, Android Keystore, BiometricPrompt, Nostr relays, and Brave Sync.

The primary data flow is Compose UI → ViewModel → domain service/consolidation engine → secure storage and/or remote backup source adapters.

## Findings

### HIGH-01 — Release artifact is unsigned

- **ID:** HIGH-01
- **Severity:** High
- **Category:** Gradle, Dependencies and Release Configuration
- **File:** `app/build.gradle.kts`
- **Line:** 24–35
- **Evidence:** The release build enables minification and shrinking but defines no `signingConfigs` or release `signingConfig`. `docs/technical/build-and-test.md` confirms that the output is an unsigned APK.
- **Explanation:** An unsigned APK is not a production-distributable Android artifact.
- **Impact:** The application cannot be safely published or updated through a normal production distribution channel.
- **Trigger conditions:** Any attempted production release from this repository configuration.
- **Recommended fix:** Add a release signing configuration supplied by protected CI secrets; establish signing-key custody, rotation, access control, and compromise-response procedures.
- **Confidence:** High
- **Verification:** Run `./gradlew :app:assembleRelease` and inspect the generated APK signing state with `apksigner verify --verbose`.

### HIGH-02 — Bcrypt passphrase verification runs on the main thread

- **ID:** HIGH-02
- **Severity:** High
- **Category:** Performance and Stability
- **File:** `app/src/main/java/app/zenten/vault/domain/AccountService.kt`
- **Line:** 32–51
- **Evidence:** `verifyVaultKeyPassphrase` and `verifyAccountPassphrase` synchronously call `SshKeyCodec.parse`. The parser executes `BCrypt().pbkdf(...)` in `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt:198`. Accepted work factors reach 8,192 rounds at `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt:37-61`.
- **Additional affected call sites:**
  - `app/src/main/java/app/zenten/vault/ui/newaccount/NewAccountViewModel.kt:23`
  - `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailViewModel.kt:60`
  - `app/src/main/java/app/zenten/vault/ui/secretcombinations/SecretCombinationsViewModel.kt:70`
  - `app/src/main/java/app/zenten/vault/ui/vaultkey/VaultKeyDetailScreen.kt:157`
- **Explanation:** Bcrypt PBKDF is intentionally CPU-expensive. Higher work factors are selectable in Settings, so this can occur through supported app behavior.
- **Impact:** UI freezes, dropped frames, unresponsive passphrase/export screens, and potentially ANRs on slower devices or high-round keys.
- **Trigger conditions:** Verifying a passphrase or exporting a key whose encrypted OpenSSH container has a costly accepted bcrypt work factor.
- **Recommended fix:** Make verification/export preparation suspend functions and perform SSH parsing/KDF work in `Dispatchers.Default`; expose loading/error UI state and disable duplicate submissions.
- **Confidence:** High
- **Verification:** Create a key with 8,192 rounds, then profile passphrase verification and export on a low-end device using StrictMode and Perfetto.

### MEDIUM-01 — Setup completion and backup-quorum evidence are written non-atomically

- **ID:** MEDIUM-01
- **Severity:** Medium
- **Category:** Data Management / Stability
- **File:** `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- **Line:** 47–50; also 94–95
- **Evidence:** `setSetupComplete()` is persisted before `setVaultKeyQuorumReached(true)`. Each method is a separate DataStore transaction in `app/src/main/java/app/zenten/vault/data/kv/AppStorageService.kt:93-120`.
- **Explanation:** A process death between these writes produces `setupComplete=true` while durable quorum evidence is absent. The launch router accepts setup completion independently in `app/src/main/java/app/zenten/vault/ui/root/RootScreen.kt:51-52`.
- **Impact:** Persisted state no longer reflects the documented invariant that completed setup has durable backup-quorum evidence. Subsequent account creation checks setup completion rather than quorum evidence.
- **Trigger conditions:** Process kill, crash, or storage interruption after setup completion is written and before quorum evidence is written.
- **Recommended fix:** Add one atomic `completeSetupWithVaultKeyQuorum()` DataStore edit that writes both values and clears pending state together. Route all successful imported-key completion paths through it.
- **Confidence:** High
- **Verification:** Instrument a controlled failure between the two writes, recreate the process, and assert that normal account navigation cannot proceed without both values.

### HIGH-03 — Release minification is neither CI-gated nor device-smoke-tested

- **ID:** HIGH-03
- **Severity:** High
- **Category:** Testing and Release Configuration
- **File:** `.github/workflows/ci.yml`
- **Line:** 55–62
- **Evidence:** CI runs debug unit tests, `assembleDebug`, and `lintDebug` only. It does not run `assembleRelease`, `lintRelease`, or instrumented tests. `docs/technical/build-and-test.md` explicitly confirms that the minified APK has not been device-smoke-tested.
- **Explanation:** Release-only R8/resource-shrinker/reflection/JNI defects can pass all required CI checks.
- **Impact:** A release APK may crash or have broken Hilt, Quartz, serialization, JNI, or crypto behavior despite green CI.
- **Trigger conditions:** Any regression that manifests only in the minified release variant.
- **Recommended fix:** Add required CI jobs for `:app:assembleRelease` and `:app:lintRelease`; add an emulator smoke test against the release variant and retain reports/artifacts.
- **Confidence:** High
- **Verification:** Introduce a temporary release-only reflection/R8 regression and confirm current CI does not detect it; then verify the new release pipeline does.

## Critical Release Blockers

No confirmed Critical-severity code vulnerability was found.

The following High-severity issues should block release:

1. Unsigned release artifact.
2. Main-thread bcrypt verification/export paths.
3. No CI release-variant gate or device smoke test.

## Medium and Low-Priority Issues

1. **MEDIUM-01:** Non-atomic setup-completion/quorum persistence.
2. No additional low-severity confirmed defects are reported. This review intentionally excludes subjective style preferences and documented intentional behavior, such as clipboard persistence after an explicit user export.

## Security Assessment

Positive evidence:

- `android:allowBackup="false"` is set.
- The app applies `FLAG_SECURE`.
- Sensitive stored account records are encrypted with AES-GCM using a non-exportable Android Keystore key.
- Passphrases are intended to remain memory-only and session copies are cleared where `CharArray` is used.
- The exported ContentProvider is protected by a signature permission.
- Cleartext endpoints are not configured; Nostr uses `wss` and Brave Sync uses `https`.
- No logging API or plaintext-secret logging was found in production source.
- No hard-coded private credential was reported. The Brave service key is documented and treated as a public protocol constant.

Unresolved release risks:

- Production signing and release-key operational controls are absent.
- No live dependency/CVE scan was performed in this review environment.
- Real remote backup interoperability, restore behavior, and minified-release behavior remain unverified.

## Stability and Performance Assessment

The architecture uses sensible cancellation handling in Workers and avoids persisting the session passphrase in WorkManager input. Network work is generally dispatched away from the UI.

The confirmed main-thread bcrypt paths are the largest stability risk. The selectable high bcrypt work factor makes freezes and ANR risk concrete. Release-only runtime behavior is also not validated.

## Missing Tests

- Instrumented/UI tests for passphrase verification and key export at the minimum and maximum supported KDF rounds.
- ANR/jank profiling or performance-budget testing on representative low-end hardware.
- Process-death test between `setSetupComplete()` and `setVaultKeyQuorumReached(true)`.
- Release-variant instrumentation smoke tests for Hilt, Keystore access, DataStore state, Quartz/Nostr startup, and JNI-backed secp256k1 paths.
- End-to-end tests against controlled Nostr and Brave Sync test infrastructure, including timeout, retry, malformed records, partial quorum, restoration, and recovery.
- Accessibility tests for core onboarding, authentication, recovery, and export flows.

## Areas That Appear Healthy

- Clear separation exists between UI, ViewModels, domain services, storage, and backup adapters.
- Keystore encryption validates payload version, IV bounds, and GCM authentication failures.
- SSH key parsing validates structure, bcrypt bounds, padding, and public/private key agreement.
- WorkManager avoids persisting the passphrase and fails closed when a restarted worker lacks an unlocked session.
- Remote backup reconciliation has explicit source indexing, read-back verification, durable source status, and serialization via a mutex.
- Manifest component exposure is explicit; the exported provider is read-only and permission-protected.
- Documentation candidly identifies known release limitations instead of claiming current production readiness.

## Recommended Fixing Order

1. Move all bcrypt parse/verification/export work off the main thread.
2. Make setup completion and quorum evidence a single atomic DataStore transition.
3. Implement CI-backed release signing and key-management procedures.
4. Require release assemble/lint and release emulator smoke tests in CI.
5. Run controlled end-to-end backup/recovery interoperability validation.
6. Establish dependency vulnerability scanning and a release security-review record.

## Final Release Recommendation

**NOT READY FOR RELEASE.**

The implementation is promising, but the available evidence supports withholding release until the high-priority release, stability, and verification gaps are closed.
