# Production-Readiness Audit — ZenTen Vault (Kotlin/Android)

- **Date:** 2026-11-10
- **Repository:** `/home/daniyal/projects/ZenTenApps/vault-kotlin`
- **Branch / commit:** `main` @ `01f3d2b`
- **Review type:** static, read-only. No project file was modified to produce this report.
- **Scope reviewed:** all 108 Kotlin source files (~15,800 lines) in `app/src/main`, the 41 host-side and 2 instrumented test files (~5,500 lines), Gradle / manifest / ProGuard / CI configuration, and the documentation set under `docs/`, `plans/`, and `audits/round1/`.

---

## 1. Executive summary

The codebase is unusually disciplined for a pre-release app: cryptographic parsing is defensive (work-factor range check *before* the KDF, seed/pubkey re-derivation, constant-time MAC comparison), backup truth is established by read-back rather than by write acknowledgement, secret lifetime is deliberately bounded, and prior audit findings are visibly fixed rather than papered over. The documentation is honest about what is not done.

The remaining blockers are concentrated, not diffuse:

- **One responsiveness defect is genuinely release-blocking** (`R-01`): `bcrypt_pbkdf` runs on the Android main thread in five user-reachable paths, including two that execute inside a Compose click callback. With the app's own supported maximum of 8192 rounds (~8 s of CPU on a desktop, longer on a phone) this is an ANR, and the app's own documentation states 8192 is selectable.
- **One behavioural defect contradicts documented behaviour** (`R-02`): any configuration change (rotation, dark mode, font scale, split screen) recreates the unlock coordinator and wipes the in-memory passphrase, so the user gets a biometric prompt *and* the passphrase screen again — behaviour the docs explicitly say only happens after 30 s in the background or a process death.
- **Several fail-open/fail-silent paths remain** (`R-03`, `R-07`, `R-08`), all previously identified in `audits/round1/remediation-plan-b.md` as H10/H12/H14 and still open.
- **The release artifact is not shippable**: unsigned, never built in CI, and the minified variant has never been smoke-tested. This is documented, and correct to document, but it still means no build in this repository has been verified as releasable.

No CRITICAL finding was confirmed. Nothing found indicates credential loss, key disclosure, or a broken recovery root.

---

## 2. Overall project quality

| Dimension | Assessment |
| --- | --- |
| Architecture | Good. Clear split: `ui` (MVVM + Compose, nested nav graphs, graph-scoped VMs), `domain` (services + consolidation engine), `data/kv` (non-secret DataStore), `data/secure` (Keystore AES-GCM), `crypto`, `bravesync`, `network/nostr`. Hilt throughout; no service locator; no God objects. |
| Kotlin correctness | Strong. Sealed interfaces for state, `Result`/`runCatching` used consistently at boundaries, `CancellationException` rethrown in workers, no global mutable state. |
| Concurrency | Mostly sound (`Mutex`-guarded session, generation counter in the access guard, atomic DataStore read-modify-write). Two main-thread blocking classes are the exception (`R-01`, `R-05`). |
| Compose | Idiomatic state hoisting, no `remember` misuse found, dialogs own their secret text. Gap: no Compose UI tests at all. |
| Security posture | Above average for pre-release: no logging anywhere (`grep` for `Log.`/`println`/`printStackTrace` returns zero hits in `app/src/main`), no analytics/crash SDK, `FLAG_SECURE`, `allowBackup="false"`, signature-permission provider, secrets never persisted. |
| Documentation | Excellent and honest; it documents its own gaps (unsigned release, untested minified build, free-tier scanning, accepted-risk decisions D1–D12). |
| Testing | Moderate. 266 test methods, good coverage of crypto parity, the consolidation engine, and setup/recovery services. Weak at the UI/`Activity` layer and at protocol-failure injection. |

---

## 3. Critical release blockers

None confirmed. No finding in this review indicates loss of key material, plaintext key disclosure, or failure of the recovery root.

---

## 4. High-priority issues

### R-01 — `bcrypt_pbkdf` runs on the Android main thread in five user-reachable paths

| Field | Value |
| --- | --- |
| **Severity** | HIGH |
| **Category** | Kotlin correctness / coroutines / Android lifecycle (ANR) |
| **Files** | `ui/accounts/AccountDetailScreen.kt:155` → `ui/accounts/AccountDetailViewModel.kt:61-62` → `domain/AccountService.kt:46-51` → `crypto/SshKeyCodec.kt:197-208`<br>`ui/newaccount/NewAccountScreen.kt:114` → `ui/newaccount/NewAccountViewModel.kt:23-27` → `domain/AccountService.kt:32-38`<br>`ui/secretcombinations/SecretCombinationsViewModel.kt:70-71`<br>`ui/vaultkey/VaultKeyDetailScreen.kt:160` → `VaultKeyDetailScreen.kt:83-92` → `ui/accounts/LocalAccountBackupShare.kt:66`<br>`ui/onboarding/OnboardingViewModel.kt:109` |
| **Line** | `SshKeyCodec.kt:200` is the blocking call |

**Evidence** (never secrets — this is the primitive only):

```kotlin
// crypto/SshKeyCodec.kt:197-208
private fun cryptBcrypt(mode: Int, input: ByteArray, salt: ByteArray, rounds: Int, passphrase: CharArray): ByteArray {
    val derived = ByteArray(48)
    BCrypt().pbkdf(String(passphrase).toByteArray(StandardCharsets.UTF_8), salt, rounds, derived)
```

```kotlin
// domain/AccountService.kt:32-36  — non-suspend, no dispatcher
fun verifyVaultKeyPassphrase(vaultKeyPassphrase: String): Result<Unit> = runCatching {
    ...
    runCatching { SshKeyCodec.parse(vaultKey.zenTenKey, vaultKeyPassphrase.toCharArray()) }
```

```kotlin
// ui/vaultkey/VaultKeyDetailScreen.kt:157-160 — inside ExportPassphraseDialog.onConfirmed
onConfirmed = { passphrase ->
    val text = viewModel.vaultKeyForExport(passphrase)
```

Each `SshKeyCodec.parse` performs one full `bcrypt_pbkdf` derivation. The app's own docs put the cost at "roughly 8 ms per round on a desktop CPU — so 128 is about one second and 8192 is over a minute" (`docs/technical/ssh-key-seed-derivation.md:121`), and 8192 is a selectable work factor (`SshKeyCodec.kt:48,57`). The app also explicitly permits *foreign* containers anywhere in 128–8192 (`SshKeyCodec.kt:277`), which the user can reach through **Paste VaultKey**.

**Explanation.** `AccountDetailViewModel.verifyExportPassphrase`, `NewAccountViewModel.verifyPassphrase`, `VaultKeyDetailViewModel.canExportVaultKey`/`vaultKeyForExport` and `AccountService.verify*Passphrase` are all synchronous. Three of them are invoked from Compose event callbacks on the main dispatcher; the other two (`SecretCombinationsViewModel:71`, `OnboardingViewModel:109`) run inside `viewModelScope.launch`, which is `Dispatchers.Main.immediate`. Only the unlock gate (`VaultKeySession.unlock`) and the create wizard (`CreateVaultKeyViewModel.verifyAndGenerate`) get this right with `withContext(Dispatchers.Default)` — so the correct pattern is already established in the codebase and simply not applied at these five sites.

**Impact.** Blocking the main thread for 1 s is a visible freeze (dropped frames, unresponsive Back). For a high-round key it exceeds the 5 s ANR window, producing an "App isn't responding" dialog during neutral actions (export confirmation, account creation, opening secret combinations). Users are likely to force-quit mid-operation. It also defeats `FLAG_SECURE`'s promise of a responsive locked UI, and the app's own comment calls the top of the band "expensive enough to stall unlock".

**Trigger conditions.** (a) User selects KDF rounds 4096/8192 in Settings, or imports/generates a key at that work factor, then performs any of the five actions. (b) A low-end device at the default 128 rounds. (c) Any pasted key anywhere in the accepted band, including a deliberately maximum-round hostile container — which is exactly the DoS the round ceiling in D12 was meant to bound, but the ceiling only bounds it to ~a minute rather than unbounded.

**Recommended fix.** Make `verifyVaultKeyPassphrase`, `verifyAccountPassphrase`, `vaultKeyForExport` (and the underlying `SshKeyCodec.parse` call sites) `suspend` and dispatch to `Dispatchers.Default`, as `VaultKeySession.unlock` already does. Convert `ExportPassphraseDialog.onConfirmed` to `(String) -> Unit` with the caller driving a `verifying` flag, so the dialog can render a busy state and the click returns immediately. Do not run KDF work in any click callback.

**Confidence.** High — the call chain is fully traced and the dispatcher is explicit at every hop.

**Verification.** Static trace of all five paths. Confirm dynamically by selecting 8192 rounds, creating an account, and opening **Export Account to Notes** on a device/emulator with StrictMode `detectDiskReads`/`ThreadPolicy` or a 2 s main-thread watchdog. This is also tracked as **H15 (still ⬜)** in `audits/round1/remediation-plan-b.md:47,337`.

---

## 5. Medium and low issues

### R-02 — Configuration change relocks the app and destroys the in-memory passphrase

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Android lifecycle |
| **Files** | `MainActivity.kt:80-82`, `MainActivity.kt:111-113`, `ui/auth/AppUnlockCoordinator.kt:106-127,155-188`, `app/src/main/AndroidManifest.xml:46-55` |
| **Line** | `MainActivity.kt:80` (`remember(activity) { AppUnlockCoordinator(...) }`), `:111` |

**Evidence.**

```kotlin
// MainActivity.kt:80-82 — coordinator is scoped to the Activity instance, not a ViewModel
val unlockCoordinator = remember(activity) {
    AppUnlockCoordinator(activity, ContextCompat.getMainExecutor(activity))
}
...
// :111-113
LaunchedEffect(unlockState) {
    if (unlockState !is AppUnlockState.Unlocked) gateViewModel.lockSession()
}
```

`AppUnlockCoordinator` holds its entire session state in plain fields (`wasUnlocked`, `backgroundAt`) and starts at `Checking` (`:114`). The manifest declares no `android:configChanges`, so rotation/font-scale/dark-mode/split-screen destroy and recreate the Activity, which recreates the coordinator with `wasUnlocked = false`. `onStart → onForeground()` then computes `requiresAuthentication(wasUnlocked = false, …) == true` and transitions to `Locked` (`:167-180`), auto-launching the biometric prompt. Meanwhile the `LaunchedEffect` observed the transient `Checking` state and called `gateViewModel.lockSession()`, zeroing the passphrase (`VaultKeySession.kt:66-70`).

**Explanation.** The `isChangingConfigurations` guard at `MainActivity.kt:119` only suppresses `onBackground()` on the *outgoing* coordinator; it cannot preserve the state of the coordinator that is being discarded.

**Impact.** Every rotation or theme/font change forces biometric re-authentication **and** re-entry of the VaultKey passphrase. This directly contradicts `docs/stakeholder/flowcharts.md:108` ("relocks on cold start or process restart, and requires authentication on the first foreground return after more than 30 seconds in the background") and `docs/technical/security-model.md` under **Non-promises**. On a phone rotated to enter a long passphrase, this is a repeated, confusing lockout.

**Trigger conditions.** Rotate the device, toggle system dark mode, change display/font size, or enter split screen while the vault is unlocked.

**Recommended fix.** Retain the coordinator (and its `AppUnlockState`) across configuration changes — e.g. hold it in an `AndroidViewModel`/retained holder, or make the session `wasUnlocked`/`backgroundAt` fields live in a process-scoped singleton alongside `VaultKeySession`, which already survives Activity recreation because it is a Hilt `@Singleton`. Also make the `lockSession()` `LaunchedEffect` react only to a *terminal* locked state, not to the transient `Checking`/`Authenticating` states.

**Confidence.** High for the recreation path; the only assumption is that the system does not retain the composable (it does not — `setContent` re-runs on the new Activity).

**Verification.** Static trace of coordinator lifetime and manifest. Confirm with "Don't keep activities" enabled, rotate on the accounts screen, and observe a second biometric prompt plus the passphrase screen.

---

### R-03 — Corrupt non-secret JSON silently resets state to empty

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Data integrity / stability |
| **File** | `data/kv/AppStorageService.kt:237-239` |
| **Line** | 237 |

**Evidence.**

```kotlin
private inline fun <reified T> decodeList(value: String?): List<T> = value?.let {
    runCatching { json.decodeFromString<List<T>>(it) }.getOrDefault(emptyList())
}.orEmpty()
```

Consumers include `customBackupSources` (`:45`), `pendingSourceWrites` (`:46`), `accountSourceStatuses` (`:47`), `locallyBackedUpAccountIds` (`:57`) and `pendingLocalBackupAccountIds` (`:60`).

**Explanation.** Any decode failure — a partially written preference, a schema change without a migration, or a disk fault — is reported as "the set is empty". Empty `pendingSourceWrites` makes the freshness check in `AccountConsolidationEngine.kt:131` and `:334` believe nothing is owed. Empty `pendingLocalBackupAccountIds` makes `RootScreen.kt:51` stop resuming the mandatory export. The subsequent write path (`:167`, `:211`, `:220`) then read-modify-writes from the same empty list, permanently overwriting the recoverable data.

**Impact.** Silent loss of backup obligations and of the "you still owe a local key export" marker. No user-visible error; the app just quietly stops believing it has work to do. This is precisely the "unreadable records are indistinguishable from absent ones" defect tracked as **H10 (⬜)** in `audits/round1/remediation-plan-b.md:46`.

**Trigger conditions.** Malformed or truncated value under any of those keys; a model field change shipped without a default; partial write during process death.

**Recommended fix.** Distinguish "absent" from "unparseable": return `null` on failure and surface a one-time reconciliation state (or rebuild from the authoritative sources — remote index plus local records) rather than an empty default. At minimum, record a non-secret `state_corrupt` flag and refuse to overwrite a value that failed to decode.

**Confidence.** High.

**Verification.** `AppStorageServiceTest` covers round-tripping but not a corrupt value. Unit-test by writing `"{not-json"` to `PENDING_SOURCE_WRITES` and asserting the resulting flow is empty and that a subsequent `upsert` drops prior real entries.

---

### R-04 — No throttling on passphrase verification anywhere except the create wizard

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Security / authentication |
| **Files** | `ui/auth/VaultUnlockGateViewModel.kt:64-79`, `ui/newaccount/NewAccountViewModel.kt:23-27`, `ui/secretcombinations/SecretCombinationsViewModel.kt:67-83`, `ui/accounts/ExportPassphraseDialog.kt:51-56` + `AccountDetailScreen.kt:154`, `VaultKeyDetailScreen.kt:157` |
| **Line** | `VaultUnlockGateViewModel.kt:64` |

**Evidence.**

```kotlin
fun submitPassphrase(passphrase: String) {
    if (verifying.value || passphrase.isBlank()) return
    verifying.value = true
    ...
    val result = vaultKeySession.unlock(passphrase.toCharArray())
```

There is no attempt counter, delay, or persisted deadline. By contrast `CreateVaultKeyViewModel.kt:453` implements an escalating 1/2/3-minute persisted lockout for the **Confirm Backup** paste — so the mechanism exists and is deliberately applied in only one place.

**Explanation.** Each wrong attempt costs one bcrypt derivation, which is itself the only rate limit (~1 s at 128 rounds). An attacker on an already-unlocked, already-authenticated device — or a malicious sibling app driving the UI through accessibility services — can submit continuously. The create-wizard control demonstrates the team considers this class of control necessary.

**Impact.** Weakens the passphrase as a second factor beyond device authentication. Note the boundary: this does not help an offline attacker (they attack the exported container, where no app control exists), and someone with an unlocked device and the app open is already inside the device trust boundary. That is why this is MEDIUM rather than HIGH.

**Trigger conditions.** Repeated wrong passphrases on the unlock gate, export dialogs, account creation, or secret-combinations gate.

**Recommended fix.** Reuse the existing persisted escalating-backoff helper across all verification entry points, keyed per flow. `VaultKeySession.unlock` already dispatches off-main, so a short delay is cheap to add there for every caller.

**Confidence.** High on absence; MEDIUM on impact weighting (threat-model dependent).

**Verification.** Submit wrong passphrases repeatedly on the unlock screen and observe no backoff. Tests: `VaultKeyDetailViewModelTest`, `SecretCombinationsViewModelTest`, `SecretCombinationsInputTest`.

---

### R-05 — Keystore decryption of all records on the main thread in list/detail composition

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Performance / stability |
| **Files** | `ui/accounts/AccountsViewModel.kt:32-37`, `ui/accounts/AccountDetailViewModel.kt:31-53`, `data/secure/SecureStorageService.kt:114-121` |
| **Line** | `AccountDetailViewModel.kt:36`, `AccountsViewModel.kt:35` |

**Evidence.**

```kotlin
// AccountDetailViewModel.kt:31-36 — `combine` runs on the caller's context; no flowOn
val state: StateFlow<AccountDetailState> = combine(accountId, sourceRepository.sources, appStorage.accountSourceStatuses) { id, sources, statuses ->
    val account = id?.let(accountService::getAccount)
```

```kotlin
// AccountsViewModel.kt:34-36
viewModelScope.launch {
    _state.value = AccountsState(loading = false, accounts = accountService.getAccounts())
}
```

`getAccount`/`getAccounts` → `SecureStorageService.getAccountById`/`readAccounts` → `crypto.decrypt` → `KeyStore.getInstance(...).load(null)` + `Cipher.doFinal` per record. `publishAccounts()` re-decrypts every record on every `addAccount`/`removeAccount` (`:72-75`, `:87-91`), and `addAccount` is called from `SetupService` during backup.

**Explanation.** A single Keystore round-trip is milliseconds, but `readAccounts` does one per record up to `MAX_ASSOCIATED_ACCOUNTS = 99` (`AccountService.kt:92`), and `addAccount` triggers a full re-scan. On cold start the accounts list therefore does N Keystore operations on the main thread, and each new account creation re-does them.

**Impact.** List-screen jank and slow first paint proportional to account count; combined with `R-01` this makes account creation feel progressively heavier.

**Trigger conditions.** Large account counts; cold start; each account creation; each detail-screen recomposition while the combine re-emits.

**Recommended fix.** Add `.flowOn(Dispatchers.Default)` to the combines and `withContext(Dispatchers.Default)` in `AccountsViewModel.refresh`, or expose `SecureStorageService` as suspending/dispatcher-wrapped. `SecureStorageService` already maintains `_accounts` as a `StateFlow` — `AccountsViewModel.refresh()` duplicating that collection (`init` block, `:23-28`) is redundant and can simply be dropped.

**Confidence.** High.

**Verification.** Static trace. Measure with `Choreographer` frame callbacks or Macrobenchmark on a 99-account fixture.

---

### R-06 — The whole navigation graph is replaced by a blocking screen without validated internet

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Product / UX (documented behaviour with an undocumented operational cost) |
| **Files** | `MainActivity.kt:172-189`, `ui/availability/AppAccessGuard.kt:51-62,117-135` |
| **Line** | `MainActivity.kt:172`, `AppAccessGuard.kt:51` |

**Evidence.**

```kotlin
// MainActivity.kt:172-189
if (!accessState.canUseApp) {
    AppBlockedScreen(blockers = accessState.blockers, ...)
} else {
    ... VaultNavGraph(...)
}
```

```kotlin
// AppAccessGuard.kt:51
val canUseApp: Boolean get() = hasCompletedCheck && blockers.isEmpty()
// AppAccessPolicy: NO_VALIDATED_INTERNET -> blockers non-empty; TIME_UNVERIFIABLE -> allowed
```

**Explanation.** The gate is intentional and documented (`docs/technical/architecture.md:74`, `docs/stakeholder/screens-and-states.md:39`) as a prerequisite for *timestamped remote* operations. But it is wired at the root of the navigation graph, not at the remote operations. Consequently, with no `NET_CAPABILITY_VALIDATED` network — airplane mode, captive portal, a VPN that fails validation, hotel Wi-Fi — the user cannot open the accounts list, view a fingerprint, export a key to Notes, or run the Secret Combinations review, none of which touch the network.

**Impact.** A local credential vault becomes unusable exactly when the user is most likely to want it (travel, offline recovery). It also creates an operational support surface: `onCapabilitiesChanged` fires frequently on some devices, and the refresh path is network-driven, so a flaky uplink can oscillate the entire UI.

**Trigger conditions.** Device online-but-unvalidated (captive portal, blocked `generate_204`), or a clock skew ≥ 2 min when both time sources respond.

**Recommended fix.** Keep the precondition at the remote call sites (it already is: workers and `SetupService`) and let the root navigation render offline-safe destinations. If a global gate is a product requirement, restrict it to the remote actions (setup/backup/consolidation/recovery), which is the stated intent of `RemoteOperationPrecondition`.

**Confidence.** High on behaviour; the finding is about scope, not about the existence of the check.

**Verification.** Enable airplane mode on the passphrase screen, unlock, and observe `AppBlockedScreen` instead of **Accounts**. Covered partially by `AppAccessGuardTest`/`AppAccessPolicyTest`, which assert the mapping but not the routing choice.

---

### R-07 — Brave Sync commit accepts a response with no per-entry result

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Networking / data integrity (backup truth) |
| **Files** | `bravesync/BravePasswordService.kt:96-101`, `bravesync/proto/SyncMessages.kt:90-97,80-87` |
| **Line** | `BravePasswordService.kt:96`, `SyncMessages.kt:93` |

**Evidence.**

```kotlin
// BravePasswordService.commit
private fun commit(session: Session, entity: ByteArray) {
    val response = request(session, SyncMessages.commitRequest(listOf(entity)))
    response.commit?.entryResults?.forEach {
        require(it.responseType == 1) { ... }
    }
}
```

```kotlin
// SyncMessages.parseCommit
return CommitResult(
    errorCode = 0,          // hardcoded
    errorMessage = null,    // hardcoded
    entryResults = fields.filter { it.number == 1 && it.group != null }.map(::parseCommitEntry),
)
```

**Explanation.** A `commit` response with **zero** entry groups, or an absent `commit` body, produces an empty list and the `forEach` never runs — the write is reported as successful. `parseCommit` also discards the enclosing error code, so a non-zero server error is invisible. The read-back verification in `AccountConsolidationEngine.ensureVaultKeyAtSource`/`ensureAccountAtSource` (`:162-170`, `:253-261`) does catch the *consequence* for consolidation and quorum, which is why this is no longer HIGH — but `BravePasswordService.create` and `createKnownMissing` are reachable from `BraveSyncBackupService` (`BackupSourceAdapters.kt:244-246`) and return `Result.success()` on the shallow check alone.

**Impact.** A malformed or partially-failing Brave Sync response is treated as a backup acknowledgement. Where read-back runs, this degrades to a wasted round-trip; where it does not, it is a false backup claim. This is the residual of **H12 (⬜)** in `audits/round1/remediation-plan-b.md:274-293`.

**Trigger conditions.** A Sync server that returns HTTP 200 with an empty or entryless `commit` body; a protocol-version drift; a truncated response.

**Recommended fix.** Require the expected branch and exactly one entry result with `responseType == 1`; reject absent bodies. Propagate `CommitResult.errorCode`/`errorMessage` instead of hardcoding success.

**Confidence.** High (code is explicit).

**Verification.** Static only. A loopback fixture returning `{}` or an entryless `CommitResponse` should make `commit` throw; no such test currently exists (`BraveSyncBackupServiceTest` does not exercise it).

---

### R-08 — Unbounded remote payloads and inventories

| Field | Value |
| --- | --- |
| **Severity** | MEDIUM |
| **Category** | Networking / stability / hostile input |
| **Files** | `bravesync/transport/BraveSyncTransport.kt:30-42`, `bravesync/proto/ProtoWire.kt:58-114`, `network/nostr/NostrRelayService.kt:130-190` |
| **Line** | `BraveSyncTransport.kt:42` |

**Evidence.**

```kotlin
// BraveSyncTransport.kt:41-42
if (response.header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true || isGzip(bytes)) {
    GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
```

`ProtoWire.Reader` validates truncation and group matching (`:82`, `:92`, `:108`) but imposes no field-count, group-depth, or byte cap. `NostrRelayService.fetchByRelay` maps every returned event without an event-count or per-event content cap.

**Explanation.** A compressed response is decompressed with no upper bound (`readBytes()` grows to whatever the stream yields), and a deeply nested group chain recurses without a depth limit. A relay can return an arbitrary number of events. Read timeouts (`callTimeout` 10 s, `withTimeoutOrNull`) bound *duration*, not *work* produced before the timeout — a fast hostile server can deliver far more than 10 s' worth of decompressed bytes.

**Impact.** Memory exhaustion / OOM and extended CPU burn from a compromised or malicious backup source. It cannot disclose secrets, hence not HIGH; but it can hard-crash the app during recovery, i.e. at the worst moment. Tracked as **H14 (⬜)**.

**Trigger conditions.** A hostile or compromised litesync endpoint or relay; a network middlebox injecting a gzip bomb.

**Recommended fix.** Cap decompressed size while streaming (`InputStream → limited copy`), cap protobuf field count and group depth, cap Nostr event count and per-event `content` length per fetch.

**Confidence.** High on absence of limits; MEDIUM on exploitability through the default vendor-run sources.

**Verification.** Static. `ProtoWireTest` covers truncation but not volume. Add a decompression-bomb test and a burst-event fixture.

---

### R-09 — Workers report success despite recorded source failures

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Reliability / observability |
| **File** | `background/BackupWorkers.kt:71`, `:119` |
| **Line** | 71, 119 |

**Evidence.** Both workers end with an unconditional `Result.success()` after `engine.consolidate(...)`/`engine.consolidateVaultKey(...)`, whose `ConsolidationResult.failures` / `VaultKeyBackupResult.failedSourceIds` are ignored. The comment at `:76-78` explains the deliberate choice: source-level failures are recorded as pending writes and repaired by the next cycle.

**Explanation.** The comment makes the reasoning sound — retrying the whole cycle immediately on one flaky source would be wasteful — but combined with no periodic work (`VaultApplication.kt:24-26`), repair now depends entirely on the next successful unlock. Tracked as **H11 (⬜)**.

**Impact.** A user who unlocks, sees a partially failed consolidation, and then keeps the app foregrounded or never unlocks again gets no automatic retry. Status cards do surface the state, so this is not silent to the user who looks.

**Recommended fix.** Distinguish "cycle completed with nonzero failures" from "cycle succeeded": either return `Result.retry()` when failures > 0 with a bounded attempt counter, or keep `success()` and schedule a one-shot follow-up with backoff when any failure is recorded.

**Confidence.** High.

**Verification.** `AccountConsolidationEngineTest` covers engine results; no worker-level test asserts the mapping.

---

### R-10 — Passphrase is copied into an unzeroable `String` inside the KDF

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Security hygiene |
| **File** | `crypto/SshKeyCodec.kt:200` |
| **Line** | 200 |

**Evidence.** `BCrypt().pbkdf(String(passphrase).toByteArray(StandardCharsets.UTF_8), salt, rounds, derived)` — only `derived` is zeroed in `finally` (`:206`); the intermediate `String` and `ByteArray` are left to the GC.

**Explanation.** Apache MINA's `pbkdf` accepts `ByteArray`, not `CharArray`, so a conversion is unavoidable at this boundary. But the temporary `ByteArray` could be zeroed after the call, and several callers themselves hold the passphrase as an immutable `String` (`VaultUnlockGateViewModel.submitPassphrase`, `OnboardingViewModel`, `NewAccountViewModel`, `ExportPassphraseDialog`), so zeroing `CharArray` downstream cannot reach those copies. `VaultKeySession` itself is correctly `CharArray`-based and zeroing (`:66-70`, `:72-76`) — the leak is at the UI boundary.

**Impact.** Reduces the value of the otherwise careful zeroing design: passphrase material remains in the heap until GC. Only exploitable with memory access (root, debugger, heap dump), which the security model already places outside the boundary — hence LOW.

**Recommended fix.** Zero the converted `ByteArray` in `finally`; standardise UI passphrase entry on `CharArray`/wipeable buffers where the Compose text field permits.

**Confidence.** High.

**Verification.** Static.

---

### R-11 — Remote-derived text is concatenated into user-facing errors

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Security / UX |
| **Files** | `bravesync/transport/BraveSyncTransport.kt:39`, `domain/SetupService.kt:41-45` |
| **Line** | `BraveSyncTransport.kt:39`, `SetupService.kt:41` |

**Evidence.** `throw IOException("Brave Sync HTTP ${response.code}: ${bytes.decodeToString().take(500)}")`, and `VaultKeyPersistedException("... " + (failure.message ?: "Retry the backup."), failure)` feeding `OnboardingState.error` (`OnboardingViewModel.kt:129`) which renders directly.

**Explanation.** A hostile or proxy-modified server response is displayed verbatim (up to 500 chars) to the user. There is no injection risk in Compose, but it is untrusted third-party content in a trusted UI, and it may leak server-side detail.

**Impact.** Low: confusing text at worst; no secret exposure found in the message construction.

**Recommended fix.** Map transport failures to fixed user-facing strings; keep the raw body in a non-user-facing diagnostic path only.

**Confidence.** High.

---

### R-12 — Vault Key creation/recovery IDs are not jittered or CAS-allocated

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Data integrity |
| **Files** | `ui/onboarding/OnboardingViewModel.kt:110`, `ui/onboarding/createvaultkey/CreateVaultKeyViewModel.kt:205`, `domain/VaultKeyRecoveryService.kt:92` |
| **Line** | as above |

**Evidence.** All three use the bare expression `createdAt = clock.millis() * 1_000L`. `AccountService.nextCreatedAt()` (`:81-88`) instead uses `millis*1000 + random(1,1000)` with a CAS, and its comment documents the convention as applying "for every other account created by the app".

**Explanation.** Vault Key records are singletons, so the practical collision risk is low; but recovery writes through `secureStorage.addVaultKey` and could collide with a concurrently generated key in the same millisecond, and the inconsistency contradicts the stated convention.

**Impact.** Extremely unlikely duplicate-ID overwrite; primarily a consistency defect.

**Recommended fix.** Route these through the same `nextCreatedAt` allocation as associated accounts.

**Confidence.** High.

---

### R-13 — SSH formatting recomputed during composition

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Compose performance |
| **File** | `ui/accounts/AccountDetailScreen.kt:105-108`, helpers at `:375-389` |
| **Line** | 105-108 |

**Evidence.** `SshPublicKeyCard(publicKey = formatSshPublicKey(account.zenTenKey, comment = ""))`, `formatSshFingerprint(...)`, `formatKdfRounds(...)` are called in the composition body. Each does a Base64 decode; the fingerprint does SHA-256; none memoizes.

**Impact.** Repeated work on every recomposition of the detail screen, including recompositions triggered by source-status flows (`AccountDetailViewModel` emits on every status update).

**Recommended fix.** `remember(account.zenTenKey, account.createdAt) { ... }`.

**Confidence.** High.

---

### R-14 — Launch-router `combine` can throw on a malformed pending ID

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Kotlin correctness |
| **File** | `ui/root/RootScreen.kt:51` |
| **Line** | 51 |

**Evidence.** `complete && pending.isNotEmpty() -> VaultDestinations.localAccountBackupExport(pending.first().toLong())`. `pending` is a `Set<String>` decoded from JSON (see `R-03`); any non-numeric entry throws `NumberFormatException` inside the `combine` transform, terminating the destination flow and leaving the root in its `null` initial state.

**Trigger conditions.** Corrupt or hand-modified `pending_local_backup_account_ids`, or a future writer storing a non-numeric ID.

**Recommended fix.** `pending.first().toLongOrNull()?.let { ... } ?: VaultDestinations.ACCOUNTS`.

**Confidence.** High on the code path; low likelihood.

---

### R-15 — Provider advertises an item MIME type for a collection URI

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Android platform correctness |
| **File** | `keyhive/AssociatedAccountsProvider.kt:58-60` |
| **Line** | 59 |

**Evidence.** `ACCOUNTS -> "vnd.android.cursor.item/vnd.zenten.associated-accounts"` — the plural `cursor.item` for a directory URI is incorrect; it should be `vnd.android.cursor.dir/...`.

**Impact.** Clients that branch on `getType` (or framework helpers that do) will treat the multi-row result as a single item. `AssociatedAccountsProviderContractTest` exists — verify whether it asserts the type.

**Recommended fix.** Use `cursor.dir` for the collection, `cursor.item` for the row.

**Confidence.** High.

---

### R-16 — Production-dead code

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Maintainability |
| **Files** | `domain/RecoveryService.kt` (entire class; referenced only by `AssociatedAccountLabelTest`), `domain/BackupService.kt:18,24` (`backupVaultKey`, `backupAssociatedAccount` — no production callers; `buildBackupEvent` is used), `bravesync/BraveSyncClient.kt` (no references) |

**Explanation.** `RecoveryService` duplicates the account-discovery logic that `AccountConsolidationEngine` now performs, and retains a nullable `BraveSyncBackupService` injection pattern. Dead crypto/backup code is a liability in a security surface: it can drift from the live contract and still be tested as if it were authoritative (`AssociatedAccountLabelTest` passes against code no user can reach).

**Recommended fix.** Delete the unused paths, or mark them `@Deprecated` with a tracking issue; move any genuinely valuable test coverage onto the live engine.

**Confidence.** High (verified with repository-wide reference search).

---

### R-17 — `SimpleDateFormat` allocated per account row

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Performance |
| **File** | `ui/accounts/AccountsListScreen.kt:203` |

**Evidence.** `SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(accountId / 1_000L))` inside `formatAccountMonth`, called from `accountsByMonth` for every account.

**Recommended fix.** Hoist a reusable formatter (it is not thread-safe, so keep it per-composition or use `java.time` `DateTimeFormatter`, which is).

**Confidence.** High.

---

### R-18 — 32 hardcoded English UI strings despite 160 string resources

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Localization / UX |
| **Files** | 25 `Text("...")` occurrences across `*Screen.kt` plus 7 elsewhere; examples: `AppBlockedScreen.kt:34,40,43,50,53,60`, `VaultKeyDetailScreen.kt:128,140,148,155,161`, `ExportPassphraseDialog.kt:52,60,72`, `VaultPassphraseScreen.kt:61,73,99,105,110` |

**Explanation.** `res/values/strings.xml` holds 160 entries (including the 3-arg KDF-round format variants), so the infrastructure exists; the newer screens bypass it. `docs/stakeholder/screens-and-states.md:170` already lists "review final wording and behavior" as outstanding.

**Impact.** No translation path for the newest flows; mixed localization if a partially translated build ships.

**Confidence.** High (counted).

---

### R-19 — Supply-chain hardening gaps

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Build / CI |
| **Files** | `gradle/wrapper/gradle-wrapper.properties:3`, `.github/workflows/ci.yml` |
| **Line** | wrapper line 3; `ci.yml` `actions/checkout@v4`, `actions/setup-java@v4`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4` |

**Evidence.** `distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip` with `validateDistributionUrl=true` but no `distributionSha256Sum`. `security.yml` pins actions to commit SHAs; `ci.yml` uses mutable major-version tags — the same asymmetry `docs/technical/build-and-test.md:101` notes as non-blocking.

**Impact.** A mutable action tag or a substituted Gradle distribution executes arbitrary code in a build that handles release artifacts. Low likelihood, high consequence.

**Recommended fix.** Add `distributionSha256Sum`; pin `ci.yml` actions to SHAs as `security.yml` already does.

**Confidence.** High.

---

### R-20 — `PublishResult.failedRelays` mixes normalized and raw URLs

| Field | Value |
| --- | --- |
| **Severity** | LOW |
| **Category** | Correctness (currently inert) |
| **File** | `network/nostr/NostrRelayService.kt:104` |

**Evidence.** `return PublishResult(event.id, successful, relayUrls.toSet() - successful, attempts)` where `successful` holds URLs returned by Quartz (normalized) and `relayUrls` is the caller's configured list. Set subtraction therefore cannot match, and `failedRelays` reports every configured relay. No production consumer reads `failedRelays` today (verified), so impact is nil until someone does.

**Recommended fix.** Normalize before subtracting, or compute `failed` from the acknowledgement map directly.

**Confidence.** High on the code; no impact today.

---

## 6. Security assessment

**Strengths verified in code and configuration:**

- **Offline container hardening.** `SshKeyCodec.readEncryptedContainer` validates magic, cipher name, KDF name, block alignment, and range-checks the declared bcrypt round count *before* running the KDF (`:245-290`, `:277`), with `MIN=128`/`MAX=8192` (`:37,48`). `parsePrivateBlob` requires matching duplicate check ints, pubkey match, 64-byte `private||public`, correct padding, and re-derives the pubkey from the seed. This closes the seed/pubkey-mismatch class (prior C2).
- **MAC verification is constant-time.** `NigoriCrypto.decrypt` uses `MessageDigest.isEqual` before any plaintext is produced.
- **Recovery is identity-bound.** `VaultKeyRecoveryService` accepts a candidate only after the decrypted seed re-derives to the exact entered words (`:70-86`), distinguishing "incompatible copy" from "absent" and "unreachable".
- **Secret lifetime.** Passphrase never persisted; `VaultKeySession` is `CharArray` + `Mutex` + zeroing on lock; workers snapshot and zero in `finally` (`BackupWorkers.kt:83-85`, `:131-133`).
- **Storage boundary.** Keystore AES-256-GCM, non-exportable key, fresh IV per write, versioned payload, no registry (documented atomicity rationale at `SecureStorageService.kt:20-28`). Non-auth-bound is decision **D5**, explicitly accepted — not reported as a defect.
- **Exposure controls.** `FLAG_SECURE` (`MainActivity.kt:66`), `allowBackup="false"`, signature `readPermission` on the provider with `grantUriPermissions="false"`, no `usesCleartextTraffic`, no network-security-config exceptions, no logging, no telemetry or crash SDK.
- **Published-data disclosure is documented, not hidden.** Account label in the `label` tag / `label:<name>` note is accepted risk **D4**; recorded in `docs/technical/security-model.md`.

**Weaknesses found:** `R-01` (availability), `R-04` (no attempt throttling), `R-07`/`R-08` (fail-open and unbounded remote input), `R-10` (passphrase copies). No issue found that discloses plaintext key material or permits recovery of a key without the passphrase.

**Boundary caveats (from the docs, confirmed accurate):** a rooted/compromised device, a malicious same-signature sibling app, and remote-server replication are outside the protection boundary. The app is honest about all three.

---

## 7. Stability and performance assessment

- **ANR risk:** `R-01` is the single most serious stability issue and is deterministic at high work factors.
- **Main-thread I/O:** `R-05`.
- **Memory:** `R-08` (unbounded decompression, unbounded field lists/events).
- **Lifecycle correctness:** `R-02` (configuration change destroys session), plus a startup `clearLegacyBackupState()` fire-and-forget (`VaultApplication.kt:21-23`) that is harmless because it is idempotent.
- **Concurrency:** `operationMutex` serialises consolidation; DataStore read-modify-write is atomic; no lost-update patterns found in non-secret state; the storage registry removal fixed the prior lost-account class.
- **Network timeouts:** relay connect 8 s, per-publish 8 s with 3 attempts (`NostrRelayService.kt:79,345`), server-time `callTimeout(10 s)`, `fetchAll` bounded by `timeoutMillis`. `readTimeout(0)` on the WebSocket is appropriate for a long-lived subscription and is bounded by the surrounding `withTimeoutOrNull`.
- **Background work:** unique names, `APPEND_OR_REPLACE`, `NetworkType.CONNECTED`, 30 s exponential backoff, fail-closed when the session is locked. Correctly no periodic work.

---

## 8. Missing tests

Verified by listing the suite (43 files, 266 `@Test` methods):

1. **No Compose UI tests at all** — `grep` for `createComposeRule`/`createAndroidComposeRule` across `app/src/test` and `app/src/androidTest` returns nothing. Every screen is untested at the UI layer, including the passphrase dialogs that are the gate for key export.
2. **No test for `AppUnlockCoordinator`** — only the pure `DeviceLockPolicy` is tested (`DeviceLockPolicyTest.kt`). The Activity-level lifecycle behaviour in `R-02` is untested.
3. **No test for `MainActivity` routing** — the unlock → passphrase → access-guard → NavHost composition is untested.
4. **No malformed-Brave-response tests** — `R-07` would be caught by an empty/entryless `CommitResponse` fixture. `BraveSyncBackupServiceTest` exists but does not exercise it.
5. **No hostile-volume tests** — no gzip-bomb, oversized-field-list, or burst-event fixtures (`R-08`). Prior audit B07 explicitly requested these.
6. **No work-factor-boundary tests on the five main-thread paths** (`R-01`); `SshKeyCodecTest` covers the band itself but not dispatcher placement.
7. **Instrumented coverage is two files** (Keystore crypto, secure storage) — no on-device coverage of biometric gating, API 28–29 credential fallback, or rotation.
8. **CI builds only the debug variant** — `assembleDebug`, `lintDebug`, `testDebugUnitTest`. The minified release build has never been compiled in CI. Documented at `docs/technical/build-and-test.md`, and tracked as **H2 (⬜)**, but it remains an evidence gap.

---

## 9. Areas that appear healthy

- **Cryptographic parsing and validation** — the strongest part of the codebase. Range checks precede expensive work; every field of the OpenSSH container is validated; a seed is never trusted without re-deriving its public key.
- **Backup truth model** — index-before-write, "unknown is not missing", read-back confirmation before quorum, and quorum persisted separately from `setupComplete`. `SetupService.performSetup` records the quorum flag it actually observed and `completeGeneratedVaultKeySetup` re-reads it rather than trusting a caller.
- **Recovery** — candidates are tried newest-first, a corrupt copy cannot mask a valid one, "incompatible copy" is a distinct error from "not found", and only a successful identity-bound decrypt is persisted.
- **Secret handling discipline** — no passphrase on disk, no secrets in WorkManager input data, no logging, immediate clipboard clearing on paste (with an API-level fallback below 28).
- **Prior audit remediations** — C1, C2, H3, H4, H5, H6, H7, H8, H9, H19, H20 are demonstrably fixed; H16's Brave-official endpoint has been removed from the defaults (`BackupSources.kt:30-80` now lists only vendor `litesync1`/`litesync2`). This review did not re-report any of them.
- **Documentation honesty** — `docs/technical/security-model.md` documents accepted risks and non-promises; `build-and-test.md` states the release artifact is unsigned and untested. No claim of production readiness is made in the repo.
- **Versioning compliance** — `versionCode 1` / `versionName "0.0.1-dev.1"` (`app/build.gradle.kts:18-19`) matches the pre-release policy in `docs/technical/build-and-test.md`.

---

## 10. Recommended fixing order

1. **`R-01`** — move all five verification/decryption paths off the main thread. Highest impact, small diff, correct pattern already exists in `VaultKeySession.unlock` and `CreateVaultKeyViewModel`.
2. **`R-02`** — retain unlock/session state across configuration changes. Small diff, high user-visible benefit, resolves a documentation contradiction.
3. **`R-07`** and **`R-08`** — fail-closed Brave responses and bounded remote input. These are the last two "backup truth / hostile input" items from the round-1 backlog.
4. **`R-05`** — dispatch storage reads; drop the redundant `AccountsViewModel.refresh()`.
5. **`R-03`** — distinguish corrupt from empty for persisted state.
6. **`R-04`** — reuse the existing lockout helper across verification entry points.
7. **`R-06`** — decide, as a product decision, whether the offline gate belongs at the root or at remote operations.
8. **Testing** — add Compose UI tests for the five KDF paths, an `AppUnlockCoordinator`/rotation test, and malformed-Brave-response tests.
9. **`R-09`–`R-20`** — polish, in the order listed (correctness first, hygiene last).
10. **Release engineering** (tracked as H1/H2, out of code scope here) — signing, release CI, minified-build smoke test, device matrix.

---

## 11. Release recommendation

### **NOT READY FOR RELEASE**

**Reasoning.**

The credential-integrity core is in good shape — I found no path that loses a key, discloses a plaintext key, or breaks the recovery root. That is the hardest part of this product and it has been done well.

The recommendation is driven by four pieces of evidence:

1. **`R-01` is a deterministic ANR** in five user-reachable paths, at work factors the app itself offers and documents. A credential vault that freezes during "confirm passphrase to export" is not releasable.
2. **`R-02` breaks the documented lock model** in a way every user will hit: rotation costs a biometric prompt plus a full passphrase re-entry. The documentation and the behaviour disagree, and the documentation is the authoritative description of a security control.
3. **Two remote-input defects remain open** from the round-1 backlog (`R-07` fail-open commit validation, `R-08` unbounded decompression/inventories). A vault must not accept a malformed acknowledgement as a backup, and must not be crashable by the server it backs up to.
4. **No artifact in this repository has been verified as shippable.** The release build is unsigned, never built in CI, and never smoke-tested under R8. The team already states this; it confirms, rather than contradicts, this verdict.

`READY AFTER REQUIRED FIXES` is plausible for a future review once items 1–4 above are addressed and the Compose/lifecycle test gaps are closed — that is the intended path, and the existing backlog (`audits/round1/remediation-plan-b.md`) already tracks most of it.

---

## Review limitations

- This was a **static review only**. No device or emulator was run, no biometric prompt was exercised, no network traffic was captured, and no APK was built during this review.
- The host-side unit suite (`./gradlew :app:testDebugUnitTest --offline -q`) was executed earlier in the session and **exited 0 with no output**; it was not re-run during final verification. Instrumented tests (`app/src/androidTest`) require a device and were **not executed**.
- **`R-02` is derived from code and manifest inspection**, not from observed rotation behaviour; the conclusion that Android does not retain the composable is a platform guarantee relied on but not executed.
- The backup servers (`appdata1/2`, `litesync1/2`) were **not contacted**; all protocol conclusions come from the client code and the documented contract in `docs/technical/brave-sync-backup-protocol.md`.
- Server-side behaviour, TLS configuration, and actual relay/EOSE semantics could not be verified. `R-07` and `R-08` describe the client's handling of hostile or malformed responses, not a demonstrated server defect.
- Dynamic performance claims (ANR timings) rely on the codebase's own documented bcrypt cost figures rather than measurement on target hardware.
- No secret, credential, or private user data is reproduced in this report; the evidence quoted is code structure and public protocol constants only.

## Documentation impact

**No documentation was updated.** The audit brief explicitly required a read-only review — *"Do not modify, rewrite, or automatically fix any source files"* and *"Do not modify any project files"* — so `AGENTS.md`'s documentation-maintenance rule is satisfied by that instruction taking precedence. If these findings are acted on, the following documents will need updating in the same change:

- `docs/technical/security-model.md` — D12 work-factor cost notes, once KDF placement changes.
- `docs/stakeholder/flowcharts.md:108` and `docs/features/device-authentication.md` — the 30-second lock model, once `R-02` is fixed.
- `docs/features/sensitive-key-access.md` — export verification becomes asynchronous (`R-01`).
- `docs/stakeholder/screens-and-states.md:39` — gate scope, if `R-06` is decided.
