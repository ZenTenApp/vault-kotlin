# Backup status and reconciliation

## Purpose and scope

Reconciliation is the periodic or user-initiated process that checks every configured backup destination, automatically imports newly discovered valid associated accounts, records coverage status, and repairs confirmed gaps.

This process is implemented by `BackupSyncService`. It is distinct from the immediate write path: immediate backup attempts to publish a newly created account, while reconciliation verifies what destinations actually contain and repairs missing coverage.

## Triggers and concurrency

- The **Sync now** action on the Backup Status screen calls `sync(force = true)`.
- The periodic `BackupReconcileWorker` calls `sync()` every 15 minutes when WorkManager has network connectivity.
- A five-minute in-process cooldown applies to non-forced sync requests.
- A coroutine `Mutex` makes synchronization single-flight: one sync cannot run concurrently with another in the same process.
- A forced request bypasses the five-minute cooldown but still waits for the single-flight lock; it does not perform concurrent reconciliation.

If no master account is stored, synchronization returns without a result.

## Inventory model

At the start of a run, the service builds a local inventory:

```text
masterkey -> stored master account
<createdAt> -> each stored associated account
```

`masterkey` is the canonical identifier for master-account backup status. Each associated account uses its decimal `createdAt` value. This inventory can grow during the same run when remote discovery finds valid associated accounts.

Coverage is recorded for every inventory ID and every expected destination:

- one `nostr:<relay URL>` destination per configured relay; and
- one `brave-sync` destination.

## Relay reconciliation algorithm

For each configured relay, the service requests master events of kind `37801` and associated-account events of kind `37802`, constrained to the master account's derived Nostr public key.

### Relay request fails

If either request fails or times out:

- the relay presence set is unknown; and
- every account's status at that relay is `UNKNOWN`.

A failed check is never interpreted as a missing backup.

### Relay request succeeds

1. The service recognizes a master backup only when an event has the canonical `d` tag `masterkey`.
2. It recognizes associated-account candidates only when the `d` tag is numeric.
3. For every discovered associated-account ID that is not already local, it validates and decodes the event before automatically storing the account.
4. Every known inventory ID is marked `PRESENT` if the relay returned that canonical ID; otherwise it is marked `MISSING`.

The service preserves each relay's raw presence set during the run. When a new account is discovered after another source has already been checked, it backfills that account's earlier source statuses from those stored presence sets. This avoids leaving late discoveries without coverage entries.

## Brave Sync reconciliation algorithm

The service checks the master record with `hasMasterBackup(master)` and discovers associated accounts with `discoverSubAccounts(master)`.

- If either operation fails, all account statuses at `brave-sync` are `UNKNOWN`.
- If both succeed, the master record contributes `masterkey` when present.
- Valid discovered associated accounts are added automatically when not already local.
- Every inventory ID is then marked `PRESENT` or `MISSING` at Brave Sync from the discovered canonical ID set.

Brave Sync discovery validates record structure and private-key content before it contributes an account. Invalid records and decryption failures do not become local accounts.

## Automatic recovery during reconciliation

Reconciliation is also a recovery trigger. A valid associated account found at any successfully checked destination is persisted immediately if its ID is not already in secure storage. The user is not asked to select discovered accounts; all valid unique accounts are restored automatically.

An account found from one source is added to the inventory and compared with all sources checked in that same pass. The service does not overwrite an already-local account with a remote record.

For the validation rules applied to relay recovery candidates, see [Automatic account recovery](automatic-account-recovery.md).

## Coverage states

| State | Assigned when | Repair eligible? |
| --- | --- | --- |
| `PRESENT` | A successfully checked destination contains the canonical backup ID. | No |
| `MISSING` | A successfully checked destination does not contain the canonical backup ID. | Yes |
| `UNKNOWN` | The destination could not be checked successfully. | No |
| `REPAIR_FAILED` | A repair write for a `MISSING` record failed. | A later run may retry after checking again. |

The Backup Status screen currently renders these states as symbols. Status data also includes `checkedAtMillis`, the time the reconciliation result was stored.

## Repair algorithm

After discovery and coverage calculation, the service repairs only `MISSING` destinations:

- For a missing Nostr destination, it builds the appropriate master or associated-account event and publishes directly to that relay.
- For a missing Brave Sync destination, it writes the master or associated-account record through `BraveSyncBackupService`.

On a successful repair, the in-memory state becomes `PRESENT`. On failure, it becomes `REPAIR_FAILED`. The updated status list is saved to `AppStorageService`; a future sync checks the destination again rather than assuming the repair persisted.

`UNKNOWN` destinations are never repaired during that run because the app cannot distinguish an outage from an absent backup.

## Persistence

`AppStorageService.setBackupStatuses` writes the non-secret status cache to DataStore. Each `AccountBackupStatus` includes:

```text
accountId             canonical account ID, including masterkey
checkedAtMillis       reconciliation completion/check time
Destinations[]        destination ID, display label, and coverage state
```

Private keys, passphrases, and remote encrypted payloads must never be added to this cache.

## Operational limits and current limitations

- The reconciliation worker uses WorkManager's 15-minute minimum periodic interval and requires a connected network; timing is ultimately controlled by Android.
- Cooldown and single-flight protection are process-local. They do not coordinate work across separate process lifetimes beyond WorkManager's unique periodic work.
- The UI lacks full text equivalents, per-destination retry, last-checked display, and detailed remediation guidance.
- Relay coverage recognizes canonical `d` tags. It does not interpret a remote event as present solely because its event ID exists.
- Current destination configuration is code/configuration driven; the UI does not provide destination editing.

## Key source locations and tests

- Reconciliation: `app/src/main/java/app/zenten/vault/domain/BackupSyncService.kt`
- Periodic worker: `app/src/main/java/app/zenten/vault/background/BackupReconcileWorker.kt`
- Backup status UI: `app/src/main/java/app/zenten/vault/ui/backup/`
- Status persistence: `app/src/main/java/app/zenten/vault/data/kv/AppStorageService.kt`
- Relay transport: `app/src/main/java/app/zenten/vault/network/nostr/NostrRelayService.kt`
- Relevant tests: `app/src/test/java/app/zenten/vault/domain/BackupRecoveryServiceTest.kt` and `StartupRetrySchedulerTest.kt`.
