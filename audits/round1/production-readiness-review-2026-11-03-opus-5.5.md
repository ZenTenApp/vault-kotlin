# Production-readiness review: ZenTen Vault (vault-kotlin)

- **Date:** 2026-11-03
- **Reviewer:** Claude Opus 5.5 (Claude Code), at the request of the repository owner
- **Method:** Static analysis only, by reading the source, build config, CI and docs. The build, test and lint commands were attempted but could not run in the review sandbox (see §8). No source, git or credential changes were made.
- **Independence:** The earlier reviews in `audits/` were not read before or during this review.
- **Commit reviewed:** `f38cd51` (main), clean working tree at time of writing.

---

## 1. Verdict: **DO NOT SHIP**

None of the five release gates is fully met, and the build, tests and lint could not be run. Several issues go beyond the gaps the docs already admit:

- The app publishes public profile data to third-party relays without the user present, and the security model doesn't mention it.
- The default backup sources include Brave's official sync server, reached by presenting as Chrome.
- Users can choose a bcrypt work factor as low as 1 for keys that are later exported as text.
- There is no release signing.

The core cryptography and storage design is sound. No confirmed Critical finding (medium-high confidence, static only).

## 2. Release-gate scorecard

Gates are from `docs/stakeholder/scope-and-roadmap.md`.

| # | Gate | Status | Evidence |
|---|---|---|---|
| 1 | Independent security assessment | **Not met** | None exists. This static review does not count as one. |
| 2 | Interoperability testing with real backup services | **Not met** | No end-to-end or interop tests. `NostrRelayServiceTest` is 39 lines and doesn't cover the auth flows. No tests for `BraveSyncBackupService` or `BraveBackupSource`. The two instrumented tests are not run in CI. |
| 3 | Approved setup/recovery/error/offline UX, accessibility | **Partially met** | Flows exist. `product-status.md` itself marks status and remediation UI as "Partial". No accessibility evidence. |
| 4 | Signing, update, telemetry, privacy, support, incident response | **Not met** | No `signingConfig` in `app/build.gradle.kts`. No crash reporting. No incident or rotation plan. Privacy gaps (B3, H3). |
| 5 | User docs and tested support for lost recovery material | **Partially met** | Recovery by Secret Combinations exists (`domain/VaultKeyRecoveryService.kt`). There are no user-facing support docs or tested procedures. |

## 3. Blockers

### [High] B1: No release signing, and the release build has never been verified

- **Evidence:**
  - `app/build.gradle.kts` has no `signingConfigs`.
  - `.github/workflows/ci.yml` runs only `testDebugUnitTest`, `assembleDebug` and `lintDebug`. It never runs `assembleRelease`, so R8 minification is never exercised.
  - `app/proguard-rules.pro` keeps BouncyCastle, secp256k1 and the serialization classes. It has no rules for:
    - Quartz (Nostr library) reflective/JSON paths;
    - Hilt-injected WorkManager workers;
    - `org.apache.sshd` `BCrypt`.
- **Impact:** A minified release may crash at runtime in the crypto or relay code, and no test would catch it.
- **Fix:**
  - Add a release signing config fed from CI secrets (Play App Signing).
  - Add `assembleRelease` and `lintRelease` to CI.
  - Smoke-test the minified APK with an instrumented create → backup → recover run.

### [High] B2: The default Brave source is Brave's official production server, reached by presenting as Chrome

- **Evidence:**
  - `domain/BackupSources.kt` (`DefaultBackupSources`, entry `default-brave-official`) points to `https://sync-v2.brave.com/v2`. The entry is non-removable and counts toward quorum.
  - `bravesync/transport/BraveSyncTransport.kt` sends `User-Agent: Chrome MAC 144.1.86.142 (zenten-vault)` together with Brave's service key (`BraveSyncConfig.SERVICE_KEY`).
- **Impact:**
  - This is a terms-of-service and legal risk, and Brave can block it at any time.
  - If it is blocked, setup quorum still works through the other Brave endpoints, but users lose a recovery path without being told.
- **Fix:**
  - Get Brave's permission or remove the official endpoint from the defaults.
  - Send an honest User-Agent.
  - Document the dependency in `docs/technical/brave-sync-backup-protocol.md`.

### [High] B3: Account profiles are published to 7 public third-party relays without the user present, and the security model doesn't say so

- **Evidence:**
  - `domain/ProfileService.kt` `PROFILE_RELAYS` (user.kindpag.es, purplepag.es, directory.yabu.me, profiles.nostr1.com, relay.primal.net, relay.snort.social, nos.lol) receive:
    - a kind-0 profile (`name` = createdAt, `display_name` = the user's account name);
    - a kind-10050 DM-relay list pointing to `dm1.zentext.me`, `dm2.nostr.box` and `dm3.npub.cx`.
  - `background/ProfilePublicationWorker.kt` signs with the account's Nostr private key read from storage. It does **not** check `VaultKeySession`, so it runs while the vault is locked.
  - `publishAssociatedAccountProfile` ends with `check(failures.isEmpty())`. If any one relay fails, the worker retries and republishes to every relay, with no limit.
- **Impact:**
  - **Privacy:** account names are published on public, permanent infrastructure and linked to keys.
  - **Doc mismatch:** `security-model.md` says background work fails closed without a session.
  - **Compliance:** Play Data Safety disclosure is affected.
- **Fix:**
  - Make publication opt-in, or document it in the security model and Data Safety.
  - Use a quorum instead of requiring every relay to acknowledge, and cap retries.
  - Decide whether publication should require an unlocked session.

### [High] B4: Users can set the bcrypt work factor down to 1 for keys that leave the device

- **Evidence:**
  - `crypto/SshKeyCodec.kt`: `SUPPORTED_BCRYPT_PBKDF_ROUNDS = (0..13).map { 1 shl it }` includes 1.
  - `ui/settings/SettingsScreen.kt` exposes every value in that list.
  - `AccountService.createAssociatedAccount` and `CreateVaultKeyViewModel` use the chosen value.
- **Impact:**
  - Those encrypted keys leave the device in several ways:
    - exported as text to notes apps, which are often cloud-synced;
    - copied to the clipboard;
    - readable by any sibling app with the same signature through `AssociatedAccountsProvider`.
  - At 1 round, guessing the passphrase offline is cheap.
  - Imported Vault Key passphrases also skip the strength policy (`VaultKeyPassphrasePolicy` only covers generated keys).
- **Fix:**
  - Set a minimum (e.g. ≥ 64; 128 is the current default).
  - Remove the setting from production builds, or put it behind a debug flag.
  - Warn about weak passphrases on import.

### [High] B5: Gates 1 and 2 have no evidence behind them

- **Evidence:**
  - No interop harness.
  - The instrumented tests (`KeystoreCryptoServiceInstrumentedTest`, `SecureStorageServiceInstrumentedTest`) are not in CI.
  - No tests for the workers, `VaultKeySession`, `AppUnlockCoordinator` (only the pure `DeviceLockPolicy` is tested), or the Brave adapter.
- **Fix:**
  - Commission an external crypto and protocol review.
  - Add an opt-in interop CI job against staging relays and Brave endpoints.
  - Run the instrumented tests on an emulator in CI.

## 4. High-risk but non-blocking

These are Medium severity, but close to blocking.

### H1: The passphrase stays in memory while the app is in the background

- **Evidence:**
  - `VaultKeySession.lock()` only runs when `unlockState` changes (`MainActivity.kt:74-76`).
  - `AppUnlockCoordinator.onBackground()` only records a timestamp. Relock is checked on the next `onForeground()`.
- **Impact:** The "30 s relock" is lazy. The passphrase stays in memory for as long as the process is backgrounded, which a memory-dump attack could exploit.
- **Fix:** Start a timer on `onStop` that zeroes the session after the grace period, once workers that started under the session have taken their copy.

### H2: Keystore loss or device-to-device restore fails silently

- **Evidence:**
  - `KeystoreCryptoService.decrypt` returns `null` on any failure.
  - `SecureStorageService.read` treats that `null` as "no record", so the vault silently looks empty or like a fresh install.
  - The manifest has `allowBackup="false"` but no `android:dataExtractionRules`. On Android 12+, device-to-device transfer can still copy the prefs file without the Keystore key.
- **Fix:**
  - Add `dataExtractionRules` that exclude everything.
  - Tell apart "absent" from "undecryptable", and show a recovery screen for the latter.
- **Open:** Whether `setup_complete=true` combined with an unreadable Vault Key produces a broken screen.

### H3: No way to delete accounts, reset the vault, or delete remote backups

- **Evidence:** `BravePasswordService.delete` exists but is never called. Relay records are permanent replaceable events.
- **Impact:** Affects GDPR-style deletion requests and Play policy. It also blocks incident response: a key can't be rotated after a relay or key compromise.

### H4: The private key goes to the clipboard without a sensitivity flag or auto-clear

- **Evidence:** `ui/accounts/AccountDetailScreen.kt:163-170` copies the encrypted PEM without `ClipDescription.EXTRA_IS_SENSITIVE`, and nothing clears it on a timer.
- **Impact:** The key is encrypted, but it is exposed to clipboard history and keyboard apps. This matters more given B4.

## 5. Medium and Low findings

### Supply chain

- `gradle/wrapper/gradle-wrapper.properties` has no `distributionSha256Sum`.
- `ci.yml` uses mutable tags (`actions/checkout@v4`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v4`), while `security.yml` pins commit SHAs.
- Semgrep rules are fetched at run time (already acknowledged in the docs).
- Dependency CVEs could not be checked because Maven Central was blocked in the sandbox (BouncyCastle 1.79, sshd-common 2.12.1, OkHttp 4.12.0, androidx.biometric 1.1.0). Treat this as unverified.

### Testing

- Robolectric 4.14.1 against compileSdk 36 may not support SDK 36 without `@Config(sdk = …)`. Unverified.

### Resource limits

- `SshKeyCodec.readEncryptedContainer` accepts any `rounds > 0` from an imported key, so a crafted key with ~2³¹ rounds hangs the UI. Cap the rounds on parse.
- Nostr `fetchAll` results have no cap on count or size.

### Zeroization

- Passphrases pass through `String`s, which can't be wiped:
  - `VaultUnlockGateViewModel.submitPassphrase`
  - `AccountService`
  - `VaultKeyRecoveryService`
  - `String(passphrase)` in `SshKeyCodec.cryptBcrypt`
- This is documented as a limitation, but it is broader than the docs suggest.

### Network

- **Unencrypted HTTP on older devices:** there is no network security config, so cleartext is allowed by default on API 26-27. All endpoints are `https`/`wss` (enforced by `normalizeSourceEndpoint`), so the risk is low. Add a config with `cleartextTrafficPermitted="false"`.
- **No certificate pinning** (OkHttp default system trust). Acceptable because the payloads are end-to-end encrypted and signed, but it should be a documented decision.

### Code hygiene

- **Footgun:** `BackupService.backupVaultKey` and `backupAssociatedAccount` are never called. `backupVaultKey` would publish under the wrong (original-seed) identity. Remove both.
- **Error text:** `BraveSyncTransport.post` puts up to 500 bytes of the server response into exception messages. It isn't saved anywhere (it is mapped to an error category), so this is low risk.

### Performance and reliability

- **Startup work on the main thread:** `SecureStorageService`'s constructor decrypts with the Keystore during Hilt injection.
- **Retries:** worker `Result.retry()` has no attempt cap when there is no validated internet.

## 6. Verified good (by reading the code)

- **Secure storage:** `KeystoreCryptoService` uses AES-256-GCM with a key that can't be exported, a fresh IV from the Keystore on every write, a 128-bit tag, a version byte, and no silent key rotation on read (`loadKey` vs `getOrCreateKey`).
- **No passphrase on disk:** consolidation workers take a copy of the in-memory passphrase, zero it in `finally`, and return failure when the session is locked. Nothing secret goes into WorkManager input data.
- **Generated SSH keys** (`SshKeyCodec`): OpenSSH-compatible aes256-ctr + bcrypt_pbkdf, check-int and padding validation, outer/inner public key match, 16 KB input cap, derived key zeroed.
- **Key derivation:**
  - Seedify uses domain separation (`sha256(sha256("vkbckp") ‖ seed)`).
  - The Brave 25th word wraps correctly past day 2048.
  - Brave HKDF and Nigori use a constant-time MAC check (`MessageDigest.isEqual`) and check the MAC before decrypting.
- **Nostr:**
  - Events are signature-checked on publish and fetch, and filtered by author and kind.
  - Cross-kind `d` tags are rejected.
  - NIP-42 auth signing is transient.
  - A forged record would need the private key.
- **Consolidation engine:**
  - "unknown ≠ missing" holds: a source is written only after a successful index.
  - Operations run one at a time under a mutex.
  - Restore runs before backup.
  - Every write is read back to confirm it.
  - Statuses store only IDs and error categories.
- **App gate:**
  - `FLAG_SECURE` covers the whole activity.
  - Device-auth errors are classified and fail closed.
  - The pre-API-30 keyguard fallback is correct.
  - Unlock state is memory-only.
- **No logging:** there are no `Log.*` or `println` calls in `app/src/main`.
- **KeyHive provider:** signature permission, read-only, rejects selection and sort arguments, and exposes only `createdAt`, `zentenKey` (encrypted) and `nostrPubkey`.

## 7. Doc drift

| File | Says | Code does | Correction |
|---|---|---|---|
| `docs/technical/security-model.md:39` | "general backup and consolidation use the stored original-seed Vault Key identity" | Vault Key records always use the `vkbckp` identity (`DefaultBackupSourceFactory.createRunContext`, `NostrBackupSource.vaultKeyIdentity`). Only associated-account records use the original seed. This also contradicts `docs/features/vault-key-setup.md`. | Split the statement by record kind. |
| `docs/technical/security-model.md` (safeguards and Non-promises) | Background work fails closed without a session | `ProfilePublicationWorker` runs unattended and signs with stored keys | Document it, or gate it on the session. |
| `security-model.md`, `product-status.md` | Relocks after 30 s; passphrase is zeroed on relock | Relock is lazy; the passphrase stays in memory while backgrounded (H1) | Describe the actual behaviour, or fix it. |
| `docs/technical/product-status.md` (device-auth row) | "background backup … remain unattended" | Contradicts `security-model.md`; consolidation needs the session | Make the two docs agree; the code matches `security-model.md` for consolidation. |
| `docs/technical/product-status.md` | `ui/Vault Key/`, `ui/sources/`, "pre-setup source list/add", "adds at most three pre-setup Nostr relays" | Neither directory exists; `scope-and-roadmap.md` says there is no management UI | Remove these references. |
| `app/src/main/java/app/zenten/vault/di/NetworkModule.kt` comment | "PoC relay set" | Production-track build | Reword. |
| `docs/technical/security-model.md` | No mention of profile publishing or third-party relays | `ProfileService.PROFILE_RELAYS`, `DM_RELAYS` | Add a trust-boundary row. |
| `code-review-checklist.md` | Missing several files | `KeystoreCryptoService.kt`, `KeystoreCryptoServiceInstrumentedTest.kt`, `ProfileService.kt`, `ProfilePublicationWorker.kt`, `VaultKeySession.kt` and `VaultKeyRecoveryService.kt` exist | Add them. |

## 8. Test and CI evidence

| Command | Result |
|---|---|
| `./gradlew --no-daemon :app:testDebugUnitTest` (JDK 25) | **FAILED.** "What went wrong: 25.0.4.1": AGP 8.11 / Gradle 8.13 doesn't recognise JDK 25. |
| `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon --console=plain :app:testDebugUnitTest` | **FAILED.** "SDK location not found … sdk.dir … Directory does not exist" (`/home/daniyal/Android/Sdk` is not present in the sandbox). |
| `:app:assembleRelease`, `:app:lintRelease` | **Not run:** same SDK blocker. Maven Central also returned 403 "Approval required for repo1.maven.org" (a sandbox network policy). |
| Static check for skipped tests | No `@Ignore`, `@Disabled` or `assume*` in `app/src/test` or `app/src/androidTest`. |

To run these for real:

1. Install Android SDK platform 36 and build-tools 36.0.0.
2. Allow access to Maven Central (`repo1.maven.org`).
3. Run the commands above with JDK 21.

## 9. Open questions and assumptions

1. Does Brave permit third-party clients on `sync-v2.brave.com`?
2. Who operates `zentext.me`, `nostr.box` and `npub.cx`? What are their retention, SLA and compromise plans?
3. If the Keystore key is lost while `setup_complete=true`, does navigation show a usable screen? `VaultNavGraph` was not traced.
4. Are any dependency CVEs open? Not checked (no network access to the registries).
5. Do the R8 keep rules work with Quartz's reflection and serialization in a release build?

**Confidence:** High for the crypto and storage findings, which come from direct reading. Medium for the runtime and Android-behaviour claims (H1, H2, B1/R8), which are untested.
