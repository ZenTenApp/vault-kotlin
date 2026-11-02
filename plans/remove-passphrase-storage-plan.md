# Plan: Stop Persisting the VaultKey Passphrase, Drop Periodic Consolidation

## 1. Goal

1. Never write the VaultKey passphrase (or an equivalent derivable secret) to disk.
2. Remove time-scheduled (hourly/periodic) consolidation.
3. After device authentication, gate the app on a passphrase entry screen.
4. Trigger consolidation once per unlock as a **background** one-time job, handing the
   passphrase to it through **memory only**, and use the session passphrase for whatever
   else needs it (export, account creation, secret-combination review).

The passphrase is entered once per foreground session and lives only in memory.

No data migration is required: the app is in development and has no existing installs, so
the stored `zenTenPass` field can simply be deleted (and with it, no legacy-JSON handling).

## 2. Current state (what actually stores / needs the passphrase)

The VaultKey is an `Account` record written by `SecureStorageService`
(`app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`). Associated
accounts use the same record type. The passphrase is a field on that record:

```kotlin
// data/model/Account.kt
data class Account(
    val createdAt: Long,
    val zenTenKey: String,   // passphrase-encrypted OpenSSH private key (must stay)
    val zenTenPass: String,  // <-- the passphrase, stored in encrypted prefs
    val nostr: NostrKeyPair,
    val accountName: String? = null,
)
```

`zenTenPass` is used by: `DefaultBackupSourceFactory.createRunContext`, `BraveSyncBackupService`,
`NostrBackupSource.restoreCandidate`, `RecoveryService`, `VaultKeyRecoveryService`,
`AccountService` (verify + create), `BackupService.buildBackupEvent`,
`CreateVaultKeyViewModel` (export + seed), `SecretCombinationsViewModel` (seed),
`OnboardingViewModel`.

Consolidation currently runs from:
- `VaultApplication.onCreate` -> `engine.consolidate(STARTUP)` / `consolidateVaultKey()`
- `WorkManagerBackupWorkScheduler.schedulePeriodic()` (hourly, registered in `onCreate`)
- one-time WorkManager requests from `SetupService`, `VaultKeyDetailScreen`,
  `AccountDetailViewModel`, `VaultKeyRecoveryService`

`AppUnlockCoordinator` keeps device-unlock state in memory only (30s background grace).
There is no passphrase gate after biometrics today.

## 3. Feasibility assessment

| Item | Verdict | Evidence / caveat |
| --- | --- | --- |
| Hilt worker can read a singleton `VaultKeySession` | **Feasible** | `hilt-work` is a dependency; `@HiltWorker` + `@AssistedInject`; `workerFactory` wired in `VaultApplication`. Non-assisted constructor deps resolve from the singleton graph. |
| Worker runs in the same process as the session | **Feasible** | No `android:process` in the manifest; WorkManager uses on-demand init via `Configuration.Provider`. |
| No secret in WorkManager `inputData` | **Feasible** | Only `account_id` / `trigger` / `force` are passed today; keep it that way. |
| Fail-closed when session is empty | **Feasible** | Worker can take `session.snapshot()`; `null` -> `Result.failure()` (not `retry`). |
| `Account.zenTenPass` removal | **Feasible, mechanical** | ~40 references; largest areas are `BackupSourceAdapters.kt`, `BraveSyncBackupService.kt`, `RecoveryService.kt`, `AccountService.kt`, tests. |
| Thread passphrase through backup sources | **Feasible, interface change** | `AccountBackupSource.restoreCandidate`/`write` and `BackupSourceFactory.createRunContext` currently take `Account`. They must take the passphrase (or a run-secrets object). `index()` only needs the stored identity. Touches `BackupSources.kt`, `BackupSourceAdapters.kt`, `AccountConsolidationEngine.kt`, `AccountConsolidationEngineTest`. |
| Remove periodic work / startup run | **Feasible, low risk** | No installs, so no periodic-work cancellation shim needed. |
| Passphrase launch gate | **Feasible, with care** | `secureStorage.getVaultKey()` does a Keystore decrypt; must not run synchronously in composition. Needs a small gate ViewModel exposing state. |
| Background job survives process death | **NOT guaranteed** | If the OS kills the process, WorkManager restarts it with an empty session and the job fails closed. WorkManager here gives delayed/constrained execution and retry while the process lives — not post-death execution. This is the accepted trade for not persisting the passphrase. |
| WorkManager vs in-session coroutine | **Both viable** | A `ProcessLifecycle`-scoped coroutine in the app graph is simpler and has the same "process must stay alive" limit. WorkManager adds constraints/backoff and survives Activity teardown. Recommendation: WorkManager, as requested. |

### Corrections found during review

1. **Do not lock the passphrase unconditionally on `onStop`.** Export/backup uses
   `Intent.ACTION_SEND` to another app, which stops `MainActivity`; an unconditional lock
   would force biometric + passphrase re-entry on every share round-trip and disrupt the
   resumable export wizard. Instead, clear the session only when
   `AppUnlockCoordinator` decides re-authentication is required (`mustAuthenticate`), so the
   existing 30s grace also covers quick share returns.
2. **The gate must read state asynchronously.** Add a gate ViewModel (e.g.
   `VaultUnlockGateViewModel`) exposing `Loading | Onboarding | PassphraseRequired |
   Unlocked`, computing VaultKey existence off the main thread. Do not call
   `secureStorage.getVaultKey()` inside the composable.

## 4. Proposed design

### 4.1 Memory-only `VaultKeySession`

Add a singleton holder (Hilt `@Singleton`, in-memory only):

```kotlin
@Singleton
class VaultKeySession @Inject constructor(
    private val secureStorage: SecureStorageService,
) {
    private val mutex = Mutex()
    private var passphrase: CharArray? = null
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /** Verifies by decrypting the stored VaultKey; fails on mismatch. */
    suspend fun unlock(candidate: CharArray): Result<Unit> = mutex.withLock { ... }

    /** Runs [block] under a copy of the current passphrase, or fails if locked. */
    suspend fun <T> withPassphrase(block: suspend (CharArray) -> T): Result<T>

    /** Standalone copy for a long-running job; caller must zero it. */
    suspend fun snapshot(): CharArray?

    /** Zeroes and drops the in-memory passphrase. */
    suspend fun lock()
}
```

Rules:
- No persistence anywhere (DataStore, prefs, `SavedStateHandle`, navigation arguments).
- `unlock` verifies with `SshKeyCodec.parse(vaultKey.zenTenKey, candidate)`.
- `lock()` overwrites the array and clears the flag; called when re-auth is required and
  lost automatically on process death.
- Document the JVM limitation: array copies may outlive the reference until GC. This is
  strictly smaller exposure than on-disk storage.

### 4.2 Delete the stored passphrase (no migration)

- Remove `zenTenPass` from `data/model/Account.kt`.
- No `ignoreUnknownKeys`, no scrub flag, no migration path — there are no installs.
- Update every construction/consumption site listed in §2 to use the session passphrase.
- Update fixtures in tests that reference `zenTenPass`.

### 4.3 Background consolidation triggered from the app (no periodic work)

Keep WorkManager for **execution** (constraints, retry/backoff, Activity-teardown
survival) but remove **scheduling**. Do not pass the passphrase through `inputData`.

- Delete `WorkManagerBackupWorkScheduler.schedulePeriodic()`, the periodic
  `PeriodicWorkRequestBuilder` helper, the `onCreate` call, and the `PERIODIC_*` constants.
- Delete the `VaultApplication.onCreate` startup `engine.consolidate(...)` block; the work
  is enqueued after unlock instead.
- Keep `AccountConsolidationWorker` / `VaultKeyConsolidationWorker`, but:
  - Inject `VaultKeySession` into each worker.
  - At the start of `doWork()`, take `session.snapshot()`. If `null` (locked, e.g. the
    process was restarted by WorkManager), return `Result.failure()` **without retry** so
    the next unlocked launch re-enqueues. Never `Result.retry()` into a locked session.
  - When unlocked, pass the snapshot to the engine for the whole operation, then zero it
    in a `finally`.
  - Keep `inputData` limited to `account_id` / `trigger` / `force`.
- Replace `BackupWorkScheduler` with a `ConsolidationScheduler` enqueued from the unlocked
  session:
  - `scheduleStartup()` — VaultKey + all accounts, called after unlock.
  - `scheduleAccount(accountId)`.
  - `scheduleVaultKey()`.
  - Requires `session.isUnlocked`; silently skips (or logs) otherwise.
- One-time requests keep `ExistingWorkPolicy.APPEND_OR_REPLACE`, the network constraint, and
  exponential backoff.

### 4.4 Expensive work is off the UI thread

The engine is `suspend` and does network I/O plus one bcrypt/BIP-39 derivation per run
context. It already runs on `Dispatchers.IO`; the WorkManager path adds no UI-blocking
work. The user-visible cost is CPU/battery during the post-unlock run, bounded by the
existing freshness check (`FRESHNESS_MILLIS = 1h`) and to at most once per unlock.

### 4.5 Launch gate: passphrase after biometrics

- Keep `AppUnlockCoordinator` for biometric/device-credential.
- Add `VaultUnlockGateViewModel` (`Loading | Onboarding | PassphraseRequired | Unlocked`),
  reading VaultKey existence off the main thread.
- In `MainActivity`, the `Unlocked` branch renders:
  - `Onboarding` -> `AvailableVaultApp()` (no gate; the fresh-install flow collects the
    passphrase itself);
  - `PassphraseRequired` -> new `VaultPassphraseScreen`;
  - `Unlocked` -> `AvailableVaultApp()`.
- On successful unlock, call `consolidationScheduler.scheduleStartup()` (guarded by
  `RemoteOperationPrecondition` and the engine's freshness checks).
- **Locking (per correction #1):** clear the session when `AppUnlockCoordinator` reports
  that re-authentication is required, not unconditionally on `onStop`. A quick return from
  an export share sheet keeps the session; a real backgrounding requires passphrase again.

Onboarding interaction:
- `OnboardingViewModel.performSetup`, `CreateVaultKeyViewModel`, and
  `VaultKeyRecoveryService.recover` call `vaultKeySession.unlock` after deriving the
  passphrase, so the rest of the wizard (export seed, secret combinations) can use it and
  post-setup consolidation can be enqueued.
- `CreateVaultKeyViewModel.loadSecretCombinationIndices` (the restored-process fallback)
  uses the session instead of `vaultKey.zenTenPass`.

### 4.6 Migrate every passphrase consumer

| Consumer | Change |
| --- | --- |
| `DefaultBackupSourceFactory.createRunContext` | take the passphrase snapshot, not `vaultKey.zenTenPass` |
| `BraveSyncBackupService` | accept the run sync code / passphrase from the caller |
| `NostrBackupSource.restoreCandidate`, `accountFromSshKey` | receive the passphrase from the run |
| `RecoveryService.decodeAssociatedAccount` | accept the session passphrase |
| `AccountService.verifyVaultKeyPassphrase` | verify by decrypting `zenTenKey` (delegate to session) |
| `AccountService.verifyAccountPassphrase` | parse `account.zenTenKey` with the candidate |
| `AccountService.createAssociatedAccount` | use the session passphrase |
| `CreateVaultKeyViewModel` / `SecretCombinationsViewModel` seed display | use the session passphrase |
| `BackupService.buildBackupEvent` | drop the passphrase-only re-validation check (key already validated on create/restore) |
| `VaultKeyRecoveryService.recover` | stop writing `zenTenPass`; unlock the session; schedule consolidation |
| `OnboardingViewModel.performSetup` | stop writing `zenTenPass`; unlock the session |
| `SetupService.addAccount` | `consolidationScheduler.scheduleAccount(id)` |
| `AccountDetailViewModel` / `VaultKeyDetailScreen` | in-session one-time scheduler |

**Interface change (the main mechanical cost):** introduce a run-secrets holder, e.g.

```kotlin
class VaultKeyRunSecrets(
    val vaultKey: Account,
    val passphrase: CharArray,
) : BackupSourceRunContext   // or carry it alongside the existing run context
```

and change `AccountBackupSource.restoreCandidate` / `write` and
`BackupSourceFactory.createRunContext` to take it. `index()` can keep taking `Account`.
The engine obtains one snapshot per operation and zeroes it when the run ends, so a
session lock mid-run cannot corrupt an in-flight job.

### 4.7 Secret-combinations review

The review flow is already passphrase-gated. Once the launch gate exists it can reuse the
unlocked session instead of prompting again, or keep the prompt for defense-in-depth.
Either way it must not read a stored value; the durable step marker stays non-secret.

## 5. Implementation phases

1. **Session plumbing** — add `VaultKeySession` + tests; wire DI.
2. **Delete stored passphrase** — remove `zenTenPass` and fix construction/consumption
   sites (one commit per group: backup sources, recovery, account service, UI).
3. **Launch gate** — gate ViewModel + `VaultPassphraseScreen`; wire into `MainActivity`;
   clear session on required re-auth.
4. **Background handoff** — inject `VaultKeySession` into the workers, snapshot + fail
   closed, remove periodic scheduling and the startup `onCreate` run, add
   `ConsolidationScheduler` and the post-unlock trigger.
5. **Tests + docs** (see §6, §7).

## 6. Tests to add / update

- New `VaultKeySessionTest` — unlock success/failure, `withPassphrase` when locked,
  `snapshot` copy semantics, `lock()` clears.
- New worker test — runs with an unlocked session; `Result.failure()` (no retry) with a
  locked session; no passphrase in `inputData`.
- New gate test — `Onboarding` vs `PassphraseRequired` vs `Unlocked` state derivation.
- New test — no periodic work is registered after startup.
- Update `AccountConsolidationEngineTest`, `AccountServiceTest`, `SetupServiceTest`,
  `VaultKeyRecoveryServiceTest`, `LocalAccountBackupViewModelTest`,
  `SecretCombinationsViewModelTest`, `AssociatedAccountsProviderContractTest`,
  `ProfileServiceTest`, `AccountsListFormattingTest`, `LocalAccountBackupShareTest` for the
  new `Account` shape and the run-secrets interface.
- Keep/adapt `SecureStorageServiceInstrumentedTest` for the new `Account` shape.

## 7. Documentation to update in the same change

- `docs/technical/security-model.md` — passphrase never at rest; verification by decryption.
- `docs/technical/architecture.md` — on-unlock WorkManager handoff, no periodic backup.
- `docs/features/device-authentication.md` — passphrase gate after biometrics.
- `docs/features/vault-key-setup.md` — onboarding no longer persists the passphrase.
- `docs/features/backup-status-and-reconciliation.md` — consolidation only on unlock.
- `docs/features/automatic-account-recovery.md` and `sensitive-key-access.md`.
- `docs/stakeholder/flowcharts.md` — app-launch, setup, export, consolidation flows.
- `docs/stakeholder/screens-and-states.md` and `user-journeys.md` — new gate screen.
- `docs/technical/product-status.md` — no post-process-death backup continuity.

## 8. Open decisions

1. **Grace period for the passphrase.** Recommendation: reuse the existing 30s biometric
   grace (per correction #1) so export round-trips stay smooth; require passphrase after
   real backgrounding.
2. **Defense-in-depth passphrase prompt for export/secret combinations**, or trust the
   session? Recommendation: keep the export prompt (deliberate disclosure); reuse the
   session for secret combinations.
3. **WorkManager vs in-session coroutine.** Recommendation: WorkManager with the in-memory
   handoff, as requested; both share the "process must stay alive" limitation.
4. **Associated-account passphrases.** This plan assumes all accounts use the VaultKey
   passphrase (the current restore path already does). Confirm no import path produces a
   different per-account passphrase; if one exists, the session must hold a per-account map.
