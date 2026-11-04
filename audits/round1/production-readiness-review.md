---
model: deepseek-v4-1-flash
audit_date: 2026-11-02
audit_time: "22:50:58 PKT"
audit_timestamp: 2026-11-02T22:50:58+0500
audit_type: production-readiness-review
repository: vault-kotlin
branch: main
commit: f38cd51
---

# Production-Readiness Review — ZenTen Vault (`vault-kotlin`)

Read-only review. Source unmodified; no commits/pushes. All findings cite file:line and state how verified.

---

## 1. Verdict

**DO NOT SHIP.** The release variant does not build: `./gradlew :app:assembleRelease` fails at `minifyReleaseWithR8` with missing-class errors, and there is no release signing configuration at all. Beyond that, the app's own five release gates are mostly unmet — no independent security assessment, no end-to-end interoperability proof against real backup destinations, no release/update/incident processes, and no telemetry or data-deletion story. The cryptographic core and in-memory passphrase design are genuinely solid for a pre-release build, but a production track cannot ship an artifact it cannot produce, sign, or test on-device.

---

## 2. Release-gate scorecard (`docs/stakeholder/scope-and-roadmap.md`)

| # | Gate | Status | Evidence |
|---|------|--------|----------|
| 1 | Independent security assessment of crypto, storage, backups, safety controls | **Not met** | `README.md` states review is outstanding; `docs/technical/product-status.md` calls itself “not a production-readiness certification.” No third-party report in repo. |
| 2 | End-to-end interoperability with real supported backup destinations | **Not met** | `docs/technical/product-status.md` lists all three Brave endpoints and both relays as requiring operational/interop validation. No integration fixtures or live tests; `build-and-test.md` explicitly keeps real-service tests out of CI. |
| 3 | Approved setup/recovery/error/offline UX incl. accessibility | **Partially met** | UX flows exist (`ui/onboarding`, `data/secure`), but `scope-and-roadmap.md` marks Backup status and automatic recovery **Partial**; no accessibility audit evidence. |
| 4 | Release signing, update, telemetry, privacy, support, incident response | **Not met** | No `signingConfigs` in `app/build.gradle.kts`; no Crashlytics/Sentry/analytics (`grep` found none); no privacy policy or incident/rotate plan in `docs/`. |
| 5 | Clear user docs + tested support procedures for lost recovery material | **Partially met** | `docs/features/*`, `docs/stakeholder/backup-and-recovery.md`, and the `Recover VaultKey` flow exist and are tested (`VaultKeyRecoveryServiceTest`), but no support runbook or user-facing recovery guide is present. |

---

## 3. Blockers (must fix before release)

### [High] — Release build fails under R8; no shippable artifact exists
- **Evidence (ran):** `./gradlew :app:assembleRelease` → `Task :app:minifyReleaseWithR8 FAILED` / `BUILD FAILED in 1m 22s`. Missing classes: `javax.management.MBeanException`, `javax.management.ReflectionException`, `net.i2p.crypto.eddsa.EdDSAPrivateKey`, `net.i2p.crypto.eddsa.EdDSAPublicKey`, `org.slf4j.impl.StaticLoggerBinder`. AGP generated `app/build/outputs/mapping/release/missing_rules.txt`.
- **Root cause:** `app/proguard-rules.pro` keeps BouncyCastle and secp256k1 but has no `-dontwarn` rules for Apache MINA sshd's optional javax.management/i2p/slf4j references.
- **Impact:** The app cannot produce a release APK at all. Worse, this regressed silently because CI only builds debug (§8).
- **Fix:** Add the five `-dontwarn` rules (or exclude the unused optional dependencies), then run `assembleRelease`, `lintRelease`, and a real on-device smoke test of the minified build. Add `:app:assembleRelease` and `:app:lintRelease` as CI gates.

### [High] — No release signing configuration or key management
- **Evidence (read):** `app/build.gradle.kts:24–34` defines `buildTypes.release` with minify/shrink but **no `signingConfig`**. No keystore, `.jks`, or `.properties` is tracked (`git ls-files`), and `.gitignore` excludes `*.keystore`. No signing step in `.github/workflows/ci.yml` or `security.yml`.
- **Impact:** Even after R8 is fixed, the release APK is unsigned and unshippable. Gate 4 cannot be met.
- **Fix:** Create a signing config fed from CI secrets/environment, document key custody/rotation, and add an incident plan for a compromised signing key.

### [High] — Release variant is never tested; R8 keep-rule correctness is unverified
- **Evidence (read):** `app/proguard-rules.pro` covers BouncyCastle, secp256k1, and kotlinx.serialization, but not Hilt/WorkManager/Quartz reflection paths. `.github/workflows/ci.yml:56–62` runs only `testDebugUnitTest`, `assembleDebug`, `lintDebug`; the only instrumentation sources (`KeystoreCryptoServiceInstrumentedTest`, `SecureStorageServiceInstrumentedTest`) never run in CI.
- **Impact:** Minification can strip or rename classes used reflectively and only crash in release. There is no evidence the release artifact boots, unlocks, or backs up.
- **Fix:** Run `connectedReleaseAndroidTest` (or at minimum a release smoke test) on a device/emulator and add it to CI.

---

## 4. High-risk but non-blocking

### [High] — Device-auth gate is interactive-only; secure storage and KeyHive are readable unattended
- **Evidence (read):** `KeystoreCryptoService.kt:19` — “The key is deliberately not bound to user authentication. Unattended background backup and signature-authorized KeyHive provider access must keep reading secure storage…” `AndroidManifest.xml` exports `AssociatedAccountsProvider` (`android:exported="true"`, `signature` permission). `AssociatedAccountsProvider.kt` reads decrypted `Account` records regardless of `AppUnlockCoordinator`.
- **Impact:** A compromised same-cert sibling app, any background worker (e.g., `ProfilePublicationWorker`), or a forensic/rooted device can read all passphrase-encrypted private keys + public identities without biometric unlock. The lock gate is UX, not a data boundary. This is documented as a non-promise in `security-model.md`, so it is under-appreciated rather than hidden.
- **Fix (decide explicitly before release):** Either accept and state it in user-facing docs, or move to an authentication-bound Keystore domain for interactive reads while keeping a separate unattended domain for backup/KeyHive.

### [High] — Vault-Key offline crackability assumptions are optimistic
- **Evidence (read):** `SshKeyCodec.kt:23` `DEFAULT_BCRYPT_PBKDF_ROUNDS = 128`; `BackupService.buildBackupEvent` puts the passphrase-encrypted key on public relays (`network/nostr`) and the Brave chain. `security-model.md` claims zxcvbn bucket 3 implies safety “against an offline attack that assumes a salted, slow hash function.” Imported/recovered Vault Keys bypass `VaultKeyPassphrasePolicy` entirely (`OnboardingViewModel.performSetup` only parses; policy is applied only in `createvaultkey/`).
- **Impact:** The Vault Key backup is public and protected only by the user's passphrase under a KDF whose work factor is low (128 bcrypt rounds, max 8192) and whose strength is never checked for imported keys. zxcvbn's “offline slow hash” model assumes ~10⁴ guesses/s, which does not describe bcrypt_pbkdf at 128 rounds against GPUs/ASICs.
- **Fix:** Raise the default KDF work factor, consider a memory-hard KDF over the passphrase, and state the offline-attack assumption honestly in `security-model.md`; add a warning for imported weak passphrases.

### [High] — Decryption/corruption failures are silent and can look like a wiped vault
- **Evidence (read):** `SecureStorageService.kt:72–77`: `read()` returns `null` on any decrypt/JSON failure, `readAccounts()` uses `mapNotNull`, and `registry()` swallows decode errors to `emptyList()`. No error category, audit, or user-visible signal.
- **Impact:** A lost/corrupt Keystore key or tampered record silently yields “no accounts,” and `getVaultKey() == null` routes the user to fresh onboarding rather than recovery. Records are recoverable from backups **only if** the user still holds the 24 words + passphrase.
- **Fix:** Distinguish “absent” from “unreadable,” surface a recovery prompt/telemetry event, and add a Keystore-key-loss path that guides the user to Secret Combinations recovery.

### [High] — Background backup failures are invisible to the user
- **Evidence (read):** `BackupWorkers.kt` returns `Result.retry()`/`Result.failure()`; the engine records only coarse `BackupErrorCategory` in DataStore. No notification, no periodic schedule (by design), no remediation UI beyond the status rows marked “Partial” in `product-status.md`.
- **Impact:** A user can believe backups are healthy when the passphrase session was locked, the relay rejected the event, or read-back failed. There is no “your last backup is N days old” alert.
- **Fix:** Add a user-visible freshness/severity banner and a manual-retry affordance; document the retry/backoff semantics.

---

## 5. Medium / Low findings

**Cryptography / secrets**
- **Medium — Unbounded bcrypt rounds on parse:** `SshKeyCodec.kt:187` accepts any `rounds > 0` from a container; only generation constrains to `SUPPORTED_BCRYPT_PBKDF_ROUNDS`. A hostile pasted/clipboard/keyboard-wrapped key with an enormous round count can stall the UI. Fix: cap parsed rounds (e.g., ≤ 8192).
- **Medium — Unzeroable passphrase copies:** `SshKeyCodec.kt:139` does `String(passphrase).toByteArray(...)`, creating an immutable heap copy that can never be wiped, on every bcrypt operation. `parse`/`Seedify`/`NostrKeyDerivation` also leave derived `ByteArray`/`CharArray` copies unzeroed. Acknowledged as “zeroization limits,” but worth centralizing into a wipeable buffer.
- **Low — `NigoriCrypto.decrypt` is constant-time** (`MessageDigest.isEqual`, `NigoriCrypto.kt`), and `LocalAccountBackupViewModel` uses `MessageDigest.isEqual` for key comparison — verified-good.

**Secure storage / data at rest**
- **Medium — No migration path:** commit `94f0d42` replaced `EncryptedSharedPreferences` with the current `vault_secure_accounts_v2` Keystore format; `SecureStorageService` has no migration branch. Pre-release only, but `security-model.md`/`product-status.md` list “migration” as an open review topic — confirm no upgrade path is needed for any distributed dev/team builds.
- **Low — `getAccounts()` reads all records synchronously** in the `SecureStorageService` constructor and on every `publishAccounts()`; fine today (99-account ceiling) but disk I/O can land on the caller thread.

**Networking / backup transports**
- **Medium — No certificate pinning / endpoint trust is DNS+TLS only:** `BraveSyncTransport.kt:13–14` uses a default `OkHttpClient`; `NostrRelayService` uses default TLS. The `litesync*.zentext.me` and `appdata*.nostr.box` servers are operator-controlled and see ciphertext + metadata. Not claimed otherwise, but should be explicit for a credential vault.
- **Medium — `BravePasswordService` caches the timestamped auth token + Nigori keys** for the whole run (`cachedSession`, `cacheSession=true` in `DefaultBackupSourceFactory`); fine within a bounded run, but there is no token-expiry handling.
- **Low — `ProtoWire`/`SyncMessages` parsing is defensive** (truncation/length checks, group validation), but `parseGetUpdates` has no cap on entity count/response size; a hostile server can drive memory growth.
- **Low — Nostr idempotency** (`already exists` + exact event-id match) is well tested (`NostrRelayServiceTest`) — verified-good.

**Platform / release config**
- **Medium — Gradle wrapper lacks `distributionSha256Sum`:** `gradle/wrapper/gradle-wrapper.properties:3` only pins the URL. Supply-chain integrity for the wrapper distribution is unverified (jar itself is present, 43,504 bytes). Fix: add the official SHA-256 sum.
- **Medium — BouncyCastle 1.79 is several releases behind and carries known advisories** (Snyk lists multiple issues fixed in 1.85/1.86, including a PBKDF2 resource-exhaustion and several timing/cert-validation CVEs). None appear directly reachable by this app's usage (Ed25519, `bcrypt_pbkdf`, AES-CTR), but the dependency is stale. `zxcvbn 1.9.0` and `sshd-common 2.12.1` show no direct advisories.
- **Low — unused `zxing` catalog entry:** `gradle/libs.versions.toml:29,67` declares `zxing-core` 3.5.3 but `app/build.gradle.kts` never uses it.
- **Low — `android:allowBackup="false"` is set** (`AndroidManifest.xml`) but there is no `android:dataExtractionRules` (API 31+) declaration; acceptable, but add it to be explicit for D2D transfer.
- **Low — `product-status.md` references a non-existent path `ui/Vault Key/`** (space) in the “Per-source status UI” row.

**Privacy / operations / compliance**
- **Medium — Public profile publication:** `ProfileService.publishAssociatedAccountProfile` publishes kind `0` (name = `createdAt`, display_name) and kind `10050` to 7 public relays after local backup (`LocalAccountBackupViewModel.complete()`). This links an SSH-derived npub to a creation timestamp/name publicly. Likely intended for Nostr, but should be a documented, user-consented privacy behavior.
- **Medium — No telemetry, crash reporting, audit trail, or incident/rotate plan** for a compromised relay or key. Gate 4 unmet.
- **Medium — No account-deletion or vault-reset UI** (documented “Not available”). For Play/production this conflicts with expectations of data deletion and “reset my vault”; at minimum document the `clearAll()` gap and provide a user path.

---

## 6. Verified-good (don't re-litigate)

- **Deterministic derivation + golden vectors:** `SshKeyCodecTest`, `QuartzParityTest` (NIP-06/19/44 official vectors), `BraveSyncCryptoParityTest`, `SeedifyTest` (25th-word modulo/wrap), `golden/crypto-vectors.json` + `nip44.vectors.json`. Ran green.
- **OpenSSH parsing is defensive:** magic/version/cipher/KDF/check-int/padding/public-key cross-check; rejects unencrypted keys; caps serialized input at 16 KB (`SshKeyCodec.kt`).
- **NIP-44 self-encryption + NIP-42 transient auth** with signatures verified before publish/fetch (`NostrEventCodec.isValid`), and operation/identity separation for Vault Key vs account records (`NostrBackupSource`).
- **Consolidation contract:** strict “unknown ≠ missing,” sequential single-mutex execution, restore-before-backup two phases, write read-back verification, idempotency by canonical ID — well covered by `AccountConsolidationEngineTest` (311 lines, failure-path cases).
- **Passphrase never persisted:** `VaultKeySession` verifies by real decryption, holds memory-only, zeroes on lock; `WorkManager` input contains no passphrase.
- **Device-lock gate:** strong-biometric-or-device-credential, 30s monotonic grace, memory-only unlock, pre-API-30 keyguard path, fail-closed error classification (`DeviceLockPolicyTest`), `FLAG_SECURE` for the activity lifetime.
- **Keystore AES-256-GCM:** fresh Keystore IV per write, version byte, constant-time MAC (`KeystoreCryptoService.kt`).
- **KeyHive is read-only and allowlisted:** `SharedAssociatedAccount` exposes only `createdAt`, `zentenKey`, `nostrPubkey` (test enforces no privkey/other fields).

---

## 7. Doc drift

1. **`docs/technical/architecture.md:74`** claims “`VaultApplication` applies it before its startup consolidation, so user-reachable in-process work cannot sign timestamped Nostr events or Brave Sync tokens with a known-bad clock.” **Reality:** `VaultApplication.kt:24–26` explicitly states there is *no* start-time consolidation, and the class does not inject `RemoteOperationPrecondition`. Correction: remove `VaultApplication` from the list; startup only enqueues nothing.
2. **`docs/technical/build-and-test.md`** does not mention that the release variant is unbuildable/untested; the CI section implies only debug coverage. Update after the fix, and document the release gate.
3. **`docs/technical/product-status.md`** “Per-source status UI” cites `ui/Vault Key/` (invalid path).
4. **`README.md` / `docs/technical/security-model.md`** accurately state the interactive-gate limitation; no drift there — but the security model should record the explicit *decision* to expose encrypted keys to same-signature apps without auth (it currently only says the provider “retains its existing contract”).

---

## 8. Test / CI evidence (exact commands run)

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest --console=plain` | **BUILD SUCCESSFUL** in 1m59s. Parsed XML: 36 test classes, **198 tests, 0 failures, 0 errors, 0 skipped**. No `@Ignore`/`@Disabled` anywhere. |
| `./gradlew :app:assembleRelease --console=plain` | **BUILD FAILED** at `:app:minifyReleaseWithR8` (missing classes, §3). |
| `./gradlew :app:lintRelease --console=plain` | **BUILD SUCCESSFUL** in 1m11s; report at `app/build/reports/lint-results-release.html` (~51 warnings, 0 errors). |
| `grep` for skips/ignores | none |
| `.github/workflows/ci.yml` | runs only `testDebugUnitTest`, `assembleDebug`, `lintDebug` — **no release build, no lintRelease, no instrumentation**. |
| `.github/workflows/security.yml` | Gitleaks (pinned image + SHA-pinned actions, full history) and Semgrep `--severity ERROR` gate, weekly schedule. Free-tier substitutes documented. |
| `gradle-wrapper.properties` | no `distributionSha256Sum`. |

Test quality is good for the covered areas: crypto parity vectors are exact, consolidation tests cover index-failure/write-failure/read-back/freshness/idempotency edge cases, and provider field allowlisting is asserted. Gaps: **no host test for `KeystoreCryptoService`/`SecureStorageService`** (only two instrumented tests, never run in CI), no test for hostile oversize bcrypt rounds, and no release/R8 test.

---

## 9. Open questions / assumptions

- **Assumption (medium confidence):** the app has no shipped production users, so the missing secure-storage migration is acceptable; the `< 1.0` version and README support this. If any team APK was distributed, migration becomes a blocker.
- **Unverified:** whether `sync-v2.brave.com` and the `litesync*` servers actually interoperate with the derived Sync code / Nigori parameters end-to-end. The repo documents this as outstanding; live services could not be tested from here.
- **Unverified:** actual biometric/Keyguard behavior on API 26–29 physical devices and OEM background-launch restrictions on KeyHive provider process restart.
- **Assumption:** the KeyHive same-signature threat model is an approved product requirement (per `security-model.md`); if not, it is a critical design change, not a hardening step.
- **Not reviewed deeply:** visual/accessibility correctness of Compose screens, and the `remove-passphrase-storage-plan.md` completeness (the resulting code was read, not the plan's checklist).
