# Backup, Restore, and Consolidation Implementation Plan

## 1. Confirmed behavior

The replacement will use a **source-oriented architecture**. Each endpoint is an independent backup source with its own index, writes, failures, retries, and account statuses.

### Default source order

| Order | ID | Label | Type | Endpoint |
| ---: | --- | --- | --- | --- |
| 1 | `default-nostr-appdata1` | appdata1 | Nostr | `wss://appdata1.zentext.me` |
| 2 | `default-nostr-appdata2` | appdata2 | Nostr | `wss://appdata2.nostr.box` |
| 3 | `default-brave-zenten` | zenten sync | Brave Sync | `https://sync-v2.zenten.app/litesync` |
| 4 | `default-brave-official` | brave sync | Brave Sync | `https://sync-v2.brave.com/v2` |

These sources will be fixed, enabled, non-removable, processed in stable order, eligible for setup quorum, and trusted for restoration.

The model will include `enabled`, `isDefault`, `order`, `countsTowardQuorum`, `restoreEnabled`, and `trustedForRestore` so additional Nostr and Brave-compatible sources can be introduced later.

### Phase-2 indexing rule

Phase 2 retains and uses each source's phase-1 index. It does **not** re-index immediately before writing. After all missing records have been written sequentially to a source, the source is indexed once more to verify the writes.

```text
Phase 1:
    index source and retain its canonical ID set

Phase 2:
    use retained phase-1 ID set
    write locally present IDs missing from that set
    update in-memory set after each acknowledged write
    perform one final read-back after all writes to that source
```

Writes must remain idempotent because another device could alter a source between the two reads.

## 2. Domain model

### 2.1 Backup source configuration

Introduce a source definition independent of transport implementation:

```kotlin
@Serializable
data class BackupSourceConfig(
    val id: String,
    val label: String,
    val type: BackupSourceType,
    val endpoint: String,
    val order: Int,
    val enabled: Boolean,
    val isDefault: Boolean,
    val removable: Boolean,
    val countsTowardQuorum: Boolean,
    val restoreEnabled: Boolean,
    val trustedForRestore: Boolean,
    val authentication: SourceAuthentication,
)

@Serializable
enum class BackupSourceType {
    NOSTR_RELAY,
    BRAVE_SYNC,
}

@Serializable
enum class SourceAuthentication {
    NIP42_OPTIONAL,
    BRAVE_SYNC_TOKEN,
}
```

Source identity will use a stable ID, not its label or current list position. Status and pending work will reference that stable ID.

### 2.2 Source registry

Add a `BackupSourceRepository` responsible for:

- returning defaults in stable order;
- loading persisted custom sources;
- normalizing endpoints;
- rejecting duplicates;
- validating `wss` for relay sources and `https` for Brave-compatible sources;
- protecting default sources from removal or disabling;
- adding, disabling, enabling, removing, and reordering custom sources;
- exposing sources as a `Flow`;
- triggering consolidation when a new source is added or re-enabled.

Custom source management APIs will be implemented, but no management UI will be added in this task.

Endpoint normalization will include:

- lowercase URI scheme and hostname;
- remove default ports;
- normalize trailing slashes;
- reject fragments and embedded credentials;
- preserve meaningful Brave endpoint paths such as `/litesync`;
- reject an endpoint already registered after normalization.

### 2.3 Canonical account identity

Use a provider-neutral ID:

```kotlin
@Serializable
sealed interface BackupAccountId {
    @Serializable
    data object Master : BackupAccountId

    @Serializable
    data class Associated(val createdAt: Long) : BackupAccountId
}
```

Remote canonical representations remain unchanged:

```text
Master:      masterkey
Associated:  decimal createdAt
```

The existing remote formats remain compatible:

- Nostr kinds `37801` and `37802`;
- canonical `d` tags;
- NIP-44 encrypted OpenSSH key payloads;
- Brave records under the existing `.zenten` URLs.

### 2.4 Source adapter contract

Replace grouped backup targets with independently instantiated source adapters:

```kotlin
interface AccountBackupSource {
    val config: BackupSourceConfig

    suspend fun index(master: Account): SourceIndexResult

    suspend fun restoreCandidate(
        master: Account,
        candidate: RemoteAccountCandidate,
    ): Result<Account>

    suspend fun write(
        master: Account,
        accountId: BackupAccountId,
        account: Account,
    ): SourceWriteResult
}
```

`index()` returns canonical IDs and opaque candidate references. It does not validate or compare account private-key payloads merely to establish presence.

```kotlin
data class SourceIndexResult(
    val sourceId: String,
    val ids: Set<BackupAccountId>,
    val candidates: Map<BackupAccountId, List<RemoteAccountCandidate>>,
    val invalidRecordCount: Int,
)
```

An adapter factory will build the correct adapter from configuration:

```kotlin
interface BackupSourceFactory {
    fun create(config: BackupSourceConfig): AccountBackupSource
}
```

This permits any number of independently addressed Nostr and Brave-compatible sources.

## 3. Payload and duplicate policy

### 3.1 ID-only presence checks

Presence and backup comparison use only the canonical account ID:

- If an ID exists locally and remotely, the source is considered to contain that account.
- Vault does not decrypt the account payload merely to compare it with local material.
- Vault does not overwrite a remote record whose canonical ID is already present.
- Different payloads under the same ID do not produce a conflict status.
- Local data is retained without inspecting or replacing remote data.

### 3.2 Restoration validation

Payload decryption and key validation happen only when an ID is missing locally and restoration is needed.

A restored account must pass the existing security contract:

- correct source-specific record shape;
- valid canonical numeric ID;
- valid Nostr event signature, kind, author, and `d` tag where applicable;
- successful decryption;
- valid Ed25519 OpenSSH private key;
- successful parsing with the retained master passphrase;
- normalized private-key representation.

Invalid candidates never enter secure local storage.

For Brave Sync, protocol-level decryption of sync entities is naturally required to read record IDs. “ID-only” means Vault will not parse or compare the contained SSH private key unless restoration is required.

### 3.3 Duplicate records

Adapters will defensively group remote candidates by canonical ID.

When an account is missing locally:

1. Order duplicate candidates newest-first where the source provides meaningful ordering/version information.
2. Try candidates sequentially.
3. Restore the first candidate that decrypts and validates successfully.
4. Skip malformed candidates.
5. Record a safe invalid-record count.
6. Do not expose a conflict state.

When the account already exists locally, candidate payloads are not inspected.

## 4. Master import flow

### 4.1 State progression

Introduce explicit onboarding states:

```text
EMPTY
→ MASTER_STORED_PENDING_BACKUP
→ MASTER_BACKUP_IN_PROGRESS
→ SETUP_COMPLETE
```

The securely stored master account is retained if quorum is not reached. `setupComplete` remains false, and the user receives a retry action without having to re-enter the private key.

### 4.2 Sequential backup algorithm

After validation and deterministic Nostr/Brave derivation:

1. Store the master account in secure storage.
2. Mark onboarding as pending master backup.
3. Process all four enabled default sources sequentially.
4. For each source:
   - index/check for `masterkey` when appropriate;
   - if already present, count it as confirmed success by ID;
   - otherwise write the master backup;
   - accept an acknowledged Nostr write or successful Brave commit for initial quorum;
   - record source-level status and failure category.
5. Continue through all four sources even if quorum is reached early.
6. Evaluate quorum after all source attempts.

Success requires:

```text
at least one eligible Nostr source succeeded
AND
at least one eligible Brave Sync source succeeded
```

Any eligible source of each type may satisfy its side of the quorum; sources are not paired.

### 4.3 Quorum outcomes

#### Full success

When all sources accepted or already contained the master:

- mark setup complete;
- schedule immediate consolidation;
- enter the app without waiting for consolidation.

#### Partial success with quorum

When at least one relay and one Brave server succeeded but some sources failed:

- mark setup complete;
- persist failed `(master, source)` operations;
- schedule retries;
- schedule consolidation;
- show degraded source statuses without blocking app use.

#### No relay quorum, no Brave quorum, or neither

- retain the master securely;
- keep setup incomplete;
- show a safe, category-specific error;
- allow retry without re-importing the key.

Invalid key or passphrase remains distinct from network and quorum failures.

## 5. Associated-account creation

The local device remains authoritative for account creation:

```text
generate account
→ save account securely
→ report local creation success
→ enqueue sequential backup to every enabled source
```

Remote failure never removes a locally saved associated account.

The direct backup operation will:

1. capture the current enabled-source list in stable order;
2. process one source at a time;
3. write only the newly created account;
4. persist each failed `(account ID, source ID)` operation;
5. update per-source status after every attempt;
6. schedule network-constrained retry work.

Creating another account remains allowed while consolidation is active. It is saved immediately, and its backup request enters the central queue with higher priority than routine consolidation.

## 6. Consolidation engine

Replace `BackupSyncService` and `RecoveryService` orchestration with a single `AccountConsolidationEngine`.

### 6.1 Global serialization

All remote account operations share one application-wide coordinator:

```kotlin
interface BackupOperationCoordinator {
    suspend fun consolidate(trigger: ConsolidationTrigger): ConsolidationResult
    suspend fun backUpAccount(accountId: BackupAccountId): AccountBackupResult
    suspend fun retryPending(): RetryResult
}
```

A process-wide mutex and priority queue enforce:

- at most one remote source operation at a time;
- accounts within a source are processed sequentially;
- UI and local account operations remain usable;
- immediate account backup can run after the current atomic source/account operation;
- no remote calls from onboarding, workers, manual sync, or account creation run concurrently.

Priority order:

1. master quorum retry;
2. newly created associated-account backup;
3. persisted failed write retry;
4. manual consolidation;
5. startup consolidation;
6. periodic consolidation.

WorkManager provides cross-restart scheduling. Persisted operation state prevents work from being lost, while the process coordinator prevents overlapping calls in a running app.

### 6.2 Phase 1: restore source by source

For each enabled source that is both `restoreEnabled` and `trustedForRestore`, in stable order:

1. Mark source status as `CHECKING`.
2. Index that source.
3. If indexing fails:
   - mark its account statuses `UNKNOWN`;
   - store a safe failure category;
   - do not treat any ID as missing;
   - continue to the next source.
4. If indexing succeeds:
   - retain the source's canonical ID set and candidate map for phase 2;
   - mark known matching IDs `PRESENT`;
   - identify IDs missing locally;
   - restore each missing associated account sequentially.
5. For each missing ID:
   - inspect candidate records newest-first;
   - decrypt and validate only because restoration is required;
   - persist the first valid account immediately;
   - add it to the local inventory;
   - continue on invalid candidates.
6. Never restore the master from a source. The master record participates only in presence checking and repair.

An account restored from an earlier source becomes part of local inventory before the next source is processed. If later sources report the same ID, they are marked present without payload inspection.

### 6.3 End of restore phase

After all restore-enabled sources have been attempted, local secure storage contains the union of:

- accounts already on the device;
- valid missing accounts restored from appdata1;
- valid missing accounts restored from appdata2;
- valid missing accounts restored from zenten sync;
- valid missing accounts restored from brave sync;
- accounts from future trusted sources, in configured order.

Source indexes remain retained as phase-1 snapshots.

### 6.4 Phase 2: back up source by source

For each enabled source that indexed successfully in phase 1:

1. Select the retained phase-1 index.
2. Compare all current local IDs against that index.
3. Process locally present but remotely missing IDs sequentially:
   - master first;
   - associated accounts in stable registry order.
4. After each acknowledged write:
   - add the ID to the in-memory source index;
   - mark it `WRITE_CONFIRMED`;
   - clear its pending `(account, source)` operation.
5. If a write fails:
   - mark it `BACKUP_FAILED`;
   - persist pending work;
   - continue with the next account.
6. After all writes to the source, index that source once again.
7. If final indexing succeeds:
   - returned IDs become `PRESENT`;
   - acknowledged IDs not returned remain internally `WRITE_CONFIRMED`;
   - absent, unacknowledged IDs remain `BACKUP_FAILED` or `MISSING`.
8. If final indexing fails:
   - preserve acknowledged writes as `WRITE_CONFIRMED`;
   - do not downgrade them to failed;
   - leave unverified source data for a future cycle.
9. Continue to the next source.

A source that failed phase-1 indexing is not written during that cycle because absence was not established. It remains `UNKNOWN`.

### 6.5 Idempotency

Repeated cycles must not create unnecessary writes:

- matching canonical ID means present;
- Nostr replacement semantics continue to use the canonical `d` tag;
- Brave records use the existing deterministic URL/username;
- phase 2 writes only IDs absent from the retained source index;
- successful writes update the in-memory index;
- final read-back establishes verified presence;
- retries remain source- and account-specific.

## 7. Status model

### 7.1 Internal status

```kotlin
@Serializable
enum class AccountSourceBackupState {
    PRESENT,
    WRITE_CONFIRMED,
    MISSING,
    CHECKING,
    BACKING_UP,
    BACKUP_FAILED,
    UNKNOWN,
}
```

There will be no `CONFLICT` or `NEVER_CHECKED` state. Before the first successful check, status is `UNKNOWN`.

### 7.2 User-facing mapping

| Internal state | UI text |
| --- | --- |
| `PRESENT` | Backed up |
| `WRITE_CONFIRMED` | Backed up, verification pending |
| `MISSING` | Missing |
| `CHECKING` | Checking |
| `BACKING_UP` | Backing up |
| `BACKUP_FAILED` | Backup failed |
| `UNKNOWN` | Unavailable / unknown |

### 7.3 Durable status record

```kotlin
@Serializable
data class AccountSourceStatus(
    val accountId: String,
    val sourceId: String,
    val state: AccountSourceBackupState,
    val lastCheckedAtMillis: Long?,
    val lastSuccessfulBackupAtMillis: Long?,
    val lastAttemptAtMillis: Long?,
    val errorCategory: BackupErrorCategory?,
)
```

Errors will be categorized safely:

- `NETWORK_UNAVAILABLE`
- `TIMEOUT`
- `AUTHENTICATION_FAILED`
- `REMOTE_REJECTED`
- `REMOTE_PROTOCOL_ERROR`
- `INVALID_REMOTE_RECORD`
- `LOCAL_ACCOUNT_UNAVAILABLE`
- `UNKNOWN`

Raw payloads, keys, passphrases, sync codes, authorization tokens, NIP-42 challenges, and server response bodies will not be persisted in status or diagnostics.

## 8. Account and master status UI

### 8.1 Associated-account details

Update `AccountDetailViewModel` to expose a live `StateFlow` containing:

- account data;
- ordered configured sources;
- source status rows;
- active backup/consolidation progress.

Each source row will show:

- source label;
- source type;
- normalized hostname or endpoint;
- state;
- last checked time when available;
- last successful backup time when useful;
- a safe error summary;
- a retry action for failed, missing, or unknown states.

Status changes will update live while work runs.

### 8.2 Master-account details

Add a dedicated Master Account details screen reachable from the main/root screen.

It will show:

- master public-key/fingerprint information where appropriate;
- setup/quorum state;
- relay quorum summary;
- Brave quorum summary;
- the same per-source backup rows;
- retry master backup;
- trigger consolidation.

Sensitive-key disclosure will retain the existing secure-window safeguards.

### 8.3 Remove global Backup Status

Remove:

- the existing global Backup Status screen;
- its destination/navigation entry;
- `BackupStatusViewModel`;
- `BackupStatusScreen`.

Per-account and master details become the canonical backup-status surfaces.

## 9. Persistence

Replace the old grouped target queues and status cache.

### 9.1 Persisted source state

DataStore will store:

- custom source configurations;
- source ordering;
- enabled/disabled state;
- source trust and quorum flags.

Defaults are generated by code and merged with persisted custom sources. They cannot be removed or disabled.

### 9.2 Pending operations

```kotlin
@Serializable
data class PendingSourceWrite(
    val accountId: String,
    val sourceId: String,
    val reason: PendingWriteReason,
    val attemptCount: Int,
    val lastAttemptAtMillis: Long?,
)
```

This replaces target-level values such as `NOSTR` and `BRAVE_SYNC`.

### 9.3 Consolidation checkpoint

Persist non-secret state:

```kotlin
@Serializable
data class ConsolidationCheckpoint(
    val cycleId: String,
    val trigger: ConsolidationTrigger,
    val phase: ConsolidationPhase,
    val currentSourceId: String?,
    val currentAccountId: String?,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
)
```

No remote payload or candidate data is persisted.

If the process dies:

- retain the fact that work was interrupted;
- discard the stale source-index snapshot;
- start a fresh restore phase on restart;
- rely on idempotent source writes.

### 9.4 Legacy data

Because no deployed installation must be migrated:

- remove old pending target queues;
- remove legacy relay-only queue logic;
- remove old status serialization;
- ignore or clear old keys;
- do not implement compatibility migrations for development-only state.

Secure account storage remains intact as the primary local account store.

## 10. Scheduling

### 10.1 Consolidation triggers

Full consolidation runs after:

- successful master quorum;
- startup when stale or pending work exists;
- periodic WorkManager execution;
- manual user request;
- adding or re-enabling a custom source in the future.

Associated-account creation triggers direct backup of that account only.

### 10.2 Periodic policy

Use:

- periodic consolidation every **1 hour**;
- connected-network constraint;
- persisted one-hour freshness threshold;
- skip startup consolidation when a successful cycle is still fresh and no pending work exists;
- allow manual consolidation regardless of freshness;
- exponential retry backoff beginning at 30 seconds;
- indefinite retries at bounded WorkManager frequency.

Android controls exact periodic execution timing.

### 10.3 Workers

Replace relay-specific worker naming with:

- `AccountBackupWorker` for pending account/source writes;
- `AccountConsolidationWorker` for complete cycles;
- optional `MasterBackupRetryWorker` if separating incomplete-onboarding work simplifies constraints.

All workers call the same operation coordinator and cannot bypass global serialization.

## 11. Brave Sync changes

The current Brave implementation is bound to one hard-coded server. Refactor it so each source creates an independent transport/service instance:

```kotlin
BraveSyncTransport(
    serverUrl = source.endpoint,
    serviceKey = BraveSyncConfig.SERVICE_KEY,
)
```

Both default endpoints use:

- the same sync code derived from the master;
- the same service key;
- the same protobuf protocol;
- the same authorization mechanism;
- the same `/command/` request path;
- independent server-side chains and copies.

Expected self-hosted command endpoint:

```text
https://sync-v2.zenten.app/litesync/command/?...
```

`BravePasswordService` will accept its transport through constructor injection instead of constructing a fixed transport internally.

Indexing will return canonical IDs without parsing the SSH payload unless restoration is required.

## 12. Nostr and NIP-42 changes

Fixing authentication against both default relays is in scope.

### 12.1 Source isolation

Each relay adapter will call exactly one relay URL. Group publication APIs will no longer determine success for source-specific orchestration.

For every operation:

- establish a connection to one relay;
- handle that relay's challenge;
- authenticate with the master-derived Nostr signer;
- perform the read or write;
- wait for the appropriate EOSE or OK result;
- close the connection.

### 12.2 NIP-42 state handling

Replace timing-based authentication assumptions with an explicit operation state:

```text
CONNECTING
→ WAITING_FOR_AUTH_OR_READY
→ AUTHENTICATING
→ AUTHENTICATED
→ REQUEST_SENT
→ RESPONSE_CONFIRMED
```

Required behavior:

- validate the relay URL in the received challenge;
- sign a kind `22242` NIP-42 auth event with the derived Nostr key;
- include the challenge and relay tags required by NIP-42;
- send AUTH before protected REQ/EVENT work;
- wait for authentication acknowledgement where the relay supplies one;
- resend or begin the pending operation after successful authentication;
- avoid relying only on the existing 250 ms grace delay;
- handle relays that do not request authentication;
- distinguish authentication rejection from timeout and ordinary publication rejection;
- prevent an unauthenticated operation from being discarded before AUTH completes.

### 12.3 Authentication acceptance tests

Working NIP-42 behavior against both default relays is part of acceptance:

- `wss://appdata1.zentext.me`
- `wss://appdata2.nostr.box`

Real-service tests remain opt-in. Deterministic fake-relay tests will cover:

- immediate AUTH challenge;
- delayed AUTH challenge;
- no challenge;
- successful authentication followed by read;
- successful authentication followed by publication;
- rejected AUTH;
- timed-out AUTH;
- challenge for wrong relay;
- reconnect/resend behavior;
- exactly one active relay operation.

## 13. Removal and replacement map

Remove or replace the responsibilities of:

- `AccountBackupTarget`;
- `BackupTargetId`;
- `AccountBackupCoordinator`;
- `NostrAccountBackupTarget`;
- existing grouped `BackupSyncService`;
- existing orchestration in `RecoveryService`;
- `RelayBackupRetryService`;
- `RelayBackupRetryScheduler`;
- `RelayBackupWorker`;
- `StartupRetryScheduler`;
- global backup-status UI and navigation;
- grouped target pending queues;
- single Brave destination assumptions.

Retain and adapt:

- `BackupService` event construction and remote format;
- Nostr event codec;
- NIP-44;
- SSH key validation;
- Brave protobuf and crypto implementation;
- secure account storage;
- account creation;
- WorkManager and Hilt infrastructure.

Suggested new domain areas:

```text
domain/backup/source/
    BackupSourceConfig.kt
    BackupSourceRepository.kt
    BackupSourceFactory.kt
    AccountBackupSource.kt
    NostrBackupSource.kt
    BraveBackupSource.kt

domain/backup/consolidation/
    AccountConsolidationEngine.kt
    ConsolidationModels.kt
    BackupOperationCoordinator.kt
    MasterBackupPolicy.kt
    PendingBackupService.kt

background/
    AccountBackupWorker.kt
    AccountConsolidationWorker.kt

ui/master/
    MasterAccountDetailScreen.kt
    MasterAccountDetailViewModel.kt
```

Exact package boundaries can be adjusted to repository conventions during implementation.

## 14. Test plan

### 14.1 Source repository tests

Verify:

- four defaults and exact ordering;
- labels and endpoint values;
- defaults cannot be removed or disabled;
- custom Nostr and Brave sources can be added;
- custom sources can be disabled and removed;
- URL normalization;
- duplicate rejection;
- stable source IDs and ordering;
- quorum and trust flags;
- adding a source schedules consolidation.

### 14.2 Master onboarding tests

Cover:

- all four sources succeed;
- relay 1 plus Brave 1 succeeds;
- relay 2 plus Brave 2 succeeds;
- one category succeeds but the other fails;
- neither category succeeds;
- all four sources are attempted despite early quorum;
- operations are sequential;
- master is retained when quorum fails;
- setup remains incomplete on quorum failure;
- retry succeeds without re-import;
- partial failures become pending source writes;
- setup completes before background consolidation finishes;
- distinct user-facing failure categories.

### 14.3 Account creation tests

Verify:

- local save occurs before remote calls;
- local account survives every remote failure;
- all sources are attempted sequentially;
- failure on one source does not prevent later sources;
- pending entries are account/source-specific;
- direct backup does not trigger full consolidation;
- new-account backup receives priority after the current atomic operation.

### 14.4 Consolidation tests

Use fake sources with call logs to verify exact order:

```text
restore appdata1
restore appdata2
restore zenten sync
restore brave sync
backup appdata1
backup appdata2
backup zenten sync
backup brave sync
```

Also test:

- accounts accumulate locally source by source;
- only locally missing IDs are decrypted;
- existing IDs do not inspect payloads;
- invalid candidate followed by valid duplicate;
- source index failure produces `UNKNOWN`;
- failed source is not written in phase 2;
- later sources continue after failure;
- retained phase-1 snapshot drives missing-ID calculation;
- no pre-write index refresh;
- one post-write source read-back;
- acknowledged writes become `WRITE_CONFIRMED`;
- final read-back upgrades them to `PRESENT`;
- one failed account write does not stop later accounts;
- accounts restored from any source propagate to other successfully indexed sources;
- master is indexed and repaired but never remotely restored;
- repeat cycle is idempotent;
- no network concurrency occurs;
- interrupted cycle restarts from a fresh restore phase.

### 14.5 UI tests

Verify:

- one ordered row per configured source;
- live status changes;
- safe error summaries;
- retry action;
- master details navigation;
- master quorum display;
- global Backup Status destination removed;
- sensitive values never appear in status or error UI.

### 14.6 Real-service tests

Real-service tests are opt-in only and excluded from normal unit and CI runs unless explicitly configured. They cover:

- read/write against both Nostr relays;
- NIP-42 authentication;
- read/write against both Brave servers;
- source isolation;
- a complete four-source cycle using disposable test material.

No production private keys or sync credentials will be committed.

## 15. Acceptance criteria

The implementation is complete when:

1. The four default sources are represented and processed independently in fixed order.
2. Master import attempts all four sources sequentially.
3. Setup succeeds only with at least one eligible relay and one eligible Brave server.
4. Failed quorum retains the master securely and permits retry without re-import.
5. Successful quorum allows app entry and starts consolidation in the background.
6. Associated accounts are stored locally before any remote work.
7. Every remote operation is globally serialized.
8. Consolidation completes restore phase source by source before backup phase begins.
9. Presence comparison is based only on canonical IDs.
10. Payloads are decrypted and validated only when a missing local account must be restored.
11. Phase 2 uses phase-1 source indexes without a pre-write refresh.
12. Each written source receives one final read-back after its writes.
13. Source and account failures do not prevent later work.
14. Per-account/source status is durable and live in account details.
15. Master status is available on a dedicated Master Account details screen.
16. The global Backup Status screen is removed.
17. Pending retries are tracked per account and source.
18. Interrupted consolidation restarts with a fresh restore phase.
19. Repeated cycles are safe and avoid unnecessary duplicate writes.
20. NIP-42 authentication works correctly against both default relays.
21. Both Brave endpoints operate as independent copies using the same derived sync-chain credentials.
22. Custom Nostr and Brave source APIs exist without requiring source-management UI.
23. No secret material is persisted in status, diagnostics, errors, or WorkManager input.
24. Relevant feature, technical, stakeholder, security, UI-flow, and test documentation is updated with the implementation.

## 16. Recommended implementation sequence

To keep reviews manageable, implementation should proceed in these stages:

1. **Source model and repository**
   - defaults, normalization, custom-source persistence, tests.
2. **Source adapters**
   - independently addressed Nostr and Brave implementations.
3. **NIP-42 repair**
   - explicit authentication lifecycle and relay tests.
4. **Persistence replacement**
   - source-specific status, pending writes, and cycle checkpoints.
5. **Master backup policy**
   - quorum, retained pending setup, retry flow.
6. **Consolidation engine**
   - sequential restore phase, backup phase, and final verification.
7. **Associated-account integration**
   - local-first creation and prioritized direct backup.
8. **Workers and application startup**
   - one-hour scheduling, freshness, retry backoff, and one coordinator.
9. **UI**
   - account source statuses, Master Account details, and removal of global status.
10. **Cleanup**
    - delete obsolete grouped-target services, queues, workers, and tests.
11. **End-to-end verification and documentation**
    - deterministic full-cycle tests and opt-in real-source tests.
