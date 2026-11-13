# ZenTen Vault (Android/Kotlin) — Production Readiness Review

- **Reviewed artifact:** `vault-kotlin-main.zip` (app module `:app`, package `app.zenten.vault`, `versionName 0.0.1-dev.1`)
- **Review date:** 2026-11-10
- **Method:** static review of source, Gradle, manifest, R8 rules, CI workflows, tests and docs. No files were modified.
- **Prior audits in repo:** `audits/round1/*` were read for context only. Every finding below was re-verified in the current code. Where a finding overlaps an item still open in `audits/round1/remediation-plan-b.md`, the item ID is given as "Tracked as".

---

## 0. Scope and limitations

| Area | Coverage |
| --- | --- |
| Read in full | `MainActivity`, `VaultApplication`, `AndroidManifest.xml`, all Gradle/R8/CI files, `di/*`, `data/*`, `crypto/KeystoreCryptoService`, `crypto/SshKeyCodec`, `crypto/Seedify`, `crypto/NostrKeyDerivation`, `crypto/Nip44`, `domain/*` (all 11 files), `background/BackupWorkers`, `keyhive/*`, `network/nostr/NostrRelayService`, `bravesync/BravePasswordService`, `bravesync/transport/*`, `bravesync/auth/*`, `bravesync/proto/ProtoWire`, `ui/auth/*`, `ui/root/*`, `ui/VaultNavGraph`, `ui/availability/AppAccessGuard`, and every ViewModel. |
| Read for specific paths | Account, VaultKey, local-backup, onboarding, recovery, secret-combinations and settings screens (export, clipboard, back-handling and coroutine paths). |
| Skimmed only | `ui/theme/*`, `ui/components/*`, `PassphraseStrengthDetailsSheet`, `DecodeRevealTerminal`, `GetStartedScreen`, `bravesync/proto/SyncMessages`, `bravesync/password/*`, `NigoriCrypto`, `NigoriInitializer`, `NostrEventCodec`. |
| Not done | No Gradle build, no unit or instrumented test run, no device or emulator testing, no resolved-dependency CVE scan. Third-party library internals (Quartz, AndroidX Biometric, OkHttp, MINA `BCrypt`) were not audited. |

Behavioral claims about the Android platform (configuration changes, device-to-device transfer, ANR thresholds) come from documented platform behavior. They were not reproduced on a device.

---

## 1. Project understanding

**Purpose.** This is a single-module Android vault. It generates or imports a passphrase-encrypted Ed25519 OpenSSH "VaultKey", derives up to 99 associated SSH accounts, and backs every key up to two Nostr relays and two Brave-Sync-compatible ("litesync") servers. Recovery uses 24 BIP-39 index "Secret Combinations" plus the passphrase.

**Stack.** Kotlin 2.2.21, AGP 8.11.2, compile/target SDK 36, minSdk 26, Jetpack Compose (BOM 2026.06.01) with Navigation-Compose, Hilt with KSP, WorkManager with HiltWorker, DataStore Preferences, SharedPreferences encrypted with an Android Keystore AES-GCM key, OkHttp 4.12, Quartz (Nostr NIP-06/19/44), BouncyCastle, Apache MINA `BCrypt`, zxcvbn, and AndroidX Biometric 1.1.0.

**Layering.**

```
Compose screens ──► @HiltViewModel ──► domain services (SetupService, AccountService,
                                       VaultKeyRecoveryService, AccountConsolidationEngine)
                                         │
              ┌──────────────────────────┼────────────────────────────┐
     SecureStorageService        AppStorageService             BackupSourceFactory
     (SharedPrefs + Keystore)    (DataStore: flags, statuses)  ├─ NostrBackupSource → NostrRelayService (Quartz/OkHttp WS)
                                                               └─ BraveBackupSource → BravePasswordService → BraveSyncTransport (OkHttp)
     VaultKeySession (in-memory passphrase, @Singleton)
     WorkManager workers (consolidation) read the passphrase from VaultKeySession
     AssociatedAccountsProvider (exported, signature-permission ContentProvider)
```

**Security gates at runtime.** `AppUnlockCoordinator` handles device authentication: strong biometric or device credential, with a 30-second re-entry grace period. `VaultUnlockGateViewModel` then requires the passphrase, which is held in memory only. `AppAccessGuard` blocks navigation unless there is validated internet and the device clock is within ±2 minutes of server time.

The architecture is coherent and the domain layer is well documented. The main weaknesses are lifecycle handling in the Activity, the threading of expensive cryptography, and operational resilience.

---

## 2. Findings

Severity ordering: High → Medium → Low. **No Critical findings were confirmed.**

### HIGH

---

#### H-01 — bcrypt_pbkdf and seed derivation run on the main thread in several user flows (ANR)

| Field | Detail |
| --- | --- |
| **Severity** | High |
| **Category** | Performance / Stability (C, H) — *Tracked as H15* |
| **Files / Lines** | `ui/newaccount/NewAccountViewModel.kt:23-27`; `ui/accounts/AccountDetailViewModel.kt:61-62` called from `ui/accounts/AccountDetailScreen.kt:155`; `ui/vaultkey/VaultKeyDetailScreen.kt:83-91` called from `:160`; `ui/secretcombinations/SecretCombinationsViewModel.kt:~67-71`; `ui/onboarding/createvaultkey/CreateVaultKeyViewModel.kt:304-311` called from `CreateVaultKeyBackupIntroScreen.kt:95-96` (`rememberCoroutineScope` runs on Main); `ui/onboarding/OnboardingViewModel.kt:109,121`; `CreateVaultKeyViewModel.kt:238`; `domain/BackupSourceAdapters.kt:34-41` |
| **Evidence** | `fun verifyPassphrase(...) { accountService.verifyVaultKeyPassphrase(...) ... }` is a plain function called from a click. `AccountService.verifyVaultKeyPassphrase` calls `SshKeyCodec.parse(...)` synchronously. `ExportPassphraseDialog` calls `onConfirmed(passphrase)` synchronously inside `TextButton.onClick`. `OnboardingViewModel.performSetupAfterPassphraseCheck` is a suspend function invoked from `viewModelScope.launch` (Dispatchers.Main.immediate) and calls `SshKeyCodec.parse` with no `withContext`. `DefaultBackupSourceFactory.createRunContext` is non-suspending and runs `SshKeyCodec.parse` plus three BIP-39/NIP-06 derivations. It is reached via `SetupService.performSetup` / `persistGeneratedVaultKeyAndBackup` / `retryVaultKeyBackup` from Main-dispatched ViewModel coroutines. |
| **Explanation** | `SshKeyCodec` itself documents that each bcrypt_pbkdf round costs about 8 ms on a desktop CPU. That puts 128 rounds at roughly 1 s and 8192 rounds at more than 1 minute, and phones are slower. Users can pick 8192 rounds in Settings, and imported keys can declare up to 8192. Only `VaultKeySession.unlock`, `AccountService.createAssociatedAccount` and parts of the create wizard move this work off the main thread. |
| **Impact** | UI freezes of several seconds at the default setting. At higher work factors, "App not responding" dialogs and possible force-closes appear exactly when users confirm passphrases, export keys, import a VaultKey or finish onboarding. |
| **Trigger** | (1) Settings → KDF rounds 1024+, create a VaultKey, then Settings → VaultKey Account → Export and enter the passphrase. (2) Paste an imported key with 2048+ rounds and tap Create. (3) Associated account → Export → confirm passphrase. Even at 128 rounds, a low-end device freezes visibly. |
| **Recommended fix** | Make every passphrase verification and export builder `suspend` and wrap it in `withContext(Dispatchers.Default)`. Turn `BackupSourceFactory.createRunContext` into `suspend` with `withContext(Dispatchers.Default)` so callers cannot misuse it. Change `ExportPassphraseDialog.onConfirmed` to an async callback with an in-progress state so the button cannot be pressed twice. As a guard, add a debug-only `StrictMode`/`Looper` check or a lint rule that rejects `SshKeyCodec.parse` on the main thread. |
| **Confidence** | High |
| **Verification** | Run a debug build with `StrictMode.ThreadPolicy.detectCustomSlowCalls()` and wrap `SshKeyCodec.parse` with `StrictMode.noteSlowCall`, or capture a Perfetto trace while exporting with 1024 rounds. Expect a main-thread slice of 5 s or more. Add ViewModel tests that use a `StandardTestDispatcher` for Main and assert no crypto runs on it (for example, inject a parser that records `Thread.currentThread()`). |

---

#### H-02 — Any configuration change (rotation, dark-mode toggle, resize, locale) relocks the vault, erases the passphrase and discards the navigation stack

| Field | Detail |
| --- | --- |
| **Severity** | High |
| **Category** | Android lifecycle / Compose state (D, E) |
| **Files / Lines** | `MainActivity.kt:80-82, 111-113, 115-124, 179`; `ui/auth/AppUnlockCoordinator.kt:114, 124-127, 167-175`; `AndroidManifest.xml:349-358` (no `configChanges`, no orientation lock) |
| **Evidence** | `val unlockCoordinator = remember(activity) { AppUnlockCoordinator(activity, ...) }`. The coordinator starts in `AppUnlockState.Checking` with `wasUnlocked = false`. `LaunchedEffect(unlockState) { if (unlockState !is AppUnlockState.Unlocked) gateViewModel.lockSession() }`. `onStop` skips `onBackground()` when `activity.isChangingConfigurations`, which shows the intent was to survive configuration changes, but the coordinator holding `wasUnlocked` is discarded along with the old Activity. The project uses no `rememberSaveable` or `SavedStateHandle` anywhere. |
| **Explanation** | On recreation a brand-new coordinator reports `Checking`. That (a) immediately runs `VaultKeySession.lock()`, which zeroes the passphrase in the `@Singleton` session, and (b) makes `onForeground()` treat the user as never unlocked and show the biometric prompt. `AvailableVaultApp` (and its `rememberNavController()`) leaves composition while locked, so the back stack is lost and the app restarts at `ROOT`. |
| **Impact** | Every rotation, system dark-mode change, font-scale change, foldable unfold or multi-window resize forces device authentication plus passphrase re-entry and drops the user's position. During the Create VaultKey "Creating/Syncing" step the session can be locked before `SetupService.withVaultPassphrase` takes its snapshot, which turns a healthy backup into "quorum not reached". On tablets and foldables the app is close to unusable. |
| **Trigger** | Unlock the app, open any screen, then rotate the device or toggle dark mode from Quick Settings. |
| **Recommended fix** | Move unlock state (`wasUnlocked`, `backgroundAt`, current state) out of the composition. Either host it in an Activity-scoped `ViewModel` (keeping only the `BiometricPrompt` binding in the Activity) or use a process-scoped holder driven by `ProcessLifecycleOwner`. Keep the passphrase lock tied to real lock transitions, not to the initial `Checking` state of a new instance. Hoist `NavHostController` above the lock gate, or restore it with `rememberSaveable`, so the back stack survives a re-lock. |
| **Confidence** | High |
| **Verification** | Compose UI or Robolectric test: unlock, then `ActivityScenario.recreate()`, then assert the state is still `Unlocked` and `VaultKeySession.isUnlocked == true`. Manually, enable auto-rotate and rotate on any screen. |

---

#### H-03 — Device-to-device transfer, or loss of the Keystore key, leaves the app in a dead-end state the user cannot recover from inside the app

| Field | Detail |
| --- | --- |
| **Severity** | High |
| **Category** | Platform / Data management / Recovery (E, G) — *Tracked as H10 (partial)* |
| **Files / Lines** | `AndroidManifest.xml:323` (`allowBackup="false"`, no `android:dataExtractionRules`; `res/xml/` does not exist); `crypto/KeystoreCryptoService.kt:46-62, 69-73` (decrypt returns `null` when the key is missing); `data/secure/SecureStorageService.kt:51, 98-100`; `ui/auth/VaultUnlockGateViewModel.kt:55-59`; `ui/root/RootScreen.kt:51-52`; `ui/accounts/LocalAccountBackupScreen.kt:71, 85-88, 97` |
| **Evidence** | With `targetSdk 36`, `allowBackup="false"` disables cloud backup but **does not disable device-to-device (D2D) transfer** on Android 12+. That requires `dataExtractionRules`. Transfer copies `shared_prefs/vault_secure_accounts_v2.xml` and the DataStore file, but not the Keystore key. `KeystoreCryptoService.decrypt` then returns `null`, `getVaultKey()` returns `null`, and the gate maps `!hasVaultKey` to `VaultUnlockGateState.Onboarding` (no passphrase prompt). `RootViewModel` still sees `setup_complete = true` in DataStore and routes to `ACCOUNTS`, or, if a pending local-backup ID exists, to `LocalAccountBackupExportScreen`. That screen blocks Back (`BackHandler { }`), shows "Account not found" and disables its only button (`enabled = account != null`). |
| **Explanation** | Records that cannot be read are treated as absent, while the routing markers stay authoritative. Nothing compares the two, and there is no reset path in the app (see M-05). Get Started → Recover VaultKey is never reached because `setupComplete` is true. |
| **Impact** | A user who moves to a new phone with Android's transfer tool gets an empty, non-functional vault. Creating accounts fails with "VaultKey account is unavailable", and in the pending-export case they are stuck permanently on one screen. Recovery requires knowing to clear app data in system settings, during the exact flow (device migration) this product is built to support. A corrupted or invalidated Keystore entry produces the same state. |
| **Trigger** | Set up the vault, then use "Copy apps & data" to a new device (Android 12+), or delete the `vault_secure_storage_key_v1` Keystore alias on a test device. |
| **Recommended fix** | (1) Add `android:dataExtractionRules` (API 31+) and `android:fullBackupContent="false"` (or exclude rules) so both cloud backup and D2D exclude `vault_secure_accounts_v2.xml` and `datastore/vault_app.preferences_pb`. (2) In the launch router, detect the inconsistent state where `setupComplete` is true but `getVaultKey() == null` while an encrypted `vault_key` preference exists. Route it to an explicit "This device can't read your vault, recover with your Secret Combinations" screen that clears local state. (3) Make every Back-blocking mandatory screen show an escape path when its subject record is missing. |
| **Confidence** | Medium-High (code path confirmed; D2D behavior from Android 12 behavior-change documentation; not reproduced on a device) |
| **Verification** | Robolectric: write the preference files, delete the Keystore alias (or inject a `KeystoreCryptoService` whose `decrypt` returns `null`), launch, and assert the user can reach recovery. Manually, run `adb shell bmgr` / a D2D transfer test with `adb shell bmgr backupnow --non-incremental` against a D2D transport, or use Pixel "Copy apps & data". |

---

#### H-04 — Release build is not signed, not built in CI and not tested after R8

| Field | Detail |
| --- | --- |
| **Severity** | High |
| **Category** | Gradle / Release configuration (I) — *Tracked as H1, H2* |
| **Files / Lines** | `app/build.gradle.kts:14-36` (no `signingConfigs`, `versionCode = 1`, `versionName = "0.0.1-dev.1"`); `.github/workflows/ci.yml:57-64` (only `testDebugUnitTest`, `assembleDebug`, `lintDebug`); `app/proguard-rules.pro:1-2` |
| **Evidence** | The `release` build type sets `isMinifyEnabled = true` and `isShrinkResources = true` but has no `signingConfig`. CI never runs `assembleRelease`, `lintRelease` or `bundleRelease`, and no instrumented tests run anywhere. The R8 rules rely on broad keeps plus several `-dontwarn` entries for "code we never reach" (sshd JMX, net.i2p EdDSA, slf4j) that no test exercises in a minified build. |
| **Explanation** | The artifact that will ship is the R8-processed release, and nothing in the repository proves it builds, launches, or keeps the reflection and serialization paths Quartz and kotlinx.serialization need. The `-keep class org.bouncycastle.** { *; }` rule also prevents shrinking of BouncyCastle, which adds several MB of DEX. |
| **Impact** | A release-only crash (a missing kept class, serializer stripped, or JNI binding renamed) would reach users unnoticed. Without signing-key custody there is no reproducible, verifiable release process. |
| **Trigger** | Building and installing the release variant. |
| **Recommended fix** | Add a signing config sourced from CI secrets or Play App Signing (never committed). Add `assembleRelease` + `lintRelease` + a smoke instrumented test (launch, unlock, create key with the test KDF, export) against the minified APK in CI. Narrow the BouncyCastle keep to the classes actually used (`Ed25519*`, `crypto.params`, `math.ec.rfc8032`). Set a real `versionCode`/`versionName` policy. |
| **Confidence** | High |
| **Verification** | `./gradlew :app:assembleRelease :app:lintRelease` in CI, then `adb install` and run the smoke test. Inspect `mapping.txt` and `usage.txt`. |

---

### MEDIUM

---

#### M-01 — Consolidation workers report success after source failures; queued retries wait for the next unlock

| Field | Detail |
| --- | --- |
| **Severity** | Medium |
| **Category** | Reliability / Background work (C, G) — *Tracked as H11* |
| **Files / Lines** | `background/BackupWorkers.kt:49, 60-71, 108-119`; `domain/AccountConsolidationEngine.kt:239-245, 535-541`; `data/kv/AppStorageService.kt` (`PendingSourceWrite.attemptCount`) |
| **Evidence** | Both workers return `Result.success()` after `engine.consolidate(...)` / `consolidateVaultKey(...)` whatever `ConsolidationResult.failures` or `VaultKeyBackupResult.quorumReached` say. Only a thrown exception leads to `Result.retry()`, and per-source failures never throw. `markPendingRetry` always writes `attemptCount = 1`. A targeted-account index failure records no pending entry at all (`:239-245`). A locked session returns `Result.failure()` (`:49`). |
| **Explanation** | Pending writes are only consulted by the freshness check, and only on the next post-unlock run. There is no backoff-driven retry while the app is still open. |
| **Impact** | A newly created account whose first backup failed (a relay timeout, say) stays without remote coverage until the user re-opens the app after a lock. If the device is lost in that window, the account is unrecoverable. The UI does show `BACKUP_FAILED`/`UNKNOWN`. |
| **Trigger** | Create an account while one relay or litesync server is unreachable, then keep the app in the foreground. |
| **Recommended fix** | Return `Result.retry()` when `failures > 0` or quorum is not reached, while the session is still unlocked and `runAttemptCount` is below a cap. Increment `attemptCount` in `upsertPendingSourceWrite`. Record a pending entry when a targeted index fails. |
| **Confidence** | High |
| **Verification** | Worker test with `TestListenableWorkerBuilder` and a fake engine that returns `failures = 1`, asserting `Result.retry()`. |

---

#### M-02 — "Export Account to Clipboard" places the private key on the clipboard without the sensitive flag and never clears it

| Field | Detail |
| --- | --- |
| **Severity** | Medium |
| **Category** | Security / Privacy (F) — *Tracked as H24* |
| **Files / Lines** | `ui/accounts/AccountDetailScreen.kt:161-176` |
| **Evidence** | `clipboard.setClipEntry(ClipData.newPlainText("Encrypted account key", LocalAccountBackupShare.accountKeyPemText(account)).toClipEntry())`. No `ClipDescription.EXTRA_IS_SENSITIVE` extra (API 33+) and no timed clear. |
| **Explanation** | The payload is the passphrase-encrypted OpenSSH private key. Without the sensitive flag, Android 13+ shows the content in the clipboard preview overlay, and IME clipboard-history and cloud-sync features (Gboard, Samsung) may keep and sync it indefinitely. |
| **Impact** | The encrypted key, the target of an offline brute-force attack on the passphrase, leaks into stores outside the app's control. The passphrase policy (zxcvbn ≥ 3, bcrypt) limits but does not remove the risk. `FLAG_SECURE` does not cover the clipboard. |
| **Trigger** | Account Details → Export Account to Clipboard. |
| **Recommended fix** | Set `PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }` (and the `android.content.extra.IS_SENSITIVE` key on API < 33). Schedule a clear after about 60 s if the clip is still ours. Consider dropping the clipboard export in favor of the Notes share intent. |
| **Confidence** | High |
| **Verification** | On Android 13+, copy and observe the preview overlay showing key text. A unit test can assert the `ClipDescription` extras. |

---

#### M-03 — Every unlock blocks the UI on a fresh HTTPS time check (up to ~20 s), and the vault is unusable offline

| Field | Detail |
| --- | --- |
| **Severity** | Medium |
| **Category** | Performance / UX / Networking (D, G, H) |
| **Files / Lines** | `MainActivity.kt:159-170`; `ui/availability/AppAccessGuard.kt:51, 71, 73-90, 99-102, 141, 204-208` |
| **Evidence** | `AppAccessGuard` is created with `remember(context)` inside `AvailableVaultApp`. That composable is torn down on every lock (H-02), so each unlock builds a new guard, a new `RemoteOperationPrecondition` and a new `OkHttpClient`. `initialState()` returns `hasCompletedCheck = false`, so `canUseApp` stays false until a `HEAD` to `www.google.com` (10 s call timeout) and then `cloudflare.com` (10 s) completes. With no validated network, navigation is blocked entirely. |
| **Explanation** | The offline block is documented intent (`docs/stakeholder/screens-and-states.md:39`). However, it also stops purely local, read-only actions such as viewing accounts or exporting a key to Notes, and the per-unlock network round trip comes from where the guard is placed, not from policy. |
| **Impact** | On slow, captive or filtered networks (for example where google.com is blocked), every app open shows a blocking screen for up to about 20 s. Users without connectivity cannot reach their own SSH keys. |
| **Trigger** | Open the app on a slow or filtered network, or rotate the device (H-02). |
| **Recommended fix** | Hoist the guard to a process-scoped singleton that caches the last successful check (with a TTL and invalidation on `ACTION_TIME_CHANGED`). Run the time check in parallel across sources. Gate only remote operations, not local browsing or export, or make the offline block a product decision recorded with its trade-off. Reuse a single `OkHttpClient`. |
| **Confidence** | High (code path); Medium (the latency depends on the network) |
| **Verification** | Throttle the network (`adb shell cmd connectivity` / emulator network delay) and time from unlock to the Accounts list. |

---

#### M-04 — Storage write amplification and repeated Keystore decryption during consolidation; storage reads on the main thread at startup

| Field | Detail |
| --- | --- |
| **Severity** | Medium |
| **Category** | Performance / Battery / Database (H, G) |
| **Files / Lines** | `domain/AccountConsolidationEngine.kt:598-620` (`setStatus`); `data/kv/AppStorageService.kt:261-266`; `data/secure/SecureStorageService.kt:43-52, 72-75, 114-121`; `keyhive/AssociatedAccountsProvider.kt:33-37` |
| **Evidence** | Each `setStatus` call (1) collects the full `accountSourceStatuses` flow and JSON-decodes the whole list, then (2) runs `upsertAccountSourceStatus`, which decodes it again, edits one entry and re-encodes and rewrites the whole list to DataStore. A full cycle calls `setStatus` about 4× per account per source (checking, indexed, refreshed, verified). Separately, `SecureStorageService.addAccount` calls `publishAccounts()`, which re-decrypts every record through the Keystore, so restoring N accounts costs O(N²) Keystore operations. The `SecureStorageService` constructor decrypts every record. Its first resolution happens in `AssociatedAccountsProvider.onCreate`, which runs on the main thread before `Application.onCreate` on every cold start, including WorkManager-initiated starts. |
| **Explanation** | At the documented ceiling (99 accounts × 4 sources) one cycle performs about 1,600 full-list rewrites of a status blob of roughly 80–100 KB, which is on the order of 100+ MB of serialization and flash writes per cycle. A 99-account restore performs about 5,000 Keystore decryptions. |
| **Impact** | Battery drain, flash wear and long consolidation runs for heavy users. Main-thread Keystore I/O adds startup latency and contributes to H-01 jank on screens that call `getAccount`/`getAccounts` from Main (`AccountDetailViewModel.kt:36`, `AccountsViewModel.kt:35`, `LocalAccountBackupViewModel.kt:25-28`). |
| **Trigger** | Many accounts plus a full consolidation or restore on a new device. |
| **Recommended fix** | Batch status updates per source into one `dataStore.edit`, or move statuses to Room with one row per (account, source). In `restoreMissingAccounts`, write all restored accounts and then publish once. Make `SecureStorageService` initialization lazy and run it on `Dispatchers.IO`, and avoid touching it in `ContentProvider.onCreate`: resolve the entry point lazily in `query`. |
| **Confidence** | High (code path); Medium (magnitude estimates) |
| **Verification** | Engine test with a counting fake `DataStore` and 99 × 4 inventory, asserting the number of writes. A Perfetto trace of a cold start with 99 accounts. |

---

#### M-05 — No way to delete an account, reset the vault, or remove remote copies

| Field | Detail |
| --- | --- |
| **Severity** | Medium |
| **Category** | Privacy / Product completeness (F, G) — *Tracked as H21* |
| **Files / Lines** | `data/secure/SecureStorageService.kt:61-64, 85-93` (`removeVaultKey`, `removeAccount`, `clearAll` have **no callers**); `bravesync/BravePasswordService.kt:45` (`delete` unused); no Nostr deletion (NIP-09) path; `docs/features/associated-account-management.md:46` |
| **Evidence** | `grep` finds no production call to any removal API. Settings offers only VaultKey Account, Secret Combinations and KDF rounds. |
| **Explanation** | Account labels are published as plaintext `label` tags on public relays (`BackupService.kt:56-58`, accepted decision D4) and as Brave notes. Users have no in-app way to withdraw them or to wipe a device before handing it over. Without a reset, H-03's dead end has no in-app exit either. |
| **Impact** | Privacy and regulatory exposure (erasure requests, Play account-deletion expectations), plus the support burden described in H-03. |
| **Recommended fix** | Add a "Reset this device" action (wipe secure storage, DataStore and the Keystore alias, then cancel work) and an account-delete flow that issues NIP-09 deletion events and Brave tombstones. Document the limits of relay deletion. |
| **Confidence** | High |
| **Verification** | Code search; product acceptance test. |

---

### LOW

---

#### L-01 — Credential writes use asynchronous `SharedPreferences.apply()` while routing markers use durable DataStore
- **Category:** Data consistency (G)
- **File / Line:** `data/secure/SecureStorageService.kt:62, 86, 91, 95-96` (`preferences.edit { … }` defaults to `apply()`); paired with `domain/SetupService.kt:66-67`, `ui/newaccount/NewAccountViewModel.kt:33-35`
- **Evidence / Explanation:** `addVaultKey`/`addAccount` return before the disk write, and the next statement persists a DataStore marker (`CreateVaultKeyStep.BACKUP_INTRO`, the pending local-backup ID) that DataStore flushes to disk before completing. If the process is killed inside that window, the marker survives without its record, which produces the H-03 dead end.
- **Impact:** Rare, but it hits the most critical record and leaves the user stuck.
- **Fix:** Use `edit(commit = true)` on the IO dispatcher for secure records, or order writes as record-commit then marker.
- **Confidence:** Medium. **Verification:** Fault-injection test that kills the process between the two writes (instrumented), or code review of the ordering.

#### L-02 — Brave Sync transport reads unbounded bodies and decompresses gzip without limits, and builds a new `OkHttpClient` per source instance
- **Category:** Networking / Robustness (G, H) — *Tracked as H14*
- **File / Line:** `bravesync/transport/BraveSyncTransport.kt:18, 37, 41-42`; instantiated per run at `domain/BackupSourceAdapters.kt:56-58` and `domain/VaultKeyRecoveryService.kt:151-153`
- **Evidence:** `response.body.bytes()` and `GZIPInputStream(...).readBytes()` have no size cap. `client: OkHttpClient = OkHttpClient()` is the default parameter.
- **Impact:** A compromised or misbehaving sync server can trigger an OOM crash (a gzip bomb) during background consolidation. Each `OkHttpClient` carries its own connection pool and dispatcher threads, so connections are not reused.
- **Fix:** Cap the response body and decompressed size (for example 8 MB) with a bounded stream. Inject a shared `@Singleton OkHttpClient`.
- **Confidence:** High (code); Low (likelihood, since TLS-protected first-party endpoints are fixed).

#### L-03 — Interrupted runs leave statuses stuck at "Checking" / "Backing up"
- **Category:** Data consistency / UI state (D, G)
- **File / Line:** `domain/AccountConsolidationEngine.kt:351, 435` (marks set before network); `ui/accounts/AccountDetailScreen.kt:321-322, 369-370`
- **Explanation:** WorkManager cancellation (`APPEND_OR_REPLACE`, constraint loss) or process death between `markChecking`/`markBackingUp` and the outcome leaves an in-progress state persisted, and it is displayed until the next run.
- **Fix:** On run start, reset stale in-progress states older than the current `ConsolidationCheckpoint`, or render them as "Unknown" when no run is active.
- **Confidence:** High.

#### L-04 — The VaultKey "Retry" action discards its result; export dialogs have no busy state
- **Category:** UI error and loading states (D)
- **File / Line:** `ui/vaultkey/VaultKeyDetailScreen.kt:70` (`viewModelScope.launch { setupService.retryVaultKeyBackup() }`, `Result` ignored); `ui/accounts/ExportPassphraseDialog.kt:89-99`
- **Impact:** The user gets no success or failure feedback for the retry. Repeated taps on Confirm during a slow verification queue up repeated bcrypt calls (made worse by H-01).
- **Fix:** Expose a retry state and render it. Disable Confirm while verifying.
- **Confidence:** High.

#### L-05 — Account Details briefly shows "Account not found" on every open
- **Category:** Compose UI state (D)
- **File / Line:** `ui/accounts/AccountDetailViewModel.kt:53` (initial `AccountDetailState()` with `account = null`), `ui/accounts/AccountDetailScreen.kt:65, 80-91`
- **Explanation:** The id is set by `LaunchedEffect` after the first composition, so the first frames render the error branch. The same pattern appears in `LocalAccountBackupExportScreen` (`:85-88`).
- **Fix:** Add a `loading` state, or pass the id through `SavedStateHandle` in the ViewModel constructor.
- **Confidence:** High.

#### L-06 — Dead or test-only production code with misleading semantics
- **Category:** Maintainability (A, B)
- **Files:** `domain/RecoveryService.kt` (no callers; `decodeAssociatedAccount` re-runs bcrypt per event at `:50`); `domain/BackupService.kt:18-28` (`backupVaultKey`/`backupAssociatedAccount` unused); `domain/BackupSourceRepository.kt:14-56` (`addCustom`/`setEnabled`/`setOrder`/`remove` used only in tests); `network/nostr/NostrRelayService.kt:195-214` (`fetchByAuthorAndKind` returns an empty list on timeout, which callers could read as "absent"); `crypto/Seedify.kt:53-54` (`fromSshKey` unused)
- **Impact:** Larger review surface, and a future caller could adopt the timeout-means-empty semantics that the engine deliberately avoids.
- **Fix:** Remove the dead code, or mark it `@VisibleForTesting`/`internal`. Make the gateway's default `fetchByAuthorAndKind` return `Result`.
- **Confidence:** High.

---

## 3. Final assessment

### 3.1 Executive summary

The codebase is carefully engineered in its domain and cryptography layers. Key containers are strictly validated, including the bcrypt work-factor band and the seed–public-key consistency check. Backup quorum requires a read-back. The passphrase is kept only in memory. `FLAG_SECURE` is set, the exported provider requires a signature-level permission, nothing is logged, and comments and docs are extensive.

The main risks are in the Android integration layer:

- expensive cryptography on the main thread (H-01)
- unlock state that does not survive configuration changes (H-02)
- dead-end states after device migration or Keystore loss (H-03)
- a release pipeline that has never produced a signed, minified, tested build (H-04)

None of these is a data-exposure vulnerability. Together they make ANRs, forced re-authentication and unrecoverable app states likely for real users.

### 3.2 Overall project quality

| Dimension | Assessment |
| --- | --- |
| Architecture | Good. Clear UI → ViewModel → domain → storage/network layering, Hilt throughout, interfaces for sources and gateways. Weak spots: lifecycle logic inside composables (`AppUnlockCoordinator`, `AppAccessGuard`), and a non-suspending `createRunContext` that hides heavy work. |
| Code quality | High readability and defensive validation. Some dead code (L-06). |
| Maintainability | Strong documentation; the `AGENTS.md` policy keeps docs in sync. |
| Stability | Weak in UI threading and lifecycle (H-01, H-02); otherwise sound. |
| Security | Good fundamentals (see 3.6). Gaps: clipboard handling (M-02), backup and D2D extraction rules (H-03). |
| Testing maturity | Moderate. 42 host-side test files (about 5.5k lines) cover crypto parity, codecs, the engine and most ViewModels. There are only 7 instrumented tests, no Compose UI tests, and no lifecycle, worker or release-build tests. |

### 3.3 Critical release blockers

None confirmed at **Critical** severity.

### 3.4 High-priority issues (fix before release)

1. **H-01:** main-thread bcrypt and derivation (ANR).
2. **H-02:** configuration change relocks the vault and erases session and navigation state.
3. **H-03:** D2D transfer or Keystore loss produces an unrecoverable app state.
4. **H-04:** no signed or minified release build verified in CI.

### 3.5 Medium and low-priority issues (in priority order)

M-01 workers report success on failure → M-02 clipboard sensitivity → M-05 no delete/reset → M-03 per-unlock blocking time check and offline lockout → M-04 storage write amplification and main-thread Keystore reads → L-01 `apply()` ordering → L-02 unbounded Brave responses → L-03 stale statuses → L-04 silent retry → L-05 "not found" flash → L-06 dead code.

### 3.6 Security assessment

**Sound:**

- **Storage encryption:** Keystore AES-256-GCM with a fresh IV per write and a versioned payload.
- **Passphrase handling:** never persisted, and zeroed in `finally` blocks for worker snapshots.
- **Key validation:** OpenSSH parsing bounds the work factor before running the KDF, and checks the public key against the seed.
- **Recovery binding:** `VaultKeyRecoveryService` binds recovered keys to the supplied words.
- **Exported provider:** signature permission, read-only, rejects selection and sort arguments, returns only the encrypted container and label.
- **Screen and input protection:** `FLAG_SECURE`, password keyboard type, clipboard cleared after paste-confirmation flows.
- **Biometric policy:** strong biometric, with a credential fallback that respects API differences.
- **Transport:** no cleartext traffic (default platform policy at targetSdk 36), and no secrets in logs.

**Notes and residual risks (not counted as defects):**

- A Brave public service key constant is hardcoded in `bravesync/BraveSyncConfig.kt:7`. It is documented as a public protocol constant and is allowlisted in Gitleaks. Confirm that classification with the server operators.
- By decision D1, the passphrase stays in memory while the app is backgrounded and after the Activity is destroyed, until the next foreground return past the grace period or process death. On a rooted or compromised device, memory inspection is the remaining exposure.
- Account labels are public on relays (D4), and same-signature apps can read encrypted keys without user authentication (D5). Both are accepted decisions and should appear in the Play Data Safety form and privacy policy.
- The clock-skew check uses HTTPS. A device whose clock is far enough off to fail TLS validation reports `TIME_UNVERIFIABLE`, which is allowed. Remote operations then fail with network errors rather than a "fix your clock" message. This is low impact.
- No certificate pinning for the first-party relay and sync endpoints. Optional hardening.

**Unresolved:** M-02, H-03 (backup and extraction rules), M-05 (erasure). No resolved-version CVE scan was done. Dependabot is configured, but run `dependencyCheck` or OSV on the resolved graph before release.

### 3.7 Stability and performance assessment

- **ANR risk:** high (H-01), at all KDF settings on low-end devices and at any setting of 1024 rounds or more.
- **Lifecycle:** configuration-change handling is broken (H-02). Process death is handled deliberately: durable step markers resume the wizards, and the session re-locks.
- **Concurrency:** the engine serializes runs with a `Mutex`. `VaultKeySession` is mutex-guarded. The `AppUnlockCoordinator` fields are mutated only on the main thread. A minor stale-publish race exists in `SecureStorageService.publishAccounts()` (`_accounts.value = readAccounts()` from concurrent writers can publish an older list last). It has low impact because the list is re-read on the next mutation.
- **Performance:** M-03 (blocking network check on each unlock) and M-04 (write amplification, O(N²) Keystore decryption, main-thread storage initialization).
- **Memory:** unbounded Brave response handling (L-02). Otherwise there are no long-lived leaks: `AppAccessGuard` unregisters callbacks in `onDispose`, and Nostr clients are closed per operation in `withClient`.

### 3.8 Missing tests

| Area | Scenarios to add |
| --- | --- |
| Lifecycle | `ActivityScenario.recreate()` keeps the unlocked state and session; the background > 30 s re-lock; the API 28–29 credential fallback (instrumented). |
| Threading | Assert that `SshKeyCodec.parse` never runs on Main for export, verify, import, create and retry paths. |
| Workers | `AccountConsolidationWorker`/`VaultKeyConsolidationWorker`: locked session → failure; source failures → retry; cancellation zeroes the passphrase. |
| Storage integrity | Keystore alias missing or corrupted record → routing reaches recovery or reset; pending local-backup ID with no record. |
| Release | Smoke instrumented test on the minified release APK (launch, unlock, create with the minimum KDF, export intent). |
| ContentProvider | Runtime test that calls without the permission are rejected, plus malformed URIs (only a contract test exists today). |
| Networking | `BraveSyncTransport` with a fake server: oversized or gzip-bomb body, non-2xx, truncated protobuf. `NostrRelayService` AUTH and timeout behavior with a local WebSocket server. |
| Compose UI | Back-blocked mandatory screens always offer a path out; the export dialog busy state; the blocked-access screen. |
| ViewModels without tests | `VaultUnlockGateViewModel`, `NewAccountViewModel`, `AccountDetailViewModel`, `SettingsViewModel`, `VaultKeySession`. |

### 3.9 Areas that appear healthy

- `SshKeyCodec`: strict OpenSSH v1 parsing, a work-factor band checked before the KDF, block-alignment and padding checks, seed/public-key consistency, and derived-key zeroing.
- `KeystoreCryptoService`: non-exportable AES-GCM, explicit IV handling, and versioned payload parsing that fails closed.
- `SecureStorageService`: the registry-free design removes the earlier lost-update race. Each mutation is a single preference edit, and records are authenticated against their key.
- `AccountConsolidationEngine`: "unknown is not missing" is enforced, writes are read back before counting toward quorum, and Vault Key and account identities are kept separate.
- `VaultKeyRecoveryService`: tries candidates newest first, checks ownership against the supplied words, and distinguishes unreachable sources from absent backups.
- `AssociatedAccountsProvider`: minimal surface, signature permission, and canonical id parsing.
- `AppUnlockCoordinator` policy objects: explicit authenticator sets per API level, with the policy unit-tested.
- CI security tooling: Gitleaks and Semgrep with SHA-pinned actions, and Dependabot with crypto dependencies excluded from batched updates.

### 3.10 Recommended fixing order

1. **H-01:** move all bcrypt and derivation off Main (mechanical, high payoff). Add the threading tests.
2. **H-02:** hoist unlock state out of the composition, and stop locking on a new instance's `Checking` state. Add a recreate test.
3. **H-03:** add `dataExtractionRules`, add inconsistent-state detection with a recovery or reset route, and give back-blocked screens a way out. Pair this with **M-05** (a reset action).
4. **H-04:** signing config, release build and lint in CI, a minified smoke test, and narrower R8 keeps.
5. **M-01:** worker retry semantics and an incrementing `attemptCount`.
6. **M-02:** sensitive clipboard flag and auto-clear.
7. **M-03 / M-04:** process-scoped access guard with caching; batched status writes and lazy, off-Main storage initialization.
8. **L-01 … L-06** as time allows.

### 3.11 Release recommendation

**READY AFTER REQUIRED FIXES**

No critical security vulnerability or data-exposure defect was confirmed, and the security architecture is above average for an app at this stage. However, H-01 through H-04 are confirmed defects that would affect ordinary users in ordinary situations: confirming a passphrase, rotating the phone, moving to a new device, and installing the shipped build. They should be fixed and regression-tested before release, with M-01 and M-02 strongly recommended in the same cycle.

This recommendation is limited by the review scope. Nothing was compiled, executed or device-tested; third-party library internals and resolved-dependency CVEs were not assessed; and some UI files were only skimmed (see §0). A release-variant build, a device test pass on API 26/28/29/34+ and a dependency vulnerability scan are still required before the release decision.
