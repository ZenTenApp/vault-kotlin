# Unified Android Production Readiness Audit — ZenTen Vault

- **Application:** ZenTen Vault (`app.zenten.vault`), single `:app` module
- **Reviewed revision:** working tree on `main` at `01f3d2b`, `versionName 0.0.1-dev.1`, `versionCode 1`
- **Consolidation date:** 2026-11-11
- **Input reports (each read in full, none modified):**
  - `audits/round2/production-readiness-review-2026-11-10-gpt-5-6-terra.md` — 173 lines, 4 findings
  - `audits/round2/production-readiness-review-2026-11-10-opus-5-5.md` — 378 lines, 15 findings
  - `audits/round2/production-readiness-review-2026-11-10-deepseek-v4.1-flash.md` — 698 lines, 20 findings
- **Method:** independent static verification of every third-party claim against project source, Gradle files, manifest, R8 rules, CI workflows, tests and `docs/`. Project source took precedence over every AI assertion.
- **Result:** 32 surviving findings — 0 Critical, 5 High, 11 Medium, 16 Low. **Verdict: NOT READY FOR RELEASE.**

---

## 1. Executive Summary

ZenTen Vault stores an OpenSSH-format Ed25519 Vault Key and its associated account keys under an Android Keystore AES-256-GCM wrapper, and backs those records up to two Nostr relays plus two Brave Sync endpoints behind a "one relay + one Brave endpoint" quorum. The engineering behind that model is better than the average early-stage vault app: keys are never persisted in plaintext, the passphrase lives only in a mutex-guarded `CharArray` session that zeroes on lock, the exported `ContentProvider` is signature-permission protected, `FLAG_SECURE` is applied at the window, the consolidation engine performs write-then-read-back verification before claiming quorum, and the project's own documentation states its release limitations instead of overstating readiness.

Release is nonetheless blocked, and none of the blockers is incidental. Two of the five High findings are defects that every user would meet in normal use; the other three are properties of what the repository would ship:

1. **The bcrypt KDF still runs on the UI thread in six user-reachable paths** (H-01). The app already contains the correct pattern three times over (`VaultKeySession.unlock`, `createAssociatedAccount`, and the Secret Combinations loader all dispatch to `Dispatchers.Default`), which makes this an application error rather than a design limitation. With the accepted work-factor band topping out at 8,192 bcrypt rounds, the worst case is a multi-second freeze — past the Android ANR threshold.
2. **Every configuration change silently destroys the unlock coordinator and forces full re-authentication** (H-02), because the coordinator is `remember`ed per Activity and its "was unlocked" state is a plain field on the discarded instance. Rotating the device, or any locale/theme/dark-mode change, interrupts the user mid-flow.
3. **Android 12+ Device-to-Device transfer leads to an unrecoverable dead end** (H-03): `allowBackup="false"` leaves the Keystore-wrapped records behind, the app declares no `dataExtractionRules`, and the unlock gate then sees no Vault Key and routes to onboarding as if the install were fresh — while `setupComplete` and any pending-export marker still claim otherwise. The only offered path destroys the old identity rather than recovering it, and there is no reset or recovery screen (M-06).
4. **The release artifact is unsigned** (H-04) and **the release variant is never built, linted or installed by CI** (H-05). The project documents both gaps itself; `docs/technical/build-and-test.md:46` states plainly that the minified APK has not been smoke-tested on a device.

The Medium band is dominated by reliability and privacy-hygiene gaps rather than new exploit classes: workers report success when individual backup sources failed and the retry counter is inert (M-01), clipboard exports omit `EXTRA_SENSITIVE` so Android does not mark the copied encrypted key as sensitive (M-02), the app is unusable without network because a startup gate requires a fresh HTTPS time check (M-03), Keystore decryption and a quadratic restore loop run on the wrong threads (M-04, M-05), and there is no way to delete or reset anything (M-06) — which is also the missing escape hatch for H-03.

No confirmed Critical vulnerability was found. The cryptographic construction, the Keystore wrapping, the export gate, and the backup verification logic all held up under inspection; the defects are concentrated in threading, Activity/lifecycle state, release engineering, and error handling.

**Final recommendation: NOT READY FOR RELEASE** (see Section 16).

---

## 2. Audit Methodology and Limitations

### 2.1 What was done

1. **Read all three reports in full**, extracting each claim with its cited file and line.
2. **Normalized** claims into unique temporary identifiers by underlying technical problem — not by file, not by wording. Two reports naming the same non-suspend bcrypt path are one finding; one report naming two independent consequences of the same root cause may become two.
3. **Verified each claim against source.** Every file cited by any report was opened and the cited range read, plus surrounding context. Where a report's line number was slightly stale, the claim was re-anchored to the current line and the discrepancy noted rather than the claim being accepted or rejected on the line number alone.
4. **Reassessed severity independently** using the criteria in §2.3, then reconciled contradictions (§8).
5. **Checked for missing risks** by inspecting adjacent code the reports did not mention (for example, the consumer graph for `vaultKeyQuorumReached` and `attemptCount`, which changed two severities).
6. **Counted** the test corpus and configuration facts directly rather than trusting any report's counts.

Verification statuses used:

| Status | Meaning |
|---|---|
| **CONFIRMED** | The defect is present in source and its mechanism is fully traceable; no runtime behaviour is required to establish it. |
| **PARTIALLY CONFIRMED** | Part of the claim is real; another part is inaccurate, overstated, or already mitigated. |
| **UNVERIFIED** | Source could not establish the claim either way. |
| **FALSE POSITIVE** | Source contradicts the claim. |
| **NEEDS RUNTIME VALIDATION** | The code path is confirmed, but the severity or manifestation depends on platform/OEM/device behaviour that static analysis cannot settle. |

### 2.2 What was not done — and why

- **No build, lint, unit test or instrumentation run was executed by this audit.** The environment does contain a JDK (`/usr/lib/jvm/java-17-openjdk-amd64`, plus a JDK 21 under `~/.gradle/jdks/`) and the Android SDK is configured in `local.properties`, so this was a deliberate choice to keep the working tree untouched, not an environmental impossibility. (One input report claims no Java runtime existed in its environment; that statement does not apply here and was not inherited.)
- **No APK was produced or inspected with `apksigner`, no device or emulator was used, no Decompiled/R8 output was examined.**
- **Pre-existing test artifacts were read but are not presented as this audit's execution.** `app/build/test-results/testDebugUnitTest/` contains 40 XML result files dated 2026-11-10 22:22, which parse to 259 tests with 0 failures and 0 errors. These predate this audit and were produced by an earlier toolchain; they are cited only as evidence that a test corpus exists and previously passed.
- **No live Nostr or Brave Sync endpoint was contacted.** No network interoperability, quorum, restore, or D2D transfer was exercised.
- **No CVE/dependency advisory scan and no secret scan** were performed.
- **No `docs/` file was modified and no fix was implemented**, per the audit constraints.

Consequences for the reader: every finding below is a **static-analysis conclusion** unless it explicitly says otherwise. H-03 in particular is a code-path finding whose real-world impact depends on Android 12+ D2D behaviour and OEM implementation, and its verification label says so rather than pretending device evidence exists.

### 2.3 Severity criteria applied

- **Critical** — remotely triggerable compromise of vault confidentiality/integrity, or loss of user keys/data with no viable recovery.
- **High** — release-blocking defect: a production artifact cannot be built/distributed safely, a common user action reliably fails or ANRs, or a security control is defeated without unusual preconditions.
- **Medium** — a real defect with bounded impact, a workaround, or a precondition outside normal use; would ship in a mature app only with tracking.
- **Low** — correctness/hygiene issues, dead code, localized UX roughness, or hardening with no demonstrated failure path.

Severity was **not** inherited from the input reports, **not** promoted because multiple models agreed, and **not** downgraded because a single model found it. Where the input reports disagreed, §8 records the resolution and its reason.
---

## 3. Consolidated Findings Overview

### 3.1 Severity × verification matrix

Counts below are the authoritative totals for this report; Sections 4–7 must and do match them exactly.

| Severity | CONFIRMED | PARTIALLY CONFIRMED | NEEDS RUNTIME VALIDATION | UNVERIFIED | FALSE POSITIVE | **Total** |
|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 4 | 1 | 0 | 0 | 0 | **5** |
| Medium | 9 | 2 | 0 | 0 | 0 | **11** |
| Low | 15 | 1 | 0 | 0 | 0 | **16** |
| **Total** | **28** | **4** | **0** | **0** | **0** | **32** |

`UNVERIFIED` and `FALSE POSITIVE` counts are 0 among *surviving* findings: claims resolved to those statuses are recorded, with their resolution, in Section 9.

### 3.2 Finding index

| ID | Severity | Verification | Title | Reports |
|---|---|---|---|---|
| H-01 | High | CONFIRMED | bcrypt KDF runs on the UI thread in six reachable paths | 3/3 |
| H-02 | High | CONFIRMED | Configuration change destroys unlock state and forces re-authentication | 2/3 |
| H-03 | High | PARTIALLY CONFIRMED | D2D transfer + Keystore-wrapped store strands the vault behind first-run setup | 1/3 |
| H-04 | High | CONFIRMED | Release artifact is unsigned; no signing configuration exists | 3/3 |
| H-05 | High | CONFIRMED | Release variant is never built, linted, or smoke-tested in CI | 3/3 |
| M-01 | Medium | CONFIRMED | Workers report success when every backup source failed; retry counter is inert | 2/3 |
| M-02 | Medium | PARTIALLY CONFIRMED | Clipboard exports are not marked `EXTRA_SENSITIVE` to the OS | 1/3 |
| M-03 | Medium | CONFIRMED | App is unusable offline while the startup time check is outstanding | 2/3 |
| M-04 | Medium | CONFIRMED | Keystore decryption and full-store reads run on the UI thread | 2/3 |
| M-05 | Medium | CONFIRMED | Restore loop is O(N²) in Keystore operations; status writes re-read the whole status list | 2/3 |
| M-06 | Medium | CONFIRMED | No delete, reset, or remote-copy removal exists anywhere in the app | 1/3 |
| M-07 | Medium | CONFIRMED | Corrupt persisted JSON is silently read as an empty list | 1/3 |
| M-08 | Medium | PARTIALLY CONFIRMED | Brave Sync commit treats a reply with no entry results as success | 1/3 |
| M-09 | Medium | CONFIRMED | Unbounded response/relay payloads and a new HTTP client per operation | 2/3 |
| M-10 | Medium | CONFIRMED | Passphrase verification is unthrottled outside the creation wizard | 1/3 |
| M-11 | Medium | CONFIRMED | Backup engine has no dispatcher of its own and is called from non-main contexts | Derived |
| L-01 | Low | CONFIRMED | Setup completion and quorum evidence are written in two separate edits | 1/3 |
| L-02 | Low | CONFIRMED | Passphrase is copied into unzeroable `String`s inside the KDF path | 2/3 |
| L-03 | Low | CONFIRMED | Remote response text can reach user-facing error messages | 1/3 |
| L-04 | Low | CONFIRMED | Three `createdAt` sites use millisecond precision with no jitter | 1/3 |
| L-05 | Low | CONFIRMED | SSH key formatting and KDF work re-run on every recomposition | 1/3 |
| L-06 | Low | CONFIRMED | `SimpleDateFormat` allocated per list row | 1/3 |
| L-07 | Low | CONFIRMED | Launch router `combine` can throw on a malformed pending ID | 1/3 |
| L-08 | Low | CONFIRMED | Exported provider returns an item MIME type for a directory query | 1/3 |
| L-09 | Low | CONFIRMED | Dead and test-only production code paths | 1/3 |
| L-10 | Low | CONFIRMED | Supply-chain pinning is inconsistent (wrapper, CI actions) | 1/3 |
| L-11 | Low | PARTIALLY CONFIRMED | UI strings are split between resources and hardcoded literals | 1/3 |
| L-12 | Low | CONFIRMED | `CHECKING`/`BACKING_UP` statuses can persist indefinitely after a crash | 1/3 |
| L-13 | Low | CONFIRMED | Account detail flashes "Account not found" before its first emission | 1/3 |
| L-14 | Low | CONFIRMED | Retry actions discard their result and have no in-flight state | 1/3 |
| L-15 | Low | CONFIRMED | `failedRelays` mixes normalized and raw URLs in an unread field | 1/3 |
| L-16 | Low | CONFIRMED | Secure-record writes are async while the marker that depends on them is durable | 1/3 |

---

## 4. Critical Findings

**None.** No finding met the Critical bar as defined in §2.3.

This is a substantive result, not a formality, and it rests on specific properties that were checked rather than assumed:

- The stored-key wrapper is AES-256-GCM with a non-exportable Android Keystore key, version-tagged payloads, bounded IV handling, and authentication failures surfaced as decryption failure (`KeystoreCryptoService.kt:46-63`). There is no path where corrupted ciphertext is accepted.
- The OpenSSH container parser validates structure, the bcrypt band, padding, and re-derives the public key from the decrypted seed as an integrity check (`SshKeyCodec.kt:104-210`). Even though the private section itself is AES-CTR (unauthenticated), the seed↔pubkey agreement catches tampering at the only place that matters.
- The exported provider is `exported="true"` but gated by a `signature`-level permission with `grantUriPermissions="false"` (`AndroidManifest.xml:15-16, 39-44`), so a third-party app cannot read it.
- No secret is logged: `Log.`/`println` do not appear in production sources, and the one public Brave protocol constant is explicitly marked as non-secret and is not reproduced in this report.
- The one genuinely irreversible failure mode found (H-03) degrades availability, not confidentiality: the vault records stay intact on the device but the app offers no route back to them, which is serious but is not a compromise.

A Critical finding would have required, for example, a plaintext key at rest, an unauthenticated remote write path into local storage, or a `ContentProvider` reachable by an arbitrary app. None exists.

---

## 5. High Findings

### H-01 — bcrypt KDF runs on the UI thread in six user-reachable paths

- **Severity:** High
- **Verification:** CONFIRMED
- **Category:** Performance and stability / Coroutines
- **File:** `app/src/main/java/app/zenten/vault/domain/AccountService.kt` (root), plus the call sites below
- **Line:** `AccountService.kt:32-51`
- **Reported by:** GPT-5.6-terra (HIGH-02, primary), Opus-5.5 (H-01), DeepSeek-v4.1-flash (R-01) — 3 of 3 reports
- **Original references:** `HIGH-02`; `H-01`; `R-01`
- **Confidence:** High

**Evidence.** `AccountService` exposes two non-suspend entry points that synchronously execute the KDF:

```kotlin
// AccountService.kt:32
fun verifyVaultKeyPassphrase(vaultKeyPassphrase: String): Result<Unit> = runCatching {
    ...
    runCatching { SshKeyCodec.parse(vaultKey.zenTenKey, vaultKeyPassphrase.toCharArray()) }   // :35
```
```kotlin
// AccountService.kt:46
fun verifyAccountPassphrase(accountId: Long, passphrase: String): Result<Unit> = runCatching {
    ...
    runCatching { SshKeyCodec.parse(account.zenTenKey, passphrase.toCharArray()) }            // :49
```

`SshKeyCodec.parse` performs the bcrypt PBKDF at `SshKeyCodec.kt:200`:

```kotlin
BCrypt().pbkdf(String(passphrase).toByteArray(StandardCharsets.UTF_8), salt, rounds, derived)
```

The accepted work-factor band is 128–8192 rounds (`SshKeyCodec.kt:37` `MIN_BCRYPT_PBKDF_ROUNDS = 128`, `:48` `MAX_BCRYPT_PBKDF_ROUNDS = 8192`), and the file's own comment records roughly 8 ms per round.

Six distinct user-reachable paths reach a derivation on the main thread. Two of them go through the shared `AccountService` helpers above; the other four call the KDF directly:

| # | User-reachable path | Line | Mechanism |
|---|---|---|---|
| 1 | Import a Vault Key during onboarding | `OnboardingViewModel.kt:109` | `SshKeyCodec.parse(key, passphrase.toCharArray())` in `viewModelScope.launch`, before the session is established |
| 2 | Create an associated account | `NewAccountViewModel.kt:23-24` ← `NewAccountScreen.kt:114` | `accountService.verifyVaultKeyPassphrase(...)`, a plain function called from a Compose click handler |
| 3 | Export an account key (passphrase gate) | `AccountDetailViewModel.kt:61` ← `AccountDetailScreen.kt:148-155` | `accountService.verifyAccountPassphrase(...)`, invoked synchronously by `ExportPassphraseDialog.onConfirmed` |
| 4 | Submit the Secret Combinations passphrase | `SecretCombinationsViewModel.kt:70-71` | `accountService.verifyVaultKeyPassphrase(...)` in `viewModelScope.launch` |
| 5 | Export the Vault Key from Vault Key detail | `VaultKeyDetailScreen.kt:157-160` → `VaultKeyDetailViewModel.kt:83-92` | `LocalAccountBackupShare.vaultKeyExportText` → `SshKeyCodec.parse`, called synchronously by the dialog |
| 6 | Export the Vault Key from the creation wizard | `CreateVaultKeyBackupIntroScreen.kt:95-96` → `CreateVaultKeyViewModel.kt:304-311` | The function is `suspend`, but the caller is a Compose `rememberCoroutineScope()` on Main — suspending does not move the work |

The correct pattern already exists three times in this codebase: `VaultKeySession.kt:47` wraps the identical call in `withContext(Dispatchers.Default)`, `AccountService.createAssociatedAccount` (`:53`) does the same, and `SecretCombinationsViewModel.loadSecretCombinationIndices` (`:153-159`) dispatches its `SshKeyCodec.parse` to `Dispatchers.Default` — the same class of work the six sites above run on Main. The mechanism and the fix both exist in-repo, which is why this is an application error rather than a design constraint.

**Technical explanation.** `viewModelScope` and Compose click handlers dispatch on `Dispatchers.Main`. A non-suspend function called from either runs to completion on the main looper. `BCrypt.pbkdf` is pure CPU work with no suspension points, so the main thread is occupied for the entire derivation. At 128 rounds that is a few milliseconds; at 8192 rounds the project's own estimate (~8 ms/round) puts a single unlock in excess of one minute, and even a 1024-round container is seconds. The Android input-dispatch ANR window is 5 seconds.

**Production impact.** Frozen UI and dropped frames on every passphrase check at the top of the band; ANR on slower devices; a dead-looking app during key export. Because these derivations guard import, account creation, export and the Secret Combinations gate, the freeze lands on the screens a user reaches when they are most anxious about their keys.

**Trigger conditions.** Any passphrase verification or key export against a container whose work factor is above a few hundred rounds — reachable through normal product behaviour, since higher rounds are selectable and the parser accepts anything in the band.

**Root cause.** The verification API was shaped for synchronous use (`fun ... : Result<Unit>`) before the KDF cost was treated as a threading concern, and the call sites were written against that signature.

**Recommended fix.** Make both verifiers `suspend` and dispatch to `Dispatchers.Default` (mirroring `VaultKeySession.unlock`); change `ExportPassphraseDialog.onConfirmed` to a suspend-capable callback or have it drive a ViewModel function that exposes `isVerifying`/`error` state; disable the Confirm button while a derivation is in flight. Also move `OnboardingViewModel:109` into the same `Dispatchers.Default` block, and give the wizard export (`CreateVaultKeyBackupIntroScreen.kt:95`) a Main-safe suspend entry point that dispatches internally.

**Verification strategy.** Instrument a key at 8192 rounds; assert no frame over budget on the main thread (Perfetto/StrictMode), and that the Confirm button is non-interactive during derivation. Add a UI test that asserts the dialog shows a busy state rather than blocking.

---

### H-02 — Configuration change destroys unlock state and forces re-authentication

- **Severity:** High
- **Verification:** CONFIRMED
- **Category:** Android lifecycle / State management
- **File:** `app/src/main/java/app/zenten/vault/MainActivity.kt`; `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockCoordinator.kt`
- **Line:** `MainActivity.kt:80-82`; `AppUnlockCoordinator.kt:106-127`
- **Reported by:** Opus-5.5 (H-02), DeepSeek-v4.1-flash (R-02) — 2 of 3 reports. GPT-5.6-terra did not report it.
- **Original references:** `H-02`; `R-02`
- **Confidence:** High

**Evidence.**

```kotlin
// MainActivity.kt:80
val unlockCoordinator = remember(activity) {
    AppUnlockCoordinator(activity, ContextCompat.getMainExecutor(activity))
}
```
```kotlin
// AppUnlockCoordinator.kt:114
private val _state = MutableStateFlow<AppUnlockState>(AppUnlockState.Checking)
// :124-127
private var wasUnlocked = false
private var backgroundAt: Long? = null
private var promptRequested = false
private var credentialRequestInFlight = false
```

The manifest declares `MainActivity` with no `android:configChanges` (`AndroidManifest.xml:47-56`), so on rotation or a UI-mode change the Activity is destroyed and recreated and everything `remember`ed in its composition is discarded.

**Technical explanation.** `remember(activity)` is scoped to the composition, not to the Activity's retained state. A new Activity instance produces a new coordinator whose `_state` starts at `Checking` and whose `wasUnlocked` is `false`. The gate at `MainActivity.kt:126` therefore renders the locked path, and `AppUnlockPolicy.requiresAuthentication` (`AppUnlockCoordinator.kt:45-47`) returns `true` because `!wasUnlocked`. The user must pass biometric/credential again even though the vault was never backgrounded in the meaningful sense.

**Production impact.** Every rotation, locale change, theme/dark-mode switch, or font-size change re-locks the app mid-task. On a tablet or foldable — where configuration changes are routine — this effectively makes the app unusable for editing flows. It also interacts with H-03: a lock the user did not cause is alarming in a vault app, and repeated re-prompts train users to approve prompts reflexively.

**Trigger conditions.** Any configuration change while the app is in the foreground and unlocked.

**Root cause.** Security state that must outlive the composition was placed in composition-scoped storage with plain mutable fields instead of in a ViewModel / retained holder.

**Recommended fix.** Move the coordinator into a `ViewModel` (Activity-scoped via `viewModel()`) or hoist its state into `rememberSaveable`-backed values; keep the `ElapsedRealtime` grace period intact so a genuine background still relocks. Do **not** add `android:configChanges="orientation|screenSize|uiMode"` as the fix — that hides the bug and leaves process death, which the ViewModel also handles correctly.

**Verification strategy.** Rotate the device while unlocked and assert no authentication prompt appears; then background for longer than the grace period and assert a prompt does appear. Cover both in a UI test with `ActivityScenario.recreate()`.

---

### H-03 — D2D transfer + Keystore-wrapped store strands the vault behind first-run setup

> **Corrected 2026-11-12 — see [Appendix D](#appendix-d--errata-and-corrections-2026-11-12).**
> E-01 narrows the trigger set (the Keystore key is not auth-bound), E-02 shows the
> recommended detection condition is not implementable as written, E-03 corrects the
> user-visible outcome to an emptied account list rather than first-run setup, and E-04
> adds the confirmed mandatory-export lock. Section 16's closure gate is amended by E-05. Read
> the original text below as the audit's record at revision `01f3d2b`.

- **Severity:** High
- **Verification:** PARTIALLY CONFIRMED
- **Category:** Android lifecycle / Data persistence / Release readiness
- **File:** `app/src/main/AndroidManifest.xml`; `app/src/main/java/app/zenten/vault/crypto/KeystoreCryptoService.kt`; `app/src/main/java/app/zenten/vault/ui/root/RootScreen.kt`
- **Line:** `AndroidManifest.xml:20`; `KeystoreCryptoService.kt:46-63`; `RootScreen.kt:37-66`
- **Reported by:** Opus-5.5 (H-03) — 1 of 3 reports
- **Original references:** `H-03`
- **Confidence:** Medium-High (code path confirmed; the D2D behaviour rests on Android 12+ platform documentation, not on a device reproduction). **This audit did not reproduce it.**

**Evidence.** The manifest sets `android:allowBackup="false"` and declares no `android:dataExtractionRules` or `android:fullBackupContent` (`AndroidManifest.xml:19-26`). The store is encrypted under an Android Keystore key (`KeystoreCryptoService.kt:65-73` `getOrCreateKey`), and decryption returns `null` when the alias is absent:

```kotlin
// KeystoreCryptoService.kt:60
cipher.init(Cipher.DECRYPT_MODE, loadKey() ?: return null, GCMParameterSpec(TAG_BITS, iv))
```

`readAccounts()` and `read(VAULT_KEY)` therefore yield nothing, so the unlock gate decides the install is fresh and reports `Onboarding` (`VaultUnlockGateViewModel.kt:50-61`) — `MainActivity.kt:140-141` maps that state straight to `AvailableVaultApp()` with no passphrase prompt. Set against that, `setupComplete` is a plain DataStore flag that is not Keystore-wrapped and can still read `true`, and `RootViewModel.destination` would route such a user to accounts (`RootScreen.kt:52` `complete -> VaultDestinations.ACCOUNTS`). The app's durable state and the screen it shows disagree.

A second, statically-confirmed dead end sits on the mandatory export screen. `LocalAccountBackupScreen` installs `BackHandler { }` and passes `onBack = null` (`LocalAccountBackupScreen.kt:71-75`) because the export is required; if the pending-export marker survived while the account record did not (or could not be decrypted), the screen renders "Account not found" (`:85-87`) with no way forward and no way back. The router keeps sending the user there for as long as `pendingLocalBackupAccountIds` is non-empty (`RootScreen.kt:51`), and nothing clears that marker on this path.

**Technical explanation.** Since Android 12, `allowBackup="false"` does not fully opt an app out of all transfer paths; Google's behaviour-change documentation directs apps to declare `android:dataExtractionRules` to control cloud backup and device-to-device transfer separately. Android Keystore keys are hardware-bound and are never migrated by D2D. What arrives is therefore the classic split-brain state: whether the plain preferences travel depends on the transfer path, while the Keystore-encrypted records can never be decrypted on the new device.

The app has no branch for that combination. The gate has exactly three outcomes — unlocked, onboarding, passphrase required — and a vault that exists but cannot be decrypted is indistinguishable from a device with no vault, so the user is handed first-run setup. Completing it creates a new Vault Key identity; the previous records stay on disk, decryptable only by the old passphrase, with no in-app route back to them (M-06). If a pending-export marker survived instead, the user is held on the mandatory export screen, which deliberately offers neither back nor skip.

**What is only partial.** The D2D behaviour itself was not reproduced; OEM implementations vary, and `allowBackup="false"` may suppress transfer entirely on some OEMs. What is fully confirmed is (a) the absent extraction rules, (b) the `null`-on-missing-key decrypt, (c) the mismatch between durable setup state and what the unlock gate can see, and (d) the absence of any recovery action. The finding survives on (a)–(d) even if (b) never occurs on a given device, because a Keystore key can also be invalidated by lock-screen changes and OS-level key loss.

**Production impact.** A user who migrates phones is shown first-run setup. The path least likely to be taken — proceeding with it — replaces the vault identity while leaving the previous records unreachable on the same device, which turns an availability failure into apparent data loss. The correct path, recovering from remote backup, is not offered at all; the user must discover it themselves. No data is exfiltrated.

**Trigger conditions.** Android 12+ device-to-device transfer, or any event that invalidates the Keystore key (factory reset restore of app data, secure lock-screen credential change on some OEMs), followed by app launch.

**Root cause.** Two persistence backends with different survival semantics are trusted as if they were one consistent store, and no code path treats "Keystore alias missing but setup complete" as a distinct, recoverable state.

**Recommended fix.** (1) Declare `android:dataExtractionRules` (API 31+) with explicit transfer/backup policies and `android:fullBackupContent` for lower APIs. (2) Detect `setupComplete == true && vault key undecryptable` at startup and route to a dedicated recovery screen that offers remote recovery and an explicit "reset local vault" action. (3) Implement M-06 so that action exists.

**Verification strategy.** On an API 31+ emulator, create a vault, back it up, then simulate the alias loss by deleting the Keystore entry (or use D2D transfer between two emulators) and assert the app presents recovery rather than first-run setup. Record the OEM matrix used.

---

### H-04 — Release artifact is unsigned; no signing configuration exists

- **Severity:** High
- **Verification:** CONFIRMED
- **Category:** Gradle, dependencies and release configuration
- **File:** `app/build.gradle.kts`
- **Line:** `:24-35`
- **Reported by:** GPT-5.6-terra (HIGH-01), Opus-5.5 (H-04, part 1), DeepSeek-v4.1-flash (executive summary and release-engineering item; not raised as a numbered finding) — 3 of 3 reports
- **Original references:** `HIGH-01`; `H-04`; related DeepSeek release/configuration findings
- **Confidence:** High

**Evidence.** The `release` build type configures minification and shrinking but no signing:

```kotlin
// app/build.gradle.kts:28-31
release {
    isMinifyEnabled = true
    isShrinkResources = true
}
```

There is no `signingConfigs` block anywhere in the module, so `assembleRelease` emits `app-release-unsigned.apk`. The project documents this itself at `docs/technical/build-and-test.md:43` ("No release signing configuration ... the APK is unsigned").

**Technical explanation.** Android requires an APK signed with a release key for installation, update continuity, and Play App Signing enrolment. An unsigned artifact cannot be installed on a device without manual re-signing, which also breaks any later signature-based update path. The signature is additionally the trust anchor for the `signature`-level `VAULT_ACCESS` permission (`AndroidManifest.xml:15-16`) that protects the exported provider — so the signing identity is a security boundary, not only a packaging concern.

**Production impact.** No production distribution is possible from this repository configuration. Any ad-hoc re-signing creates a divergent signing identity and a permission-compatibility hazard for the companion app that consumes the provider.

**Trigger conditions.** Any release attempt.

**Root cause.** Release signing was never configured because no release artifact was ever produced through CI; the documentation records the gap as a known limitation rather than as a configuration error.

**Recommended fix.** Add a `signingConfigs.release` fed from environment/CI secrets (keystore path, alias, and both passwords injected, never committed), wire it into the `release` build type, and document key custody, rotation, and compromise response. Enrol in Play App Signing so the upload key is not the distribution key.

**Verification strategy.** `./gradlew :app:assembleRelease` then `apksigner verify --verbose --print-certs`; assert the certificate subject matches the documented release identity, and add a CI check that fails when the release artifact is unsigned.

---

### H-05 — Release variant is never built, linted, or smoke-tested in CI

- **Severity:** High
- **Verification:** CONFIRMED
- **Category:** Testing and release configuration
- **File:** `.github/workflows/ci.yml`
- **Line:** `:55-63`
- **Reported by:** GPT-5.6-terra (HIGH-03), Opus-5.5 (H-04, part 2), DeepSeek-v4.1-flash (release-engineering item; not raised as a numbered finding) — 3 of 3 reports
- **Original references:** `HIGH-03`; `H-04`; related DeepSeek CI findings
- **Confidence:** High

**Evidence.**

```yaml
# .github/workflows/ci.yml
- name: Run host-side unit tests
  run: ./gradlew :app:testDebugUnitTest --console=plain      # :56
- name: Assemble debug APK
  run: ./gradlew :app:assembleDebug --console=plain          # :59
- name: Run Android lint (debug)
  run: ./gradlew :app:lintDebug --console=plain              # :62
```

No job runs `assembleRelease`, `lintRelease`, or connected/instrumented tests against a release artifact. `docs/technical/build-and-test.md:46` states the minified build has not been smoke-tested on a device or emulator, and the R8 rules include broad keeps (`app/proguard-rules.pro:2` `-keep class org.bouncycastle.** { *; }`, `:6` secp256k1) plus serialization keeps (`:22-28`) that are exactly the pattern that fails only at runtime.

**Technical explanation.** R8, resource shrinking, reflection-based serialization, Hilt codegen, Quartz's Nostr client, and the JNI secp256k1 binding can all compile cleanly and fail at runtime under minification. Compilation validates symbol presence; it does not validate reflective lookup, `Class.forName` paths, or native symbol retention. Debug-only CI therefore cannot detect the most likely release-only failure class in this app.

**Production impact.** A green CI badge provides no evidence that the shippable artifact works. The first release-only defect would be discovered in production or during manual QA, not in CI.

**Trigger conditions.** Any regression that manifests only under minification/shrinking.

**Root cause.** CI was built to give fast feedback on the debug loop and was never extended to the artifact that ships; R8 correctness was assumed to follow from compilation.

**Recommended fix.** Add required jobs for `:app:assembleRelease` and `:app:lintRelease`; add an emulator smoke job that installs the minified APK and exercises launch → unlock → create account → consolidate → export; retain the artifacts and reports.

**Verification strategy.** Land a deliberate release-only reflective-lookup regression and confirm current CI passes; then confirm the new pipeline fails. Record a baseline device pass of the minified APK.

---

## 6. Medium Findings

### M-01 — Workers report success when every backup source failed; retry counter is inert

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Background work / Reliability
- **File:** `app/src/main/java/app/zenten/vault/background/BackupWorkers.kt`; `app/src/main/java/app/zenten/vault/domain/AccountConsolidationEngine.kt`
- **Line:** `BackupWorkers.kt:48-78`, `:107-126`; `AccountConsolidationEngine.kt:535-541`
- **Reported by:** Opus-5.5 (M-01, Medium), DeepSeek-v4.1-flash (R-09, Low) — 2 of 3 reports
- **Original references:** `M-01`; `R-09`
- **Confidence:** High

**Evidence.**

```kotlin
// BackupWorkers.kt:49-71
val passphrase = vaultKeySession.snapshot() ?: return Result.failure()
...
engine.consolidate(passphrase, trigger, force = trigger == ConsolidationTrigger.MANUAL)
Result.success()       // :71 — unconditional
```
```kotlin
// AccountConsolidationEngine.kt:540
PendingSourceWrite(id.value, source.id, reason, attemptCount = 1, lastAttemptAtMillis = clock.millis()),
```

Source-level failures are caught inside the engine and recorded as status rows; only an escaped exception reaches `Result.retry()` (`:78`). `attemptCount` is written as the constant `1` and has no reader anywhere in `src/main` (only the data-class declaration at `AppStorageService.kt:284`).

**Technical explanation.** The worker's result reports "did the coroutine finish", while the engine's `Tally` reports "did the backup succeed". Because source failures are recoverable by design, any run where all four endpoints refused still returns `Result.success()`. WorkManager therefore never applies backoff, and the failed state is visible only in app UI. The queue of pending writes is likewise drained on the next unlock rather than retried within the run, and `attemptCount` being both constant and unread means there is no mechanism to grow a delay or cap a persistently failing source.

**Production impact.** Backups can be entirely failing while the system reports healthy work. On a device that is rarely unlocked, a user can believe backups are current when no remote copy exists — the single most consequential reliability property of a backup app. Also, a source that fails deterministically is re-attempted at full rate forever.

**Trigger conditions.** Any run where every source is unreachable, rejects auth, or times out — routine on captive portals, in poor coverage, or after a relay outage.

**Root cause.** The success return value was coupled to coroutine completion rather than to the engine's quorum/tally outcome; `attemptCount` was designed but never wired.

**Recommended fix.** Have `consolidate`/`consolidateAccount` return an outcome the worker maps: total failure → `Result.retry()`; partial → success with a notification/status nudge. Persist an incremented `attemptCount` and use it for backoff (or cap retries and surface a persistent error).

**Verification strategy.** Stub all sources to fail; assert the worker returns `retry` and that `attemptCount` increments across runs. Add an engine test asserting a partial-success run still records `BACKUP_FAILED` rows.

---

### M-02 — Clipboard exports are not marked `EXTRA_SENSITIVE`

- **Severity:** Medium
- **Verification:** PARTIALLY CONFIRMED
- **Category:** Security and privacy
- **File:** `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailScreen.kt`
- **Line:** `:161-176`, `:269`
- **Reported by:** Opus-5.5 (M-02) — 1 of 3 reports
- **Original references:** `M-02`
- **Confidence:** High for the missing flag; the "never clears" half is not a defect (see below)

**Evidence.** Two clipboard writes carry encrypted private-key material as plain text:

```kotlin
// AccountDetailScreen.kt:163-167
clipboard.setClipEntry(
    ClipData.newPlainText(
        "Encrypted account key",
        LocalAccountBackupShare.accountKeyPemText(account),      // full OpenSSH PEM block
    ).toClipEntry(),
)
```
```kotlin
// AccountDetailScreen.kt:269 (CopyableIdentityValue, reusable for key text)
clipboard.setClipEntry(ClipData.newPlainText(clipboardLabel, value).toClipEntry())
```

`ClipDescription.extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)` is never set; `grep` for `IS_SENSITIVE` across `src/main` returns nothing.

**Technical explanation.** On API 33+ (`ClipDescription.EXTRA_IS_SENSITIVE`) and API 34+ (`ClipboardManager.setClip`), marking a clip sensitive tells the OS to suppress the clipboard preview toast and to withhold the content from apps that query the clipboard in the background. Without the flag the encrypted key block is treated as ordinary text: it appears in the system clipboard overlay and is readable by any app that polls the clipboard while in focus. The clipboard itself is not a secure channel in any case; the flag reduces passive exposure.

**What is *not* a defect.** The fact that the copied value is never auto-cleared is deliberate, documented behaviour (`docs/features/sensitive-key-access.md:29`): the export is a user-requested disclosure and clearing it would break the paste workflow. Reporting non-clearing as a bug would be a false positive, and the input report's framing of it as a defect was rejected (see Section 9).

**Production impact.** The encrypted key (protected by the account passphrase, but still a high-value offline-attack target) is copied to a clipboard that the OS treats as innocuous. Reduced user awareness of a sensitive copy, and broader availability to background clipboard readers on older API levels.

**Trigger conditions.** User taps "Export Account to Clipboard" or copies an identity/key value.

**Root cause.** The clipboard helper was written for general text values and was later reused for key material without revisiting its sensitivity metadata.

**Recommended fix.** Add a `sensitive: Boolean = false` parameter to the copy helper and set `EXTRA_IS_SENSITIVE` (plus `ClipDescription.MIMETYPE_TEXT_PLAIN` suppression) for key/PEM copies. Keep the documented non-clearing behaviour. Confirm that `FLAG_SECURE` still blocks the preview on the relevant API levels.

**Verification strategy.** On API 33+, copy an exported key and assert the system toast is suppressed and a test app in the background cannot read the clip.

---

### M-03 — App is unusable offline while the startup time check is outstanding

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Android lifecycle / Product behaviour
- **File:** `app/src/main/java/app/zenten/vault/ui/availability/AppAccessGuard.kt`; `app/src/main/java/app/zenten/vault/MainActivity.kt`
- **Line:** `AppAccessGuard.kt:51-63`, `:70-104`, `:181-196`; `MainActivity.kt:159-176`
- **Reported by:** Opus-5.5 (M-03), DeepSeek-v4.1-flash (R-06) — 2 of 3 reports
- **Original references:** `M-03`; `R-06`
- **Confidence:** High

**Evidence.**

```kotlin
// AppAccessGuard.kt:51
val canUseApp: Boolean get() = hasCompletedCheck && blockers.isEmpty()
```
```kotlin
// MainActivity.kt:172
if (!accessState.canUseApp) { AppBlockedScreen(... onRetry = { accessGuard.refresh(showChecking = true) }) }
```

`AppAccessPolicy.evaluate` maps `NO_VALIDATED_INTERNET` to a blocker, and `RemoteOperationPrecondition.check()` returns that value whenever `ConnectivityManager` reports no validated network. `ServerTimeClient.fetchServerTimeMillis` issues sequential `HEAD` requests to two hardcoded HTTPS origins with a 10 s call timeout (`:70-104`).

**Technical explanation.** The gate is a *local* UI gate fed by a *remote* precondition. With no network there is no validated internet by definition, so the blocker is set synchronously and the user never reaches the vault. The time check exists to prevent generating date-derived Brave Sync identities from a wrong clock — a genuinely important property — but it is applied to reading local, already-decrypted data as well as to remote mutation.

**Production impact.** The vault — whose value proposition includes offline access to one's own keys — is unavailable in airplane mode, on a plane, in a subway, or during an ISP outage. A user who needs a key precisely when connectivity is unavailable cannot get it. `TIME_UNVERIFIABLE` is handled correctly (`AppAccessPolicy` allows it), so the hard failure is specifically the no-network case.

**Trigger conditions.** Launch with no validated network.

**Root cause.** One prerequisite policy is used for both "may we mutate remote state" and "may the user read local state", conflating two different risk profiles.

**Recommended fix.** Split the gate: require `RemoteOperationReadiness.READY` (or `TIME_UNVERIFIABLE`) for remote operations, and allow local browsing/export with a warning banner plus a persisted "last known good time" so date-derived identity generation remains guarded.

**Verification strategy.** Launch in airplane mode and assert account list/detail and export are reachable with a banner; assert remote actions remain disabled with an explanatory message.

---

### M-04 — Keystore decryption and full-store reads run on the UI thread

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Performance and stability
- **File:** `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailViewModel.kt`; `AccountsViewModel.kt`; `keyhive/AssociatedAccountsProvider.kt`; `data/secure/SecureStorageService.kt`
- **Line:** `AccountDetailViewModel.kt:25-58`; `AccountsViewModel.kt:32-37`; `AssociatedAccountsProvider.kt:43`; `SecureStorageService.kt:44`, `:82`, `:114-117`
- **Reported by:** Opus-5.5 (M-04 part), DeepSeek-v4.1-flash (R-05) — 2 of 3 reports
- **Original references:** `M-04`; `R-05`
- **Confidence:** High

**Evidence.**

```kotlin
// AccountDetailViewModel.kt:29-30 — inside combine {} on the collector's context (Main)
val account = id?.let(accountService::getAccount)          // → SecureStorageService.readAccounts()
```
```kotlin
// SecureStorageService.kt:82
fun getAccounts(): List<Account> = readAccounts()         // decrypts every record, every call
```
```kotlin
// AssociatedAccountsProvider.kt:43 — ContentProvider.query runs on the binder thread of the caller's process
ACCOUNTS -> jsonCursor(Json.encodeToString(storage.getAccounts().map { it.createdAt.toString() }))
```

`readAccounts()` (`SecureStorageService.kt:114-117`) iterates all keys and calls `crypto.decrypt` for each, which performs a Keystore AES-GCM operation. The `AccountDetailViewModel.state` `combine` re-executes on every account-status emission, so each status write re-decrypts the full store on the main thread.

**Technical explanation.** `stateIn(viewModelScope, ...)` collects on `Dispatchers.Main.immediate` by default; `combine`'s transform block runs on the collector's context, so `accountService.getAccount` — and therefore the Keystore decrypt — happens on the main looper. Hardware-backed Keystore operations have a nontrivial fixed cost (tens of milliseconds, worse under strongbox/TPM arbitration) and N of them per emission makes account-list navigation janky and, on low-end devices, visibly janky.

**Production impact.** Dropped frames and sluggish list/detail navigation proportional to account count; a "sticky" UI whenever the consolidation engine emits status updates (which is exactly when the user is watching backup status). The provider path additionally blocks whichever app bound to it.

**Trigger conditions.** Opening the account list or detail with a non-trivial number of accounts; any status update while a detail screen is open.

**Root cause.** `SecureStorageService` exposes synchronous, decrypt-on-every-call accessors, and ViewModels call them directly from a Main-context flow transform.

**Recommended fix.** Cache decrypted records in the in-memory `MutableStateFlow` that already exists (`_accounts`) and have `getAccounts`/`getAccountById` read the cache; expose `Flow<List<Account>>` to ViewModels and use `flowOn(Dispatchers.IO)` or `stateIn(..., viewModelScope, ...)` with an explicit `Dispatchers.Default` upstream. Do the same for the provider (`query` should not decrypt the whole store).

**Verification strategy.** Seed 99 accounts; assert no main-thread Keystore calls via StrictMode (`detectCustomSlowCalls`/`detectDiskReads`) and measure frame timings while statuses update.

---

### M-05 — Restore loop is O(N²) in Keystore operations; status writes re-read the whole status list

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Performance and stability / Data persistence
- **File:** `app/src/main/java/app/zenten/vault/domain/AccountConsolidationEngine.kt`; `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
- **Line:** `AccountConsolidationEngine.kt:378-395`, `:598-620`; `SecureStorageService.kt:95-96`
- **Reported by:** Opus-5.5 (M-04 write amplification), DeepSeek-v4.1-flash (R-05-adjacent) — 2 of 3 reports
- **Original references:** `M-04`; `R-05`
- **Confidence:** High

**Evidence.**

```kotlin
// AccountConsolidationEngine.kt:389-394
if (recovered != null) {
    secureStorage.addAccount(recovered)      // decrypt-all + re-encrypt-all + publish
    tally.restored++
    markIndexedPresent(id, config)           // DataStore upsert + full status read
}
```
```kotlin
// SecureStorageService.kt:95-96 — addAccount rewrites the entire collection and republishes
preferences.edit { putString(key, crypto.encrypt(json.encodeToString(account))) }
```

`setStatus` (`:598-620`) fetches `appStorage.accountSourceStatuses.first()` on every call to preserve prior timestamps, then upserts one row via `upsertAccountSourceStatus`, which itself decodes the full list (`AppStorageService.kt:185`).

**Technical explanation.** Restoring R accounts across S sources performs on the order of R × S full-store decrypt/re-encrypt cycles, plus R × S full status-list decode/encode cycles. With the account ceiling at 99 and four sources, a single worst-case consolidation can perform several hundred full-collection Keystore operations and DataStore writes. Each `preferences.edit {}` is a synchronous commit on the calling thread of a full JSON blob.

**Production impact.** Long consolidation runs (hundreds of milliseconds to seconds of serialized work), battery wake time, and jank if any of it lands on Main. On a large vault this is the dominant cost of a restore, and it happens precisely when the user is anxious that their data is missing.

**Trigger conditions.** A restore that recovers many accounts; any run over a large inventory.

**Root cause.** A per-record API (`addAccount`) implemented as a whole-collection rewrite, combined with per-status reads of the full status list.

**Recommended fix.** Add a batch write path (`addAccounts(List)`) that decrypts once, merges in memory, encrypts once and publishes once; give `setStatus` an in-memory status cache maintained across the run; use a single DataStore edit per source at the end of a phase.

**Verification strategy.** Benchmark a restore of 99 accounts × 4 sources and assert the Keystore op count is O(N), not O(N²), with a test double counting `decrypt` calls.

---

### M-06 — No delete, reset, or remote-copy removal exists anywhere in the app

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Product / Security and privacy / Recovery
- **File:** `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`; `bravesync/BravePasswordService.kt`
- **Line:** `SecureStorageService.kt:61-93`; `BravePasswordService.kt:45`
- **Reported by:** Opus-5.5 (M-05) — 1 of 3 reports
- **Original references:** `M-05`
- **Confidence:** High

**Evidence.** `removeVaultKey()` (`:61`), `removeAccount(createdAt)` (`:85`) and `clearAll()` (`:90`) exist but have **zero callers in `src/main`** (only the definitions; `grep` finds no invocation). `BravePasswordService.delete(syncCode, existing)` (`:45`) likewise has no caller. Nostr events are never issued a deletion event.

**Technical explanation.** The storage layer can delete; no layer above it ever asks it to. Consequences: (a) a user cannot remove an account or the vault; (b) a stale or foreign record restored into local storage cannot be evicted; (c) an unrecoverable local state (H-03) has no user-accessible exit; (d) a revoked/shared device retains vault data indefinitely; (e) remote copies in Nostr relays and Brave Sync cannot be withdrawn, so "remove my backup" is not an available promise.

**Production impact.** A vault app with no deletion story is a compliance and user-trust problem (data-subject erasure, "sold my phone" hygiene) and it is the missing prerequisite for fixing H-03. Privacy policies that claim user control over backups would be inaccurate.

**Trigger conditions.** Any user intent to remove data; any corrupted-state recovery.

**Root cause.** Deletion was deferred in the product surface while the storage primitives were built.

**Recommended fix.** Ship account deletion and a guarded "reset local vault" (typed confirmation, clears `SecureStorageService`, resets setup flags, optionally attempts remote deletion), plus Nostr NIP-09 deletion for relay copies and Brave `delete` for its entries. Document what cannot be removed.

**Verification strategy.** Delete an account and assert local records, pending writes and statuses are removed; reset the vault and assert the router returns to onboarding; assert remote deletion attempts are recorded in status.

---

### M-07 — Corrupt persisted JSON is silently read as an empty list

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Data persistence / Error handling
- **File:** `app/src/main/java/app/zenten/vault/data/kv/AppStorageService.kt`
- **Line:** `:237-239`
- **Reported by:** DeepSeek-v4.1-flash (R-03) — 1 of 3 reports
- **Original references:** `R-03`
- **Confidence:** High

**Evidence.**

```kotlin
// AppStorageService.kt:237-239
private inline fun <reified T> decodeList(value: String?): List<T> = value?.let {
    runCatching { json.decodeFromString<List<T>>(it) }.getOrDefault(emptyList())
}
```

This helper backs `pendingLocalBackupAccountIds`, `pendingSourceWrites`, `accountSourceStatuses`, `locallyBackedUpAccountIds` and the create/secret-combination step markers.

**Technical explanation.** Any deserialization failure — schema evolution, partial write, corruption — is indistinguishable from "no data". The failure is silent: no log, no status, no user signal. Adjacent to L-01 (non-atomic setup writes), this is how an interrupted write can manifest as permanently lost state without anyone noticing, and it can also make the router treat a user with pending exports or an in-flight wizard as fresh.

**Production impact.** Data-loss class failure with no diagnostic trail. Because the same preference store holds wizard step markers, a decode failure can strand a user mid-wizard or route them past a mandatory step.

**Trigger conditions.** App upgrade that changes a serialized schema; storage corruption; an interrupted write (see L-01).

**Root cause.** `getOrDefault` used to make the read total, at the cost of conflating absence with corruption.

**Recommended fix.** Distinguish null (absent → empty) from non-null-but-undecodable (corrupt → surface an error, preserve the raw string for diagnosis, route to a recovery screen). Add a migration/version key for these JSON blobs.

**Verification strategy.** Write a malformed string into each preference key and assert the app reports a recoverable error rather than silently behaving as empty.

---

### M-08 — Brave Sync commit treats a reply with no entry results as success

- **Severity:** Medium
- **Verification:** PARTIALLY CONFIRMED
- **Category:** Networking / Data integrity
- **File:** `app/src/main/java/app/zenten/vault/bravesync/BravePasswordService.kt`; `bravesync/proto/SyncMessages.kt`
- **Line:** `BravePasswordService.kt:96-99`, `:35`; `SyncMessages.kt:90-97`
- **Reported by:** DeepSeek-v4.1-flash (R-07) — 1 of 3 reports
- **Original references:** `R-07`
- **Confidence:** Medium-High

**Evidence.**

```kotlin
// BravePasswordService.kt:96-100
private fun commit(session: Session, entity: ByteArray) {
    val response = request(session, SyncMessages.commitRequest(listOf(entity)))
    response.commit?.entryResults?.forEach {
        require(it.responseType == 1) { "Brave Sync commit failed (${it.responseType}): ${it.errorMessage.orEmpty()}" }
    }
}
```
```kotlin
// SyncMessages.kt:90-97 — parseCommit hardcodes a successful status
errorCode = 0,
errorMessage = null,
```

`createKnownMissing` (`:35-36`) commits and does not re-fetch to confirm the entry exists. `commitNewPassword` (`:76-87`) does require the returned entity (`:31`), so the highest-value path is guarded; the generic `commit` used for the entryless case is not.

**Technical explanation.** Three fail-open behaviours compose: (1) a `null` `commit` block leaves the loop unexecuted, so no entry result is validated and the call returns normally; (2) `parseCommit` fabricates `errorCode = 0` regardless of the wire payload, discarding any server-reported failure at that level; (3) an empty `entryResults` list iterates zero times and is therefore "success". The finding is *partially* confirmed because the consolidation engine performs a separate read-back verification before declaring a backup present — so the fail-open is caught one layer up, which is why this is Medium and not High. The `createKnownMissing` path, however, is documented to be used specifically for entries known to be missing, and it has no read-back.

**Production impact.** A Brave backup silently absent while the engine's own repair pass may or may not catch it depending on which path wrote. Where the read-back runs, cost is an extra round trip; where it does not, a user can believe a backup exists when it does not.

**Trigger conditions.** Server returns a commit response without entry results (version differences, partial failure) or an empty/omitted commit block.

**Root cause.** Parsing was written defensively against server-version variation and lost the distinction between "no detail reported" and "success".

**Recommended fix.** Require a non-null commit block and at least one entry result where one entity was submitted; surface `errorCode`/`errorMessage` from the wire instead of hardcoding success; re-fetch after `createKnownMissing` to confirm presence.

**Verification strategy.** Stub transport responses with (a) missing commit block, (b) empty entry results, (c) non-zero wire error code, and assert each fails the backup.

---

### M-09 — Unbounded response/relay payloads and a new HTTP client per operation

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Networking / Performance
- **File:** `app/src/main/java/app/zenten/vault/bravesync/transport/BraveSyncTransport.kt`; `network/nostr/NostrRelayService.kt`
- **Line:** `BraveSyncTransport.kt:18`, `:37-42`; `NostrRelayService.kt:221-244`, `:348-355`
- **Reported by:** Opus-5.5 (L-02, Low), DeepSeek-v4.1-flash (R-08, Medium) — 2 of 3 reports
- **Original references:** `L-02`; `R-08`
- **Confidence:** High

**Evidence.**

```kotlin
// BraveSyncTransport.kt:37-42
val bytes = response.body.bytes()                                       // whole body, unbounded
...
GZIPInputStream(bytes.inputStream()).use { it.readBytes() }             // full decompression, unbounded
```
```kotlin
// NostrRelayService.kt:223-245 — a fresh client/scope per operation
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
val client = clientFactory(scope)      // → newQuartzClient builds a new OkHttpClient (:348)
...
finally { client.close(); scope.cancel() }
```

Neither path caps the size of what it accepts: no `Content-Length` check, no streamed limit, no decompression-ratio guard, no cap on the number of events returned by `fetchAll`. Both also rebuild their HTTP client rather than sharing one: Brave constructs a `BraveSyncTransport` (default `OkHttpClient()`) per source instance at `BackupSourceAdapters.kt:55-58` and `VaultKeyRecoveryService.kt:151-153`, and Nostr constructs a new `OkHttpClient` (with its own connection pool and dispatcher) for every publish/fetch at `:348-355`.

**Technical explanation.** Gzip is a decompression-bomb primitive: a small compressed body can expand to gigabytes, and because the decompression happens after the whole encoded body is already in memory, both peaks stack. For Nostr, a hostile or malfunctioning relay can return arbitrarily many events matching a filter, and `MAX_AUTHORS_PER_FILTER` (`:345`) bounds authors per filter but not events per subscription. The client churn discards HTTP/2 connection reuse, so every backup does a full TCP+TLS+WebSocket handshake to each relay — slower, more battery, and more conspicuous on the wire.

**Production impact.** OOM/ANR risk from a malicious or broken endpoint; degraded battery and latency; higher network cost proportional to run count.

**Trigger conditions.** A compromised or buggy Brave/Nostr endpoint returning an oversized or highly-compressed response; normal operation for the client churn.

**Root cause.** Streaming APIs used in buffered mode without limits; client lifecycle tied to a single operation rather than to the process.

**Recommended fix.** Read with a bounded buffer (e.g. cap at a few hundred KB for the sync protobuf, reject on overflow), enforce a decompressed-size ceiling and a maximum compression ratio, cap events processed per fetch, and share one `OkHttpClient` per role (relay, brave, time-check) instead of constructing one per call.

**Verification strategy.** Stub a gzip bomb and an oversized relay response; assert both fail fast without exceeding a memory budget. Assert connection reuse across two sequential operations with an `EventListener`.

---

### M-10 — Passphrase verification is unthrottled outside the creation wizard

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Security and privacy / Authentication
- **File:** `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailScreen.kt`; `ui/vaultkey/VaultKeyDetailScreen.kt`; `ui/secretcombinations/SecretCombinationsViewModel.kt`; `ui/newaccount/NewAccountViewModel.kt`
- **Line:** `AccountDetailScreen.kt:148-182`; `VaultKeyDetailScreen.kt:152-170`; `SecretCombinationsViewModel.kt:67-79`; `NewAccountViewModel.kt:23-27`
- **Reported by:** DeepSeek-v4.1-flash (R-04) — 1 of 3 reports
- **Original references:** `R-04`
- **Confidence:** High

**Evidence.** The only attempt-limiting code in the app is in the create wizard: `CreateVaultKeyViewModel.kt:94-105` and `:447-455` (`MAX_VAULT_KEY_PASTE_ATTEMPTS`, `blockedUntil = clock.millis() + attempts * VAULT_KEY_PASTE_LOCKOUT_STEP_MILLIS`). The passphrase dialogs and verification screens have no counter, no delay, and no lockout: `ExportPassphraseDialog` (`:91-99`) simply re-invokes `onConfirmed` on each tap.

**Technical explanation.** The exported-key container is designed to make offline brute force expensive (bcrypt), and the same design makes online guessing against a live app expensive *for the attacker only if the app limits attempts*. Without a limit, a holder of a stolen unlocked device — or malware driving the UI via accessibility/automation — can attempt passphrases at the device's bcrypt rate indefinitely. The number of attempts is also bounded in practice by the 5s ANR window (H-01) only because the UI is frozen, which is not a security control.

**Production impact.** Reduces the cost of a post-unlock guessing attack against the account and Vault Key passphrases, and against Secret Combinations review. Not a remote attack; requires an unlocked device or privileged automation.

**Trigger conditions.** Attack with physical or accessibility-level access to an unlocked device.

**Root cause.** Throttling was implemented in the one flow that had a UX moment to host it (the paste step) and not generalized.

**Recommended fix.** Centralize a per-record attempt counter with exponential delay in `AccountService` (persisted, not just in memory), apply it to export verification, Secret Combinations review, and new-account creation; keep the message generic.

**Verification strategy.** Unit-test that the Nth failure introduces a delay and that the counter persists across process restart; UI-test that repeated wrong entries eventually disable the action.

---

### M-11 — Backup engine has no dispatcher of its own and is called from non-main contexts

- **Severity:** Medium
- **Verification:** CONFIRMED
- **Category:** Coroutines / Concurrency
- **File:** `app/src/main/java/app/zenten/vault/domain/AccountConsolidationEngine.kt`; `domain/BackupSourceAdapters.kt`; `domain/SetupService.kt`
- **Line:** `AccountConsolidationEngine.kt:67-73`, `:101`, `:218`, `:313`; `BackupSourceAdapters.kt:34-41`; `SetupService.kt:21-51`
- **Reported by:** Derived from Opus-5.5 (H-01 adjacent context) and DeepSeek-v4.1-flash (R-01) — reported indirectly, confirmed by this audit
- **Original references:** `H-01`; `R-01` (same root cause family, different call path)
- **Confidence:** High

**Evidence.**

```kotlin
// AccountConsolidationEngine.kt — no withContext/Dispatchers anywhere in the file
suspend fun backupVaultKey(...) = operationMutex.withLock { runVaultKeyBackup(...) }   // :67-73
...
val runContext = sourceFactory.createRunContext(secrets)                             // :101, :218, :313
```
```kotlin
// BackupSourceAdapters.kt:34-35 — a *non-suspend* factory that derives the seed
override fun createRunContext(secrets: VaultKeyRunSecrets): BackupSourceRunContext {
    val seed = SshKeyCodec.parse(secrets.vaultKey.zenTenKey, secrets.passphrase).seed   // bcrypt again
```

`grep` for `withContext|Dispatchers` in `AccountConsolidationEngine.kt` returns nothing.

**Technical explanation.** The engine is suspend but inherits its caller's dispatcher. Callers are mixed: the WorkManager path runs on the worker's `Dispatchers.Default`, which is fine, but `SetupService.performSetup` is invoked from `OnboardingViewModel.performSetup` via `viewModelScope.launch` (`OnboardingViewModel.kt:72-76`), i.e. Main. So the first-run backup — including a full bcrypt derivation in `createRunContext` and all network I/O — can execute on the main thread, freezing the setup screen for the duration of remote work. This is the same class of defect as H-01 but on the longest-running path in the app, and it was not separately reported by any input report.

**Production impact.** Potential multi-second-to-multi-minute freeze during first-run setup (network timeouts are 8 s × 3 attempts × 4 sources, plus KDF). Users may kill the app mid-setup, which with L-01 is the exact process-death window that loses quorum evidence.

**Trigger conditions.** First-run setup or a retry of a stored Vault Key backup, with any network slowness.

**Root cause.** The engine assumes its caller manages the dispatcher, and one caller does not.

**Recommended fix.** Wrap the engine's public entry points in `withContext(Dispatchers.IO)` (I/O) and move the KDF in `createRunContext` to `Dispatchers.Default`; alternatively make `createRunContext` suspend and document the contract. Audit every `suspend` domain service for the same implicit-context assumption.

**Verification strategy.** StrictMode + a slow-network fake during onboarding; assert no main-thread work beyond one frame, and add a test asserting the engine enters `Dispatchers.IO`.

---

## 7. Low Findings

### L-01 — Setup completion and quorum evidence are written in two separate edits

- **Severity:** Low (reported as Medium)
- **Verification:** CONFIRMED
- **Category:** Data persistence / Invariant
- **File:** `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- **Line:** `:47-50`, `:94-95`
- **Reported by:** GPT-5.6-terra (MEDIUM-01) — 1 of 3 reports
- **Original references:** `MEDIUM-01`
- **Confidence:** High

**Evidence.**

```kotlin
// SetupService.kt:47-50
appStorage.setSetupComplete()
appStorage.setVaultKeyQuorumReached(true)
```
Each is its own `dataStore.edit {}` (`AppStorageService.kt:93`, `:118`), so a process death between them leaves `setupComplete = true` with quorum evidence absent. The same pattern repeats at `:94-95` in `retryVaultKeyBackup`.

**Why Low, not Medium.** The invariant break is real but has no consumer: `vaultKeyQuorumReached` is referenced only at `AppStorageService.kt:79` (declaration), `SetupService.kt:120` (`completeGeneratedVaultKeySetup` checks it) and `:83`/`:133` (writes). After `setupComplete` is true, no code path reads it. `RootViewModel` routes on `setupComplete` alone (`RootScreen.kt:52`), so the user-visible consequence is nil. The report's stated impact ("subsequent account creation checks setup completion rather than quorum evidence") is accurate as a description of the code and *that is precisely why nothing breaks* — there is no branch that can observe the torn state. It remains a defect because the code's comment asserts an invariant the code does not maintain, and because any future consumer of the flag would inherit a latent inconsistency.

**Technical explanation.** `DataStore.edit` is atomic per call, so two calls are two transactions. The comment above `setSetupComplete()` in `SetupService` and the field's KDoc both describe a state that carries quorum evidence with it; the implementation cannot deliver that across a process boundary. `requireImportedVaultKeyBackup()` (cleared by the first edit) and the quorum flag are therefore not guaranteed to be consistent with each other after an interruption.

**Production impact.** None today, because `vaultKeyQuorumReached` is read only by `completeGeneratedVaultKeySetup` (`SetupService.kt:120`), which runs before setup completes. The consequence is a false invariant in code and documentation, plus a latent inconsistency for any future reader of the flag.

**Trigger conditions.** Process death, crash or storage failure between the two `dataStore.edit` calls.

**Root cause.** Two independent DataStore edits where one transaction would express the invariant.

**Recommended fix.** A single `completeSetupWithVaultKeyQuorum()` edit writing both values (and clearing pending state) atomically; route `performSetup` and `retryVaultKeyBackup` through it.

**Verification strategy.** Instrumented test that kills the process between writes and asserts the router cannot reach accounts without quorum evidence.

---

### L-02 — Passphrase is copied into unzeroable `String`s inside the KDF path

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Security and privacy / Memory hygiene
- **File:** `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- **Line:** `:200`
- **Reported by:** DeepSeek-v4.1-flash (R-10), Opus-5.5 (adjacent to H-01) — 2 of 3 reports
- **Original references:** `R-10`
- **Confidence:** High

**Evidence.**

```kotlin
BCrypt().pbkdf(String(passphrase).toByteArray(StandardCharsets.UTF_8), salt, rounds, derived)
```

Only `derived` is zeroed, in the `finally` at `:207`. The two copies made by `String(passphrase).toByteArray(...)` are never zeroed: one immutable heap `String` that lives until GC, and one `ByteArray` whose only live reference is the argument expression, so it cannot be wiped at all. Call sites also carry `String` passphrases by design — `AccountService` takes `String` (`:32`, `:46`) and the export dialogs hold `String` state.

**Technical explanation.** `CharArray` and `ByteArray` can be wiped; `String` cannot. Every conversion from `CharArray` to `String` in the KDF path creates a plaintext passphrase copy whose lifetime is outside the app's control. This is a real hygiene defect but is bounded by the fact that the Android runtime, IME, and Compose text fields all hold `String`/`CharSequence` copies anyway — so it raises the number of copies, not the existence of them.

**Production impact.** Longer window in which the passphrase sits unzeroed in heap; relevant to memory-dump forensics on a rooted/compromised device, not to remote attack.

**Trigger conditions.** Every passphrase verification, key generation, export and recovery that reaches `SshKeyCodec.parse` — i.e. normal use of the app.

**Root cause.** Apache MINA's `BCrypt.pbkdf` accepts `ByteArray`, but the code converts from `CharArray` via `String` rather than encoding the `CharArray` directly.

**Recommended fix.** Encode `CharArray` → `ByteArray` directly (e.g. `CharsetEncoder` over a `CharBuffer`), zero the encoded array in `finally`, and widen the internal API to accept `CharArray` end-to-end. Document that `String` passphrases at the UI boundary remain unavoidable.

**Verification strategy.** Code review plus a test asserting the encoded buffer is zeroed; no runtime assertion is practical for a heap copy, so this is verified by construction.

---

### L-03 — Remote response text can reach user-facing error messages

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Security and privacy / Error handling
- **File:** `app/src/main/java/app/zenten/vault/bravesync/transport/BraveSyncTransport.kt`; `domain/SetupService.kt`; `ui/onboarding/OnboardingViewModel.kt`
- **Line:** `BraveSyncTransport.kt:39-40`; `SetupService.kt:35-41`; `OnboardingViewModel.kt:124-131`
- **Reported by:** DeepSeek-v4.1-flash (R-11) — 1 of 3 reports
- **Original references:** `R-11`
- **Confidence:** Medium-High

**Evidence.**

```kotlin
// BraveSyncTransport.kt:39-40
throw IOException("Brave Sync HTTP ${response.code}: ${bytes.decodeToString().take(500)}")
```
```kotlin
// SetupService.kt:36-41 — the wrapped message is concatenated into a user-facing exception
"The VaultKey is saved locally, but its backup is incomplete. " + (failure.message ?: "Retry the backup.")
```
```kotlin
// OnboardingViewModel.kt:124-131
.onFailure { error -> ... error.message ?: "Setup failed. Please try again." }
```

Note on the input report's claim: it also named `formatBackupError` (`AccountDetailScreen.kt:402-403`) as an instance. That is **incorrect** — `formatBackupError` lowercases an enum name, and never interpolates remote text. The finding survives on the two paths above.

**Technical explanation.** Up to 500 bytes of an arbitrary HTTP response body — from a server the app does not control — are placed into an exception message that is then shown to the user. At minimum this leaks server internals; it also permits a hostile or MITM'd endpoint to inject text into the UI (misleading instructions, fake support phone numbers).

**Production impact.** Information disclosure and a social-engineering surface in an error path the user is likely to trust during a failed backup.

**Trigger conditions.** A backup attempt that receives a non-2xx Brave Sync response with a body, or any transport failure on that path.

**Root cause.** Diagnostic detail was put in the exception message rather than in a log or a structured error field, and the message is surfaced directly.

**Recommended fix.** Keep remote text out of user-facing strings: map failures to a closed set of error categories (`BackupErrorCategory` already exists) and log the detail separately (without secrets). Truncate or hash if any diagnostic is needed internally.

**Verification strategy.** Stub a response whose body contains HTML/instructions and assert the UI shows only the category message.

---

### L-04 — Three `createdAt` sites use millisecond precision with no jitter

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Data model / Correctness
- **File:** `ui/onboarding/OnboardingViewModel.kt`; `ui/onboarding/createvaultkey/CreateVaultKeyViewModel.kt`; `domain/VaultKeyRecoveryService.kt`
- **Line:** `OnboardingViewModel.kt:110`; `CreateVaultKeyViewModel.kt:205`; `VaultKeyRecoveryService.kt:92`
- **Reported by:** DeepSeek-v4.1-flash (R-12) — 1 of 3 reports
- **Original references:** `R-12`
- **Confidence:** High

**Evidence.** All three use `createdAt = clock.millis() * 1_000L`, while `AccountService.nextCreatedAt()` (`:81-88`) deliberately adds `ThreadLocalRandom.nextLong(1, 1000)` microseconds and CASes against `lastCreatedAt` to guarantee uniqueness. The three bypassing sites rely on millisecond resolution, so two creations in the same millisecond — or a retried creation — can collide on what is used as the primary key.

**Technical explanation.** `createdAt` is the account's identity (`removeAccount(createdAt)`, provider `getAccountById(id)`). Millisecond granularity is usually sufficient for user-driven creation but not guaranteed; the app's own convention documents microsecond resolution as the standard, so these three sites are inconsistent with it rather than merely coarse.

**Production impact.** A collision would overwrite one account with another in the preference store (`addAccount` keys on `createdAt`). Low likelihood, but the failure mode is silent data loss.

**Trigger conditions.** Two records created within the same millisecond by the same path, most plausibly a rapid retry after a failure.

**Root cause.** Three code paths construct `Account` directly rather than going through the shared ID generator.

**Recommended fix.** Extract the ID generator into a shared component and use it in all four sites.

**Verification strategy.** Unit test that N rapid creations through each path yield N distinct IDs.

---

### L-05 — SSH key formatting and KDF work re-run on every recomposition

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Jetpack Compose / Performance
- **File:** `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailScreen.kt`
- **Line:** `:105-108`, `:375-388`
- **Reported by:** DeepSeek-v4.1-flash (R-13) — 1 of 3 reports
- **Original references:** `R-13`
- **Confidence:** High

**Evidence.** `formatSshPublicKey(...)`, `formatSshFingerprint(...)` and `formatKdfRounds(...)` are invoked directly in composition (`:105-108`), and the helpers (`:375-388`) re-run `SshKeyCodec` parsing/formatting. None is wrapped in `remember`.

**Technical explanation.** Compose recomposes on any state change in the enclosing scope, including status flow emissions. Unremembered formatting work — which includes key parsing — repeats for each recomposition. The KDF-round formatting re-parses the container header.

**Production impact.** Additional jank on the detail screen, compounding M-04.

**Trigger conditions.** Any recomposition of the account detail screen — including every backup-status emission while the screen is open.

**Root cause.** Formatters called inline in composition rather than hoisted into `remember(account)` (or computed in the ViewModel).

**Recommended fix.** `remember(account.id, account.zenTenKey) { ... }` for each formatted value, or compute them once in `AccountDetailState`.

**Verification strategy.** Compose recomposition-count test asserting the formatter runs once per account.

---

### L-06 — `SimpleDateFormat` allocated per list row

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Performance
- **File:** `app/src/main/java/app/zenten/vault/ui/accounts/AccountsListScreen.kt`; `ui/accounts/AccountCard.kt`
- **Line:** `AccountsListScreen.kt:203`; `AccountCard.kt:120`, `:123`
- **Reported by:** DeepSeek-v4.1-flash (R-14) — 1 of 3 reports
- **Original references:** `R-14`
- **Confidence:** High

**Evidence.**

```kotlin
// AccountsListScreen.kt:203
SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(accountId / 1_000L))
// AccountCard.kt:120, :123 — two more instances per row
```

**Technical explanation.** `SimpleDateFormat` is expensive to construct (locale data, pattern compilation) and is allocated per item per recomposition. `java.time` formatters (`DateTimeFormatter`) are immutable and thread-safe but should still be cached; the cheaper fix here is `remember`ing a formatter or deriving the label once.

**Production impact.** Measurable list-scroll jank on large vaults, especially with the up-to-99 account ceiling.

**Trigger conditions.** Scrolling the account list, or recomposing any account card.

**Root cause.** Formatting written inline for brevity.

**Recommended fix.** Hoist `DateTimeFormatter` constants, or precompute the month label in the list model.

**Verification strategy.** Macrobenchmark scroll on a 99-account list before/after.

---

### L-07 — Launch router `combine` can throw on a malformed pending ID

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Kotlin correctness / Stability
- **File:** `app/src/main/java/app/zenten/vault/ui/root/RootScreen.kt`
- **Line:** `:37-66` (specifically `:51`)
- **Reported by:** DeepSeek-v4.1-flash (R-15) — 1 of 3 reports
- **Original references:** `R-15`
- **Confidence:** High

**Evidence.**

```kotlin
// RootScreen.kt:51
complete && pending.isNotEmpty() -> VaultDestinations.localAccountBackupExport(pending.first().toLong())
```

`pendingLocalBackupAccountIds` is a `Set<String>` decoded from JSON (`AppStorageService.kt:59-60`, `:237-239`). A non-numeric value throws `NumberFormatException` inside the `combine` transform, which cancels the router's flow.

**Technical explanation.** The router is the app's launch gate. An exception in its transform propagates through `stateIn`, terminating destination computation; the destination flow never emits, and the UI has no fallback branch for a terminal failure. Combined with M-07 (corrupt JSON reads as empty), the two failure modes bracket each other: corrupt JSON silently hides pending exports, while a *valid* list with a bad element kills the router.

**Production impact.** Blank or stuck launch screen; recoverable only by clearing app data. Low likelihood, high user cost.

**Trigger conditions.** A persisted `pendingLocalBackupAccountIds` entry that is not a decimal number, which a future migration or a partial write can produce.

**Root cause.** A parse of persisted data without a total function.

**Recommended fix.** `pending.first().toLongOrNull()` with a fallback that clears the offending entry and routes to accounts; add an explicit error branch in the router.

**Verification strategy.** Unit-test the router with a non-numeric pending ID and assert a defined destination.

---

### L-08 — Exported provider returns an item MIME type for a directory query

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Android ContentProvider correctness
- **File:** `app/src/main/java/app/zenten/vault/keyhive/AssociatedAccountsProvider.kt`
- **Line:** `:59`
- **Reported by:** DeepSeek-v4.1-flash (R-16) — 1 of 3 reports
- **Original references:** `R-16`
- **Confidence:** High

**Evidence.**

```kotlin
ACCOUNTS -> "vnd.android.cursor.item/vnd.zenten.associated-accounts"   // :59
ACCOUNT  -> "vnd.android.cursor.item/vnd.zenten.associated-account"    // :60
```

Per the Android `ContentProvider.getType` contract, a query returning multiple rows must report a `vnd.android.cursor.dir/` type; `.item/` means "this URI identifies exactly one row".

**Technical explanation.** Consumers use the type to decide how to present results. An item type on a multi-row query is a contract violation that can make generic adapters render incorrectly or fail type assertions.

**Production impact.** Misbehaving reads in the companion app; no security impact.

**Trigger conditions.** Any consumer calling `getType` on the accounts directory URI.

**Root cause.** Copy of the item string for the directory case.

**Recommended fix.** `vnd.android.cursor.dir/vnd.zenten.associated-accounts`.

**Verification strategy.** Assert `contentResolver.getType(ACCOUNTS_URI)` starts with `vnd.android.cursor.dir/`.

---

### L-09 — Dead and test-only production code paths

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Maintainability
- **File:** `app/src/main/java/app/zenten/vault/domain/RecoveryService.kt`; `bravesync/BraveSyncClient.kt`
- **Line:** entire files
- **Reported by:** Opus-5.5 (L-06), DeepSeek-v4.1-flash (R-17) — 2 of 3 reports
- **Original references:** `L-06`; `R-17`
- **Confidence:** High

**Evidence.** `RecoveryService` (88 lines) is referenced only by `app/src/test/.../AssociatedAccountLabelTest.kt:145`. `BraveSyncClient` (37 lines) has no reference outside its own declaration (`grep -rn "BraveSyncClient(" app/src` finds only the class). The live recovery path is `VaultKeyRecoveryService`, wired at `di/DomainModule.kt:59-67` and used by `RecoverVaultKeyViewModel.kt:23,110`.

**Technical explanation.** Two parallel implementations of "recovery" and "Brave sync" exist; one of each is live. A maintainer reading the tree cannot tell which is authoritative without tracing DI, and dead code that looks live is a review hazard in a security-sensitive module.

**Production impact.** No runtime impact; adds review surface and risk of fixing the wrong file.

**Trigger conditions.** Any maintainer reading or patching the recovery or Brave Sync flow, or any future wiring change that revives the dead file.

**Root cause.** Two implementations were written for each concern and the newer one replaced the older only at the DI/wiring layer; the superseded files were not removed.

**Recommended fix.** Delete both, or mark `@VisibleForTesting` and rename to make the test-only status explicit; adjust the test to target the live service.

**Verification strategy.** Compilation after removal; the label test should be re-pointed rather than deleted.

---

### L-10 — Supply-chain pinning is inconsistent

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Build and release security
- **File:** `.github/workflows/ci.yml`; `.github/workflows/security.yml`; `gradle/wrapper/gradle-wrapper.properties`
- **Line:** `ci.yml:29`, `:36`, `:42`, `:53`, `:67`; `security.yml:38`, `:62`, `:90`; `gradle-wrapper.properties:3`
- **Reported by:** DeepSeek-v4.1-flash (R-18) — 1 of 3 reports
- **Original references:** `R-18`
- **Confidence:** High

**Evidence.** `security.yml` pins actions to full commit SHAs (`actions/checkout@11d5960a... # v4`, `upload-artifact@ea165f8d... # v4`), while `ci.yml` uses mutable major tags (`actions/checkout@v4`, `setup-java@v4`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v4`). The Gradle wrapper declares no `distributionSha256Sum` (`gradle-wrapper.properties:3`), although `validateDistributionUrl=true` is set.

**Technical explanation.** A mutable tag can be re-pointed at different code; a full SHA cannot. The build job is the higher-value target (it produces the artifact that would be signed), so the inconsistency is backwards: the stricter policy is applied to the lower-risk workflow. Without a wrapper checksum, a compromised distribution host or TLS interception could serve a different Gradle binary.

**Production impact.** Build-integrity exposure on the pipeline that would produce release artifacts; no impact on the shipped binary as reviewed today.

**Trigger conditions.** Any CI run that resolves a mutated action tag or a substituted Gradle distribution.

**Root cause.** The security workflow was hardened while the build workflow kept the original, more convenient tag references; no lint enforces pinning.

**Recommended fix.** Pin every action in `ci.yml` to a SHA (mirroring `security.yml`) and add `distributionSha256Sum` to the wrapper properties.

**Verification strategy.** Add a CI lint (e.g. `zgosalvez/github-actions-ensure-sha-pinned-actions`) and a wrapper-checksum verification step.

---

### L-11 — UI strings are split between resources and hardcoded literals

- **Severity:** Low
- **Verification:** PARTIALLY CONFIRMED
- **Category:** Localization / Accessibility
- **File:** throughout `app/src/main/java/app/zenten/vault/ui/**`
- **Line:** n/a (32 literal `Text("…")` sites vs 160 `<string>` entries)
- **Reported by:** DeepSeek-v4.1-flash (R-19) — 1 of 3 reports
- **Original references:** `R-19`
- **Confidence:** High

**Evidence.** `grep -c '<string ' app/src/main/res/values/strings.xml` → 160. `grep -rn 'Text("' app/src/main/java --include=*.kt | wc -l` → 32. Notably, `ExportPassphraseDialog.kt:72` hardcodes `label = { Text("VaultKey Passphrase") }` even though the dialog is shared by the account-detail export flow, where that label is wrong for an account key.

**Why partially confirmed.** The report characterized this as ~"32 hardcoded strings against 160 resources"; the counts are right, and the resulting defect is real — but many of the 32 are debug/pseudo-labels inside `buildAnnotatedString` or inline content alternates. The substantive instances are user-visible screen text and the mislabeled shared dialog. The finding is Low because the app is not shipping localized, and the mislabel is a UX correctness issue rather than a functional break.

**Technical explanation.** Literals in Compose bypass the resource pipeline: they cannot be translated, are not lint-checked for missing placeholders, and are invisible to translation tooling.

**Production impact.** Blocks localization; the shared dialog shows "VaultKey Passphrase" when exporting an associated account, which can confuse the user about which passphrase to enter.

**Trigger conditions.** Adding a new locale, or exporting an associated account through the shared passphrase dialog.

**Root cause.** Screens were written incrementally, with resources introduced for the earlier ones and literals left in later composables; the shared dialog was generalized without generalizing its label.

**Recommended fix.** Move user-visible literals to `strings.xml`; parameterize the dialog label per caller (`vaultKeyLabel` vs `accountLabel`).

**Verification strategy.** Enable `HardcodedText` lint as an error; add a UI test asserting the account export dialog names the account passphrase.

---

### L-12 — `CHECKING`/`BACKING_UP` statuses can persist indefinitely after a crash

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Data persistence / UX state
- **File:** `app/src/main/java/app/zenten/vault/domain/AccountConsolidationEngine.kt`
- **Line:** `:547-550`, `:679-681`
- **Reported by:** Opus-5.5 (L-03) — 1 of 3 reports
- **Original references:** `L-03`
- **Confidence:** High

**Evidence.** `markChecking` (`:547`) and `markBackingUp` (`:550`) write transient states to durable storage. If the process dies during the run, nothing resets them; the next launch displays the persisted transient state until another run completes.

**Technical explanation.** Transient progress states are stored in the same durable row as conclusive results, with no "run generation" or staleness marker. `lastAttemptAtMillis` exists but has no reader that degrades stale transient states.

**Production impact.** A user can see "Checking" or "Backing up" for days after a crash, which undermines trust in the backup status display — the feature's main trust signal.

**Trigger conditions.** A process kill during a consolidation run, followed by relaunch.

**Root cause.** Progress states were persisted through the same durable status model used for conclusive outcomes, without a run-identity or staleness marker.

**Recommended fix.** Either treat transient states as non-durable (derive from an in-flight run flag) or reset any transient state older than the freshness window at startup.

**Verification strategy.** Persist a `CHECKING` row, restart, and assert the UI shows a conclusive state or an explicit "interrupted" status.

---

### L-13 — Account detail flashes "Account not found" before its first emission

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Jetpack Compose / UX
- **File:** `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailViewModel.kt`; `AccountDetailScreen.kt`
- **Line:** `AccountDetailViewModel.kt:65-68`; `AccountDetailScreen.kt:87`
- **Reported by:** Opus-5.5 (L-05) — 1 of 3 reports
- **Original references:** `L-05`
- **Confidence:** High

**Evidence.** `AccountDetailState(account: Account? = null)` is the `stateIn` initial value, and the screen renders "Account not found" whenever `account == null` (`:87`), without distinguishing "not loaded yet" from "genuinely absent".

**Technical explanation.** `stateIn` emits the initial value synchronously before `load(id)` and the first `combine` result; for one frame the screen shows a terminal error for a valid account.

**Production impact.** Momentary but alarming in a vault app — "Account not found" reads as data loss.

**Trigger conditions.** Opening account detail for any valid account.

**Root cause.** The `stateIn` initial value and the "account absent" state were both modelled as `null`, so the screen cannot distinguish loading from missing.

**Recommended fix.** Add a `Loading`/`NotFound`/`Loaded` distinction (sealed state or a nullable `isLoading` flag) and render nothing/spinner while loading.

**Verification strategy.** Compose test asserting the not-found text never appears for a valid ID.

---

### L-14 — Retry actions discard their result and have no in-flight state

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** UX / Error handling
- **File:** `app/src/main/java/app/zenten/vault/ui/vaultkey/VaultKeyDetailScreen.kt`; `ui/accounts/ExportPassphraseDialog.kt`
- **Line:** `VaultKeyDetailScreen.kt:70`; `ExportPassphraseDialog.kt:91-99`
- **Reported by:** Opus-5.5 (L-04) — 1 of 3 reports
- **Original references:** `L-04`
- **Confidence:** High

**Evidence.**

```kotlin
// VaultKeyDetailScreen.kt:70
fun retryVaultKey() { viewModelScope.launch { setupService.retryVaultKeyBackup() } }   // Result dropped
```
`ExportPassphraseDialog`'s Confirm button has no busy state and calls `onConfirmed` synchronously on the UI thread (`:91-99`, `enabled = passphrase.isNotBlank()` only).

**Technical explanation.** A `Result` failure produces no feedback; the button is immediately re-tappable, and with H-01 still present the tap appears to do nothing while the KDF runs, inviting repeated taps and duplicate work.

**Production impact.** Users cannot tell whether a retry or verification is running or failed; repeated submissions amplify the freeze.

**Trigger conditions.** A failed backup retry, or a passphrase verification slow enough (see H-01) that the user taps again.

**Root cause.** The retry and confirm actions were wired directly to fire-and-forget calls, before any busy/error state existed to surface outcomes.

**Recommended fix.** Surface `Result` failures as inline messages, add `isInFlight` state, and disable the action while pending (this lands naturally with the H-01 fix).

**Verification strategy.** UI test asserting a failed retry shows an error and that Confirm is disabled during verification.

---

### L-15 — `failedRelays` mixes normalized and raw URLs in an unread field

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Kotlin correctness
- **File:** `app/src/main/java/app/zenten/vault/network/nostr/NostrRelayService.kt`; `network/nostr/NostrEvent.kt`
- **Line:** `NostrRelayService.kt:104`; `NostrEvent.kt:17`
- **Reported by:** DeepSeek-v4.1-flash (R-20) — 1 of 3 reports
- **Original references:** `R-20`
- **Confidence:** High

**Evidence.**

```kotlin
// NostrRelayService.kt:104
return PublishResult(event.id, successful, relayUrls.toSet() - successful, attempts)
```

`successful` is populated from Quartz `NormalizedRelayUrl.url` values while `relayUrls` is the raw configured list; subtracting one spelling from another is only correct when normalization is identity. `failedRelays` (`NostrEvent.kt:17`) has no consumers (`grep` finds only the declaration), so the defect is latent.

**Technical explanation.** Two representations of the same logical URL are compared as strings. Any configured URL whose normalized form differs (trailing slash, default port, case in scheme/host) appears in `failedRelays` even after a successful publish.

**Production impact.** None today; it would misreport failures the moment anything consumes the field — for example a per-relay diagnostic UI or a retry policy.

**Trigger conditions.** Any future consumer of `PublishResult.failedRelays`, especially with a configured URL whose normalized form differs.

**Root cause.** `relayUrls` was the convenient source for the failed set, while success tracking necessarily used Quartz's normalized values.

**Recommended fix.** Compare normalized forms on both sides (or populate `failedRelays` from the monitor's per-relay outcome), and delete the field until something reads it.

**Verification strategy.** Unit test with a URL needing normalization, asserting `failedRelays` is empty after success.

---

### L-16 — Secure-record writes are asynchronous while the routing marker that depends on them is durable

- **Severity:** Low
- **Verification:** CONFIRMED
- **Category:** Data consistency
- **File:** `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`; `data/kv/AppStorageService.kt`; `ui/newaccount/NewAccountViewModel.kt`
- **Line:** `SecureStorageService.kt:95-96`; `AppStorageService.kt:219-223`; `NewAccountViewModel.kt:33-35`
- **Reported by:** Opus-5.5 (L-01) — 1 of 3 reports
- **Original references:** Opus `L-01`
- **Confidence:** Medium-High

**Evidence.**

```kotlin
// SecureStorageService.kt:95-96 — androidx.core.content.edit defaults to apply()
private fun write(key: String, account: Account) =
    preferences.edit { putString(key, crypto.encrypt(json.encodeToString(account))) }
```
```kotlin
// NewAccountViewModel.kt:33-35 — the marker is written after the record "exists"
.onSuccess {
    appStorage.requireAccountLocalBackup(it.createdAt)     // suspend; DataStore flushes before returning
```
```kotlin
// AppStorageService.kt:219-223
suspend fun requireAccountLocalBackup(accountId: Long) = dataStore.edit { preferences ->
    val ids = decodeList<String>(preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS]).toMutableSet()
    ids += accountId.toString()
    preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS] = json.encodeToString(ids.toList())
}
```

**Technical explanation.** `apply()` updates the in-memory map synchronously and performs the disk write asynchronously; `dataStore.edit` suspends until its own write completes. The ordering guarantee therefore runs the wrong way: the router marker can reach disk while the record it points at is still only in memory. If the process dies in that window, `pendingLocalBackupAccountIds` names an account that `SecureStorageService` cannot find, and the router's mandatory-export branch (`RootScreen.kt:51`) sends the user to a screen with `BackHandler { }`, `onBack = null`, and "Account not found" (`LocalAccountBackupScreen.kt:71-87`) — no route forward, no route back, and nothing clears the marker.

**Production impact.** Rare, but it lands on the most important record and produces the same class of dead end as H-03. It is the more likely of the two, because it needs only a process kill during account creation rather than a device migration.

**Trigger conditions.** Process death, crash, or low-memory kill between the account write and the DataStore flush — most likely immediately after account creation or Vault Key generation.

**Root cause.** Two persistence backends with different durability semantics are ordered by call sequence rather than by completion; the marker write is `suspend` and the record write is not.

**Recommended fix.** Make `SecureStorageService.write` use `edit(commit = true)` on `Dispatchers.IO`, or convert the service to suspend with a completing write, and write the marker only after the record is durable. In addition, treat a marker whose account is missing as stale and clear it at startup — the same recovery path H-03 needs.

**Verification strategy.** Instrumented fault injection: kill the process between `addAccount` and `requireAccountLocalBackup`, relaunch, and assert the user reaches a usable state rather than a blocked screen.

---

## 8. Contradictory Findings

Contradictions are recorded with the resolution applied, the evidence that decided it, and the residual uncertainty.

### C-1 — Config-change relock: High vs Medium

- **Positions:** Opus-5.5 rated H-02 **High**; DeepSeek-v4.1-flash rated R-02 **Medium**; GPT-5.6-terra did not report it at all.
- **Resolution: High (H-02).** The code path is not in doubt — both reports describe the same mechanism and the source confirms it (`MainActivity.kt:80`, `AppUnlockCoordinator.kt:114-127`). The disagreement is about frequency and impact. Medium would be right if rotation were an edge case; it is not. Rotation, dark-mode switching, font-size change and locale change are all ordinary actions, and the device posture ("tablet, user rotates") makes this an every-session event. The user-visible effect is an unsolicited re-authentication prompt on a security app, which is both an availability defect and a trust defect.
- **Residual uncertainty:** None on mechanism. Actual frequency by device class was not measured.

### C-2 — Worker success-on-failure: Medium vs Low

- **Positions:** Opus-5.5 rated **Medium**; DeepSeek-v4.1-flash rated R-09 **Low**.
- **Resolution: Medium (M-01).** The Low reading assumes the engine's durable status rows are the user's source of truth and that the worker result is only an internal signal. That is incomplete: the worker result controls WorkManager's backoff and retry, and the engine's pending-write queue only drains on the next unlock. A device that is rarely unlocked can therefore accumulate failed backups with no automatic retry *and* no OS-level signal. For a backup product, "work reports success while all backups failed" is not a Low.
- **Residual uncertainty:** Whether any user-facing surface would have warned the user on the next open. The status rows do exist, so a diligent user could notice. That reduces likelihood of harm, not the defect.

### C-3 — Unbounded payloads: Low vs Medium

- **Positions:** Opus-5.5 rated L-02 **Low**; DeepSeek-v4.1-flash rated R-08 **Medium**.
- **Resolution: Medium (M-09).** The Low reading depends on the remote endpoint being trusted. It is not: both Brave Sync servers and both Nostr relays are external services the app does not operate, and Nostr relays in particular are permissionless and can be operated by anyone. An unbounded `bytes()` + unbounded gunzip against an untrusted peer is a classic decompression-bomb / memory-exhaustion primitive.
- **Residual uncertainty:** Whether OkHttp's default response-size handling imposes any practical ceiling. It does not impose a decompressed-size limit, so the impact estimate stands.

### C-4 — Clipboard: is non-clearing a defect?

- **Positions:** Opus-5.5 M-02 asserts the clipboard is never cleared; the project's own docs state non-clearing is deliberate.
- **Resolution: M-02 retained, but only the `EXTRA_IS_SENSITIVE` half.** `docs/features/sensitive-key-access.md:29` states the export deliberately leaves the value on the clipboard because it is a user-requested disclosure. Reporting that as a defect would contradict documented product intent. The missing sensitivity flag is a distinct defect that does not conflict with that intent.
- **Residual uncertainty:** None.

### C-5 — Non-atomic setup writes: Medium vs Low

- **Positions:** GPT-5.6-terra rated MEDIUM-01 **Medium**; this audit rates it **Low** (L-01).
- **Resolution: Low, with the Medium rationale recorded.** GPT's stated impact is that "subsequent account creation checks setup completion rather than quorum evidence". That is literally true and is why the torn state is currently unobservable: `vaultKeyQuorumReached` has no consumer after setup (`AppStorageService.kt:79`, `SetupService.kt:120` only). The invariant claim in the code comment is false, which is a real defect; the user-visible impact is zero today. Downgrading is not dismissal — L-01 specifies a fix and a process-death test. If a future change makes `vaultKeyQuorumReached` authoritative, this must be re-rated Medium.
- **Residual uncertainty:** Any consumer added later changes the rating.

### C-6 — D2D/Keystore: real High, or documentation-only risk?

- **Positions:** Opus-5.5 reports H-03 High with Medium-High confidence and states it was not reproduced; the other two reports do not mention it.
- **Resolution: H-03 retained as High, PARTIALLY CONFIRMED.** A single-report finding is not downgraded for being singular (explicit rule), and the mechanism is confirmed against both source and Android 12+ documented behaviour. What is *not* claimed is that it was observed: no device or emulator reproduction exists in this audit. The finding is written so that a reader can see exactly which parts are static-confirmed (absent extraction rules; null-on-missing-key decrypt; setup-state/unlock-gate disagreement; no reset path) and which rest on platform documentation (the D2D transfer behaviour itself).
- **Residual uncertainty:** OEM variation in whether `allowBackup="false"` suppresses D2D entirely. Even under the most favourable OEM behaviour, the Keystore-invalidation path remains.

### C-7 — Offline usability: defect or product decision?

- **Positions:** Opus-5.5 M-03 and DeepSeek R-06 both flag the offline gate; neither cites documentation establishing it as intentional.
- **Resolution: M-03 retained as Medium, as a scope question made explicit.** The clock check protects a real property (Brave Sync's date-derived 25th word). But the implementation applies a remote precondition to local reads, and there is no documented product decision stating that the vault must be online to be readable. The resolution is therefore: the *requirement* is legitimate, the *placement* is a defect. If product intent is in fact "always online", that must be documented and M-03 closed as intended behaviour — no such document exists (`docs/features/` contains no offline-access statement).
- **Residual uncertainty:** Product intent. Flagged in the roadmap as requiring a decision (P2).

### C-8 — Severity inflation across reports

- **Observation:** The three reports produced 4, 15 and 20 findings respectively, with total severity weight far above what source verification supports. Several DeepSeek Low findings describe symptoms rather than defects (for example the `failedRelays` field, which has no consumer at all), and GPT's report contains only four findings because it reviewed a narrower slice.
- **Resolution:** Nothing was carried forward as "Critical" or "High" without a traced mechanism. Findings whose mechanism could not be traced were resolved in Section 9 rather than retained at reduced severity. Counts in this report reflect verified defects, not reported ones: 32 surviving findings from 41 reported claims.

---

## 9. Duplicate and False-Positive Findings

### 9.1 Duplicate consolidation

| Consolidated finding | Reports and IDs | Duplicate class | Reason |
|---|---|---|---|
| H-01 | GPT HIGH-02; Opus H-01; DeepSeek R-01 | Substantive | Same root cause (non-suspend verifiers on Main), same file. Call-site lists differ; the union was verified independently. |
| H-02 | Opus H-02; DeepSeek R-02 | Exact | Same mechanism. |
| H-04 | GPT HIGH-01; Opus H-04 (part); DeepSeek release item | Exact | Same absent `signingConfigs`. |
| M-01 | Opus M-01; DeepSeek R-09 | Exact | Same unconditional success. |
| M-03 | Opus M-03; DeepSeek R-06 | Exact | Same gate; DeepSeek's variant emphasized the offline case, which is the substantive one. |
| M-04 / M-05 | Opus M-04; DeepSeek R-05 | **Related but independent** | Opus bundled UI-thread decrypt and write amplification. They were split because they need separate fixes (cache vs batch write) and have separate failure modes (jank vs long runs). |
| M-09 | Opus L-02; DeepSeek R-08 | Exact | Same absent caps; severities differed. |
| L-09 | Opus L-06; DeepSeek R-17 | Exact | Same two dead files. |
| L-16 | Opus L-01 | Unique (no duplicate) | Records the `apply()`-vs-DataStore ordering hazard; kept separate from L-01 (setup atomicity) because the fix is a different write path. |
| H-05 | GPT HIGH-03; Opus H-04 (part); DeepSeek CI items | Substantive | Same CI gap; GPT framed it as minification untested, Opus as release pipeline absent. |
| L-02 | DeepSeek R-10; Opus H-01 (adjacent) | Related but independent | Opus's evidence mentions the `String` conversion as part of the threading finding; it is a separate memory-hygiene defect and is not fixed by moving threads. |

Non-duplicated cross-references: DeepSeek R-01 and R-05 both reference `AccountService`, but R-05 is about Keystore/decrypt on Main (M-04) and is independent of the bcrypt path.

### 9.2 Claims rejected or narrowed

| Claim | Status | Reason |
|---|---|---|
| Opus M-02: "clipboard is never cleared" as a defect | **FALSE POSITIVE (as stated)** | Documented deliberate behaviour at `docs/features/sensitive-key-access.md:29`. The surviving part is the missing sensitivity flag (M-02). |
| GPT MEDIUM-01: torn setup state breaks subsequent account creation | **NARROWED to Low** | The flag has no consumer after setup; see C-5. Mechanism real, impact overstated. |
| DeepSeek R-11: `formatBackupError` interpolates remote text | **FALSE POSITIVE for that site** | `AccountDetailScreen.kt:402-403` lowercases an enum name. L-03 retains the two real paths. |
| DeepSeek R-03 framing: corruption causes "empty account list" | **NARROWED** | The decode helper backs preference lists (pending writes, statuses, step markers), not the encrypted account store. Corrupt JSON cannot empty the account list; it can silently drop pending/status state (M-07). |
| GPT "no additional low-severity confirmed defects" | **Not a claim; noted** | Recorded to show the report's coverage was narrower, not that the code was cleaner. |
| DeepSeek R-07 stated as a confirmed HIGH-impact data-loss path | **NARROWED to Medium** | The engine's read-back verification catches it one layer up for the consolidation path; `createKnownMissing` remains unguarded (M-08). |
| Opus L-02 "new OkHttpClient per source" | **CONFIRMED, re-scoped** | Two different lifetimes, both wasteful: Brave builds one `BraveSyncTransport` (and `OkHttpClient`) per source instance per run (`BackupSourceAdapters.kt:55-58`, `VaultKeyRecoveryService.kt:151-153`), and Nostr builds one per operation (`NostrRelayService.kt:223-245`). See M-09. |
| DeepSeek R-04 "no throttling" as a brute-force vulnerability | **CONFIRMED, scoped** | Real, but requires an unlocked device or privileged automation; not a remote attack. Rated Medium (M-10). |
| Any claim of a Critical vulnerability | **FALSE POSITIVE** | No Critical candidate survived; see Section 4. |
| GPT environment claim "no Java runtime installed" | **Not inherited** | Refers to that report's environment, not this repository's; see §2.2. |

### 9.3 Bulk-unverified claims

No surviving finding rests on a claim this audit could not trace to source. Items the input reports asserted without a file anchor (for example generic "consider dependency scanning" recommendations) were not carried forward as findings; they appear as roadmap hygiene items in Section 15 only where they connect to a named finding.

---

## 10. Unique Findings

Findings reported by exactly one input report but confirmed independently here. Singularity was not treated as evidence for or against.

| ID | Report | Why it is unique | Why it survived |
|---|---|---|---|
| H-03 | Opus only | Requires reasoning across manifest, Keystore, and unlock gate | Mechanism traced end-to-end; D2D behaviour documented; limited to PARTIALLY CONFIRMED because unreproduced |
| M-02 | Opus only | Requires knowing `EXTRA_IS_SENSITIVE` | Flag verified absent; sensitive data verified present |
| M-06 | Opus only | Absence-of-code finding | Verified: three storage deletes and one Brave delete with zero `src/main` callers |
| M-07 | DeepSeek only | Requires reading the decode helper's callers | Verified at `AppStorageService.kt:237-239` and call sites |
| M-08 | DeepSeek only | Requires reading the Brave protobuf parser | Verified: unexecuted `forEach` + hardcoded `errorCode = 0` |
| M-10 | DeepSeek only | Absence-of-code finding | Verified: throttling exists only in the create wizard |
| M-11 | **Derived by this audit** | No report connected `SetupService`'s caller to the engine's missing dispatcher | Verified: no `withContext`/`Dispatchers` in the engine; onboarding calls it from `viewModelScope` |
| L-01 | GPT only | Cross-file invariant | Verified; downgraded per C-5 |
| L-16 | Opus only | Requires cross-backend durability reasoning (`apply()` vs `DataStore.edit`) | Verified at `SecureStorageService.kt:95-96`, `AppStorageService.kt:219-223`, `NewAccountViewModel.kt:33-35`; same dead-end class as H-03 |
| L-03 | DeepSeek only | Error-path tracing | Verified on two of three claimed sites |
| L-04 | DeepSeek only | Requires comparing against `nextCreatedAt` | Verified at three sites |
| L-05 | DeepSeek only | Compose recomposition reasoning | Verified at `AccountDetailScreen.kt:105-108` |
| L-06 | DeepSeek only | Perf micro-pattern | Verified at three allocations |
| L-07 | DeepSeek only | Persistence-to-router tracing | Verified at `RootScreen.kt:51` |
| L-08 | DeepSeek only | Provider contract knowledge | Verified at `:59-60` |
| L-12 | Opus only | Durable-state reasoning | Verified at `:547-550` |
| L-13 | Opus only | Compose state-initialization reasoning | Verified at `AccountDetailViewModel.kt:65-68` |
| L-14 | Opus only | UX-state reasoning | Verified at `VaultKeyDetailScreen.kt:70`, dialog `:91-99` |
| L-15 | DeepSeek only | URL normalization knowledge | Verified; latent because the field is unread |

M-11 is called out because it is the one finding in this report that no input report made. It is included under the "missing risks" objective: the evidence is a confirmed absence of dispatcher handling in a file that performs both KDF and network I/O and is reachable from a Main-context caller.

---

## 11. Security and Privacy Assessment

### 11.1 Controls that hold

| Control | Evidence | Assessment |
|---|---|---|
| Encryption at rest | `KeystoreCryptoService.kt:46-73` — AES-256-GCM, non-exportable Keystore key, version-tagged payload, IV bounds, `GCMParameterSpec(TAG_BITS, iv)` | Strong. Authentication failure surfaces as decryption failure, never as plaintext. |
| Key container validation | `SshKeyCodec.kt:104-210` — structural parse, bcrypt band check (`:164`, `:277`), padding checks, seed↔pubkey re-derivation | Strong. The unauthenticated AES-CTR private section is compensated by the re-derivation integrity check. |
| Passphrase lifetime | `VaultKeySession.kt` — `CharArray` guarded by `Mutex`, zeroing on lock, `unlock` on `Dispatchers.Default` | Acceptable, with the `String`-copy caveat (L-02). |
| Screen capture | `MainActivity.kt:66` — `FLAG_SECURE` on the window | Strong. |
| Exported provider | `AndroidManifest.xml:15-16, 39-44` — `signature` permission, `exported=true`, `grantUriPermissions=false`, read-only | Strong, contingent on the signing identity being real (H-04). |
| Secret logging | No `Log.`/`println` in `src/main` | Strong. |
| Remote trust boundaries | Nostr over `wss`, Brave over `https`; no cleartext config | Acceptable; payload limits absent (M-09). |
| Backup verification | Write-then-read-back before quorum; source-status model; `operationMutex` serialization | Strong design; weakened by M-08 fail-open at one layer. |
| Documented honesty | `docs/technical/build-and-test.md:43-46`, `docs/features/sensitive-key-access.md:29` | Commendable; the docs state known gaps rather than overstating readiness. |

### 11.2 Security defects

- **H-01 / M-11** — KDF on the UI thread. Not a confidentiality break, but it makes an intentional security cost (bcrypt) indistinguishable from an app hang, which pressures users and may lead to weaker work factors being chosen.
- **M-02** — Clipboard exports lack `EXTRA_IS_SENSITIVE`, widening exposure of the encrypted key block.
- **M-10** — No attempt throttling on passphrase verification, reducing the cost of post-unlock guessing.
- **M-06** — No deletion of local or remote copies. This is a privacy/erasure gap, not a disclosure.
- **L-02** — Unzeroable `String` copies of the passphrase in the KDF path.
- **L-03** — Remote response text can enter user-facing errors (information disclosure + social-engineering surface).

### 11.3 Privacy assessment

Data leaving the device: encrypted vault records (Nostr events; Brave password entries), each individually passphrase-encrypted before transmission. The app does not transmit plaintext keys, and does not appear to collect analytics or telemetry (no analytics dependency in `app/build.gradle.kts`). Two pieces of metadata are noteworthy: (a) Nostr events expose the author pubkey and kind to relays — inherent to the protocol, and the app's own documentation treats it as such; (b) the startup time check sends `HEAD` requests to `google.com` and `cloudflare.com` (`AppAccessGuard.kt:70-104`), which discloses app activity to two third parties on every cold start. That is a disclosure worth documenting, though it is not a finding by the criteria in §2.3 — it is discussed in Section 12 and noted in the roadmap.

**No secret was exposed in producing this report.** The Brave Sync service key is a documented public protocol constant and its value is not reproduced here.

---

## 12. Stability and Performance Assessment

### 12.1 Principal stability risks, ranked

1. **H-01** — main-thread bcrypt across six paths. The only defect in this report with a credible ANR path under normal use.
2. **M-11** — the consolidation engine inheriting a Main dispatcher when called from onboarding, which turns the first-run backup into a main-thread network + KDF operation.
3. **H-02** — forced re-authentication on every config change. Not a crash, but it interrupts and frustrates at a predictable rate.
4. **M-09** — unbounded body/gzip handling against untrusted endpoints (memory exhaustion).
5. **M-04 / M-05** — UI-thread Keystore work and quadratic restore cost; scaling problems that worsen with vault size.
6. **M-07 / L-07** — silent state loss and a router that can terminate on malformed persisted data.

### 12.2 Things that are genuinely well done

- WorkManager is configured with `ExistingWorkPolicy.APPEND_OR_REPLACE` and exponential backoff (`BackupWorkers.kt:157-202`), and cancelled work rethrows `CancellationException` rather than swallowing it (`:74-76`) — structured concurrency is respected.
- The passphrase is never placed in WorkManager input data; a restarted worker fails closed (`BackupWorkers.kt:49`, `:108`).
- `RelayOperationMonitor` handles the AUTH/OK race by keeping one monitor and connection across retries (`NostrRelayService.kt:126-145`) — a subtle correctness detail that is easy to get wrong.
- `VaultKeySession` uses a `Mutex` and zeroes on lock; `AccountService.nextCreatedAt` uses a CAS loop.
- `AppAccessGuard` uses a generation counter (`AtomicLong`) so stale async results cannot overwrite newer state (`:146`, `:192`).
- The consolidation engine's `operationMutex` prevents overlapping runs.

### 12.3 Performance observations not raised as findings

- A new `OkHttpClient` per relay operation (folded into M-09) also means a fresh TLS/WebSocket handshake per publish/fetch.
- `value.forEach { append(it.toString()); append('\u200B') }` in `CopyableIdentityValue` allocates a `String` per character for long keys; correct, but a candidate for a later optimization.
- The startup time check's sequential `HEAD` requests (`:70-104`) add up to ~20 s worst case to cold start when both sources time out, which is bounded but noticeable. This is the cost side of M-03; the security rationale is legitimate.

---

## 13. Testing Gaps

### 13.1 Current corpus (counted directly)

| Metric | Value |
|---|---|
| Unit test files under `app/src/test` | 41 (40 contain `@Test`) |
| `@Test` methods under `app/src/test` | 259 |
| Instrumented test files under `app/src/androidTest` | 2 (`crypto/`, `data/`) |
| `@Test` methods under `app/src/androidTest` | 7 |
| Compose UI test dependencies declared | Yes (`app/build.gradle.kts:136-139`) |
| Compose UI test rules actually used | **0** — no `createAndroidComposeRule`/`createComposeRule` anywhere |
| Pre-existing unit-test results on disk | 40 XML files, 259 tests, 0 failures, 0 errors (dated 2026-11-10; **not executed by this audit**) |

The unit layer is respectable in volume, concentrated on crypto, codecs and domain logic. The gaps are structural.

### 13.2 Gaps tied to findings

| Gap | Findings it would have caught | Priority |
|---|---|---|
| No UI/instrumentation test for any screen; Compose test deps declared but unused | H-02, L-13, L-14, L-11 | P0 for H-02 |
| No launch-path test (onboarding → setup → router) | H-03, L-07, M-07 | P0 |
| No threading/StrictMode assertions | H-01, M-04, M-11 | P0 |
| No release-variant smoke test (CI or manual) | H-05, H-04 | P0 |
| No process-death / state-tearing test | L-01, M-07, L-12 | P1 |
| No remote-client failure-mode tests (malformed/empty/large responses) | M-08, M-09, L-03 | P1 |
| No passphrase-throttling test | M-10 | P2 |
| No large-vault performance test (99 accounts × 4 sources) | M-05, M-04, L-06 | P2 |
| No clipboard metadata test | M-02 | P2 |
| No provider contract test (`getType`, permission enforcement) | L-08, plus the provider's security posture | P2 |

---

## 14. Healthy Components

Recorded so the remediation effort does not disturb what is already correct. Each item was inspected, not inferred.

| Component | Evidence | Why it is healthy |
|---|---|---|
| Keystore encryption service | `crypto/KeystoreCryptoService.kt:46-73` | AES-256-GCM, version-tagged payloads, IV bounds, synchronized key creation, `null` (not plaintext) on missing key. |
| SSH container codec | `crypto/SshKeyCodec.kt:104-277` | Bounded bcrypt band, padding and structure validation, seed↔pubkey re-derivation as an integrity check, explicit rejection messages. |
| Vault Key session | `domain/VaultKeySession.kt:40-60` | `Mutex`-guarded `CharArray`, zeroing on lock, KDF on `Dispatchers.Default` — the pattern H-01 should adopt. |
| WorkManager integration | `background/BackupWorkers.kt:30-80`, `:150-202` | Passphrase never persisted to worker input; fail-closed on missing session; `CancellationException` rethrown; exponential backoff; `APPEND_OR_REPLACE` uniqueness. |
| Consolidation engine design | `domain/AccountConsolidationEngine.kt:59-73`, `:660-681` | `operationMutex` serialization, quorum is a precondition for setup completion, per-source status model with explicit write-attempt state sets. |
| Relay operation monitor | `network/nostr/NostrRelayService.kt:126-145`, `:250-330` | Handles the AUTH/OK ordering race correctly by reusing one monitor and connection across retries. |
| Access-guard concurrency | `ui/availability/AppAccessGuard.kt:139-199` | Generation counter prevents stale async results from clobbering newer state. |
| Account ID generation | `domain/AccountService.kt:81-88` | CAS loop with microsecond jitter — the correct pattern that L-04's three sites bypass. |
| Exported provider protection | `AndroidManifest.xml:15-16`, `:39-44`; `keyhive/AssociatedAccountsProvider.kt` | Signature permission, no URI grants, read-only surface. |
| R8 configuration | `app/proguard-rules.pro:2-28` | Explicit keeps for BouncyCastle, secp256k1, and kotlinx.serialization — deliberate, if broad. |
| Release documentation honesty | `docs/technical/build-and-test.md:25-46` | States the unsigned and unsmoke-tested status plainly rather than claiming readiness. |
| Secret hygiene | whole `src/main` | No logging API calls; no plaintext key rendered; export is passphrase-gated and user-initiated. |
| Domain test coverage | 259 `@Test` methods across 40 files | Substantial for a project at this stage, concentrated where correctness matters most. |

---

## 15. Prioritized Remediation Roadmap

### P0 — Release blockers (must land before any release candidate)

| Order | Finding | Action | Depends on |
|---|---|---|---|
| 1 | **H-01** | Make verifiers `suspend` + `Dispatchers.Default`; add busy state to export dialogs; move the onboarding parse and the two synchronous dialog callbacks off Main | — |
| 2 | **M-11** | Add explicit dispatchers to the consolidation engine's entry points; make `createRunContext` suspend | — (same PR as H-01 is reasonable) |
| 3 | **H-02** | Move unlock coordinator state to a ViewModel/retained holder; keep the grace-period semantics | — |
| 4 | **H-04** | Add `signingConfigs.release` from CI secrets; document custody/rotation; `apksigner` verification | — |
| 5 | **H-05** | CI: `assembleRelease`, `lintRelease`, emulator smoke of the minified APK | H-04 (needs a signed artifact) |
| 6 | **H-03** (+ **M-06**) | `dataExtractionRules`; startup detection of "setup complete, key undecryptable"; recovery screen with reset action | M-06 must land with H-03, or H-03 has no exit |
| 7 | **M-06** | Account deletion + guarded local reset (+ remote deletion attempts) | Enables H-03 |
| 8 | **M-01** | Worker maps engine outcome to `Result.retry()`; wire `attemptCount` to backoff | — |

### P1 — Required for a production-quality first release

| Order | Finding | Action | Depends on |
|---|---|---|---|
| 9 | **M-07** | Distinguish absent from corrupt in `decodeList`; add schema-version keys | — |
| 10 | **L-07** | `toLongOrNull` + explicit router error branch | M-07 |
| 11 | **M-08** | Require non-null commit + ≥1 entry result; surface wire error codes; read back after `createKnownMissing` | — |
| 12 | **M-09** | Bounded body/decompressed sizes; shared `OkHttpClient`s | — |
| 13 | **M-04** | Cache decrypted records; read the cache from list/detail/provider | — |
| 14 | **M-05** | Batch `addAccounts`; in-memory status cache; one DataStore edit per phase | M-04 |
| 15 | **M-02** | `EXTRA_IS_SENSITIVE` on key/PEM clipboard writes | — |
| 16 | **L-01** | Atomic `completeSetupWithVaultKeyQuorum()` | — |
| 17 | **L-16** | Completing secure write (`commit = true`) before the marker; clear stale markers at startup | Shares the startup recovery path with H-03 |
| 18 | **L-03** | Category-only user messages; detail to internal logging | — |
| 19 | **L-12** | Reset or de-durable transient statuses | M-07 (if adding a run-generation key) |

### P2 — Hardening and product decisions

| Order | Finding | Action | Depends on |
|---|---|---|---|
| 20 | **M-03** | Split the gate: local read allowed offline, remote mutation gated; persist last-known-good time | Product decision on offline support |
| 21 | **M-10** | Centralized persisted attempt counter + exponential delay | — |
| 22 | **L-02** | Encode `CharArray` → `ByteArray` directly; widen internal API | — |
| 23 | **L-04** | Shared ID generator for all four `createdAt` sites | — |
| 24 | **L-05**, **L-06** | `remember` formatters; hoist `DateTimeFormatter` | — |
| 25 | **L-08** | `cursor.dir` MIME type | — |
| 26 | **L-13**, **L-14** | Loading state; surface retry results; busy states | H-01 (shared busy-state work) |
| 27 | **L-15** | Normalize the comparison, or delete the unread field | — |
| 28 | **L-09** | Delete or mark test-only `RecoveryService`, `BraveSyncClient` | Point the label test at the live service first |
| 29 | **L-10** | SHA-pin all `ci.yml` actions; add wrapper `distributionSha256Sum` | — |
| 30 | **L-11** | Extract user-visible literals; parameterize dialog label | — |

### P3 — Follow-up

- Instrumented/UI test suite (Section 13), starting with the launch path and rotation (H-02).
- End-to-end backup/restore/recovery interoperability against controlled Nostr and Brave endpoints.
- Dependency/CVE scanning and a recorded release security review.
- Evaluate removing the third-party time source (or making it configurable) — see §11.3.

### Dependency notes

- **H-03 cannot be closed without M-06**: detecting an unrecoverable state without offering a reset leaves the user exactly where they are now.
- **M-04 should precede M-05**: caching the decrypted store removes most of the cost that batching then optimizes further.
- **H-05 depends on H-04** for a signed artifact, but `assembleRelease` and `lintRelease` can be added to CI immediately.
- **H-01 and M-11 should ship together**: they are the same defect class and touch overlapping call paths, and both need the same busy-state UI work.

---

## 16. Final Release Recommendation

**NOT READY FOR RELEASE**

This is the only recommendation supported by the evidence. It is not a judgment that the project is unsound — Sections 11 and 14 document substantial, correct security engineering — but the artifact that would be shipped today is unsigned, unvalidated in its release configuration, and contains a confirmed main-thread ANR path on six user-reachable screens.

**Blocking findings (all must be closed or accepted in writing by release management):**

| Finding | Blocking because |
|---|---|
| **H-01** | Confirmed ANR-class defect on passphrase verification and export; reachable through normal use |
| **H-02** | Every configuration change forces re-authentication; normal use on tablets/foldables |
| **H-03** | Migration/lock-screen-change path strands existing records behind an unrecoverable first-run setup, with no reset (needs M-06) |
| **H-04** | The release artifact cannot be distributed; the signing identity is also the provider's trust anchor |
| **H-05** | No evidence the minified artifact runs; CI green-lights untested release code |

**Supporting findings that should close in the same release:** M-01, M-06 (also unblocks H-03), M-11.

**Low findings with an outsized risk relative to their label:** L-16 (silent dead end on the mandatory export screen) and L-07 (router terminates on malformed state) both read as Low by likelihood and High by consequence. They are rated Low because each needs an interrupted write or a corrupt value, but they are the first P1 items for that reason.

**What would move the verdict to READY AFTER REQUIRED FIXES:** all P0 items closed and verified by the strategies in Sections 5–7, with a recorded device/emulator pass of the signed minified APK on at least one API 26-class device and one API 31+ device, plus a D2D transfer observation (H-03) resolving the PARTIALLY CONFIRMED status.

**What would move it to CONDITIONALLY READY:** the same P0 closure for an internal/dogfood channel that does not require public distribution, with H-04/H-05 replaced by a documented, controlled sideload process — and only with **M-06** present, since a dogfood build without a reset path is how testers lose real data.

**What would never be an acceptable resolution:** shipping with H-01 or H-02 open. Both are cheap to fix, both are already solved correctly elsewhere in the same codebase (`VaultKeySession.unlock`; ViewModel-scoped state), and both are user-visible on the very screens that build trust in a vault app.

---

## Appendix A — Reports and references

| Report | Path | Findings | Verdict stated |
|---|---|---|---|
| GPT-5.6-terra | `audits/round2/production-readiness-review-2026-11-10-gpt-5-6-terra.md` | 4 | NOT READY FOR RELEASE |
| Opus-5.5 | `audits/round2/production-readiness-review-2026-11-10-opus-5-5.md` | 15 | READY AFTER REQUIRED FIXES |
| DeepSeek-v4.1-flash | `audits/round2/production-readiness-review-2026-11-10-deepseek-v4.1-flash.md` | 20 | NOT READY FOR RELEASE |

The single dissent (Opus-5.5's READY AFTER REQUIRED FIXES) is consistent with this report's verdict in substance: Opus's own P0 list is nearly identical, and its recommendation label reflects a different threshold for when a remediation path exists, not a disagreement that the current artifact is shippable.

## Appendix B — 11-dimension assessment

| Dimension | Assessment | Basis |
|---|---|---|
| Kotlin correctness | **ACCEPTABLE** | Clean coroutine usage in workers, sessions and the access guard; defects are localized (L-07 parse, L-04 ID precision, L-15 normalization). No systemic type-safety or nullability problems observed. |
| Architecture | **STRONG** | Clear UI → ViewModel → domain → storage/source layering; DI via Hilt throughout; the consolidation engine is a coherent boundary. |
| Coroutines and concurrency | **NEEDS IMPROVEMENT** | Correct primitives (`Mutex`, `SupervisorJob`, generation counters, structured cancellation) coexist with H-01 and M-11 — the engine has no dispatcher and six user paths block Main. |
| Jetpack Compose | **ACCEPTABLE** | Idiomatic composition and state handling, but no Compose tests at all (deps declared, zero usages), inline formatting on recomposition (L-05), and a loading/not-found conflation (L-13). |
| Android lifecycle | **NEEDS IMPROVEMENT** | H-02 (config change destroys security state) and H-03 (D2D/Keystore split-brain) are both lifecycle/state-lifetime defects. `FLAG_SECURE` and `DisposableEffect` usage are otherwise correct. |
| Security and privacy | **ACCEPTABLE** | Encryption, container validation, provider protection and secret hygiene are strong (Section 11.1); clipboard metadata, throttling, deletion and error-message hygiene are the gaps. No Critical. |
| Networking | **NEEDS IMPROVEMENT** | Robust relay/AUTH handling and quorum logic; unbounded payload handling, per-operation client creation, and one fail-open commit path (M-09, M-08). |
| Database and persistence | **ACCEPTABLE** | DataStore + Keystore-wrapped prefs is a sound model; corruption handling (M-07), non-atomic writes (L-01) and write amplification (M-05) need work. |
| Performance and stability | **NEEDS IMPROVEMENT** | H-01, M-04, M-05, M-11 all concentrate here. The app will feel slow and can ANR on exactly the flows that matter most. |
| Gradle and release configuration | **HIGH RISK** | Unsigned release, no release CI, no device validation, inconsistent action pinning, no wrapper checksum. The project documents these gaps itself. |
| Testing and reliability | **NEEDS IMPROVEMENT** | 259 unit tests is a real foundation; zero UI/instrumentation coverage of the flows where the High findings live, and no release-variant testing. |

---

## Appendix C — Verification limitations repeated for reviewers

- Every finding in this report is a **static-analysis conclusion**. None was reproduced on a device, and none was executed as a test by this audit.
- **H-03 is the only finding whose real-world manifestation depends on platform behaviour this audit could not observe.** It is labelled PARTIALLY CONFIRMED for that reason; the missing extraction rules, the null-on-missing-key decryption, the mismatch between durable setup state and what the unlock gate can see, and the absent reset path are all confirmed statically, and remain defects independent of D2D behaviour.
- The 259/40 test-result artifacts on disk predate this audit and were produced by an earlier toolchain. They are not evidence about the current revision.
- No dependency advisory scan, secret scan, or interoperability test was performed.
- No project file was modified and no fix was implemented. No secret value appears in this report.

---

## Appendix D — Errata and corrections (2026-11-12)

Corrections to this report established by source verification after the consolidation date.
The original findings text is left unmodified so the audit remains a faithful record of
what the consolidating reviewer concluded at revision `01f3d2b`; read the corrections here
as authoritative where they conflict with Section 5 or Appendix C. E-05 is a note on how to
close the finding rather than a correction of it.

### E-01 — H-03 is narrower than stated: the Keystore key is not auth-bound

**Original claim (H-03, "Trigger conditions"):** "Android 12+ device-to-device transfer, or
any event that invalidates the Keystore key (factory reset restore of app data, secure
lock-screen credential change on some OEMs), followed by app launch."

**Correction.** `KeystoreCryptoService.generateKey()` builds the key with only
`PURPOSE_ENCRYPT`/`PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, and
`setKeySize(256)`. Neither `setUserAuthenticationRequired(true)` nor
`setInvalidatedByBiometricEnrollment(true)` is declared anywhere in the file, so this key
is **not** bound to device authentication and is **not** invalidated by enrolling a new
biometric or changing the lock-screen credential. The lock-screen-change trigger does not
exist for this app.

The remaining trigger is device-to-device transfer, where the Keystore alias is absent on
the destination device. This narrows the finding's reachability but does not weaken it: an
alias can still be absent after a D2D transfer on an OEM that honours the transfer, and the
confirmed code-path defects (a)–(d) in H-03 are unaffected.

**Severity:** unchanged (High). **Verification status:** unchanged (PARTIALLY CONFIRMED),
now for a single trigger rather than two.

### E-02 — H-03's recommended detection condition is not implementable as written

**Original claim (H-03, "Recommended fix" item 2):** "Detect
`setupComplete == true && vault key undecryptable` at startup."

**Correction.** `SecureStorageService.read(key)` is a single chain that returns `null` for
both "no such preference" and "present but did not decrypt":

```kotlin
private fun read(key: String): Account? = preferences.getString(key, null)
    ?.let(crypto::decrypt)
    ?.let { encoded -> runCatching { json.decodeFromString<Account>(encoded) }.getOrNull() }
```

`KeystoreCryptoService.decrypt()` also swallows *all* failures through `runCatching { }
.getOrNull()`, so a missing key alias, a malformed payload, and a GCM tag mismatch are
also one indistinguishable `null`. The class's own KDoc acknowledges the gap: "distinguishing
'corrupt' from 'absent' for the user is tracked separately and is not attempted here."

A check written as stated would therefore fire on **every fresh install**, because a fresh
install also has `setupComplete == false` only until onboarding completes, and cannot at any
point distinguish the two states. The condition must consult key presence directly:

```kotlin
// Ciphertext survived but produced no record: the wrapping key is gone, not the data.
val stranded = preferences.contains(VAULT_KEY) && read(VAULT_KEY) == null
```

This requires exposing a presence/health signal from `SecureStorageService` (for example a
`vaultKeyState` that distinguishes `Absent`, `Readable`, and `Undecryptable`) rather than
inferring it from a `null` record. That API change is a prerequisite for the H-03 fix and
should be its own reviewable step.

### E-03 — H-03's user-visible outcome is an emptied vault, not first-run setup

**Original claims:** H-03 states the user "is handed first-run setup" and that
`RootViewModel.destination` "would route such a user to accounts (`RootScreen.kt:52`)"; the
Executive Summary similarly says the gate "routes to onboarding as if the install were
fresh".

**Correction.** These two statements are mutually inconsistent in the original text, and the
`RootScreen` branch is the one that actually executes. `RootViewModel.destination` evaluates
`complete && pending.isNotEmpty()` first and then `complete -> VaultDestinations.ACCOUNTS`
before reaching `else -> VaultDestinations.GET_STARTED`. Because `setupComplete` is a plain
DataStore flag that survives transfer, `complete` is `true` on the stranded device, so the
user lands on the **accounts list** — which renders `"No accounts yet"` /
`"Create an account to get started."` (`AccountsListScreen.kt:116-120`) — and never reaches
`GET_STARTED`.

Consequences for the fix and for testing:

- The observable symptom is an **apparently emptied vault**, which is worse for user trust
  than a fresh-install screen and is more likely to be reported as data loss.
- A D2D test that asserts "shows onboarding" will **fail against correct behaviour**; assert
  instead that the app does not present an empty account list as its final state.
- The `GET_STARTED` branch is not reached in this scenario, so route-based detection at
  `else -> GET_STARTED` is not a viable place to add the recovery check.

**Severity:** unchanged (High). The corrected symptom is a clearer user-visible failure than
the original description, not a lesser one.

### E-04 — Confirmed companion dead end: the mandatory-export lock

H-03 mentions the pending-export path in passing. Source verification confirms it is a hard
lock with no escape, and it is the more likely variant for a user who transfers mid-onboarding:

- `RootScreen` routes to `localAccountBackupExport(pending.first())` while the marker is
  non-empty.
- `LocalAccountBackupScreen.kt` installs `BackHandler { }` and passes `onBack = null` because
  the export is mandatory.
- With the account undecryptable, the screen renders `"Account not found"` (error colour)
  and no action.
- The only writer that clears the marker is `AppStorageService.markAccountLocallyBackedUp()`,
  called from `LocalAccountBackupViewModel` after a successful export, which requires a
  decrypted account. Nothing else removes the marker.

Any H-03 fix must clear or bypass `pendingLocalBackupAccountIds` on the stranded path, not
only add a recovery destination.

### E-05 — Closure gate for H-03

The report's Section 16 asks for a "D2D transfer observation" to resolve the PARTIALLY
CONFIRMED status. A real two-device transfer on an OEM that honours D2D remains the only way
to settle the platform-behaviour half. The four code-path defects (a)–(d) can be fixed and
verified independently, and should not wait for that observation; the deterministic
reproduction is to delete the Keystore entry and relaunch, per H-03's verification strategy.

## Appendix E — Remediation status (2026-11-12)

The findings text and Section 16's verdict above are the record at revision `01f3d2b` and are
left unmodified. This appendix tracks what has since landed. **No finding is closed here:**
closure needs the verification strategy named in its own section, which is a device or
Console observation, not a code review.

### E-06 — H-04 code half addressed; signing identity and custody remain open

**What landed.** `app/build.gradle.kts` now declares a `release` signing config and attaches
it to the `release` build type. Credentials are read from four environment variables —
`VAULT_RELEASE_KEYSTORE`, `VAULT_RELEASE_STORE_PASSWORD`, `VAULT_RELEASE_KEY_ALIAS`,
`VAULT_RELEASE_KEY_PASSWORD` — so no secret is stored in the repository. They are read via
`providers.environmentVariable` rather than `System.getenv`, because this project enables the
Gradle configuration cache and only `providers.*` values become cache inputs. A partial set
fails the build with a `GradleException` naming the missing variables; none set falls back to
the unsigned artifact so contributors without signing rights are not blocked; and
`VAULT_RELEASE_REQUIRE_SIGNING=true` fails the build when credentials are absent, which is
the CI setting.

**Verified.** `./gradlew :app:assembleRelease` succeeds with the credentials exported and
produces `app/build/outputs/apk/release/app-release.apk`; `apksigner verify -v` reports
`Verifies` with APK Signature Scheme **v2** (v1/v3/v3.1/v4 all false), subject `CN=vault`,
SHA-256 `ce2da0a44af29098d546c0c65fa5b3ff57a087e44f4af34a699dd171de04e77a`. A forced
`--rerun-tasks` package (not a cached artifact) re-verified the same certificate. The
partial-configuration and `REQUIRE_SIGNING` failure paths were exercised and both fail as
intended.

**Also landed for H-05.** `.github/workflows/release.yml` builds, signs, verifies
(`apksigner` on the APK, `jarsigner -verify` on the bundle), checks 16 KB alignment, and
uploads the `.aab`, `.apk` and `mapping.txt` on a `v*` tag. It is deliberately separate from
`ci.yml` so signing secrets are never exposed to `pull_request` runs. H-05 itself remains
open: the workflow has never executed. Its four secrets are now configured on a `release`
GitHub Environment (environment-scoped rather than repository-level, so they are injected
only into a job declaring that environment), but no tagged run has produced an artifact.

**Why this does not close H-04.** The finding's `done` condition — and Section 16's — is a
distributable artifact, and three things still stand between the build and that:

1. **The wired keystore is a development key, not an upload key.** `vault.jks` is
   self-signed `CN=vault` and lives in the repository working tree. Structurally it satisfies
   Play's cryptographic requirements (4096-bit RSA, SHA-256, `codeSigning` EKU —
   self-signing is normal and required), but it is stored in legacy **JKS** format, whose
   password protection is a single un-iterated SHA-1 pass, so a weak password is cheap to
   brute-force. Its provenance is unrecorded, and uploading it would make a development
   identity the permanent Play upload identity and, via `VAULT_ACCESS`, the KeyHive
   provider's trust anchor (Section 11.1, "contingent on the signing identity being real").
2. **Key custody, rotation, and compromise response are undocumented** — this was the
   finding's third required item and no document exists.
3. **No signed artifact has been uploaded to Play Console**, which is the finding's stated
   acceptance test.

**Also not addressed by this change.** Only the v2 scheme is produced. With `minSdk = 26`
that is installable, but v3 carries signing-key rotation, and Play App Signing is the
expected mechanism for it (Section 2.3). Decide rotation posture before the first upload.

**Effect on dependencies.** H-05 still needs a *signed* artifact in CI; the local fallback to
unsigned means CI must assert the signature explicitly rather than infer it from build
success, which `release.yml` does. Section 16's verdict is unchanged — H-04 and H-05 remain
blocking, and the "CONDITIONALLY READY" path still requires a documented, controlled
sideload process in their place.
