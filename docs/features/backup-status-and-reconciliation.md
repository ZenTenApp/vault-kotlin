# Backup status and consolidation

## Purpose

Vault consolidates account backups across independently addressed sources. A cycle restores missing local accounts from every trusted source before filling confirmed gaps at any source.

The fixed default sources, in processing order, are:

| Label | Type | Endpoint |
| --- | --- | --- |
| appdata1 | Nostr relay | `wss://appdata1.zentext.me` |
| appdata2 | Nostr relay | `wss://appdata2.nostr.box` |
| zenten sync | Brave Sync | `https://sync-v2.zenten.app/litesync` |
| brave sync | Brave Sync | `https://sync-v2.brave.com/v2` |

Defaults are enabled, trusted for restore, count toward master quorum, and cannot be removed or disabled. `BackupSourceRepository` supports persisted custom Nostr and Brave-compatible sources, but source-management UI is not implemented yet.

## Sequential execution

`AccountConsolidationEngine` protects all remote backup operations with one process-wide mutex. Sources are processed in stable order, one at a time. Accounts within each source are also processed one at a time; remote network operations are never deliberately concurrent.

## Phase 1: index and restore

For every enabled source:

1. Index canonical IDs on that source.
2. A failed index marks status `UNKNOWN`; failure does not prove an account is absent.
3. A successful index records `masterkey` and decimal associated-account IDs.
4. Existing local accounts are compared by canonical ID only. Their remote payloads are not decrypted or compared.
5. For an associated ID missing locally, candidates are tried newest-first until one decrypts and validates.
6. A valid recovered account is saved immediately and becomes part of the inventory considered for later sources.

The master is indexed but never restored remotely. The user must retain and import the master private key and passphrase.

Custom-source restoration requires both `restoreEnabled` and `trustedForRestore`; untrusted sources can still be indexed for status and backup repair.

## Phase 2: backup and read-back

Only after all phase-1 source reads finish:

1. Retain each successful phase-1 index; do not refresh it before writes.
2. Compare that snapshot with the complete local inventory, including accounts restored from later sources.
3. Write the master first, then associated accounts in local registry order, only when the canonical ID is absent.
4. Continue after individual write failures and persist pending writes without secret payloads.
5. Update the retained in-memory presence set after each acknowledgement.
6. Perform one final read-back for each successfully indexed source after its writes.

An acknowledged write is internally `WRITE_CONFIRMED`. For Nostr, acknowledgement includes a successful relay `OK` or an exact-event-ID `already exists` response to an idempotent retry; arbitrary blocked responses are failures. Network, timeout, authentication, and remote-rejection failures retain separate error categories. A successful final read-back upgrades returned IDs to `PRESENT`. Both states are displayed to users as **Backed up** because read-back is an internal reconciliation detail, not incomplete backup work; backed-up rows show the last successful backup time. A source whose initial index failed remains `UNKNOWN` and is not written in that cycle.

## Per-source states

| State | Meaning |
| --- | --- |
| `PRESENT` | A successful source read confirmed the canonical ID. |
| `WRITE_CONFIRMED` | The source acknowledged a write; displayed as **Backed up** while internal read-back remains pending. |
| `MISSING` | A successful source read did not contain the canonical ID. |
| `CHECKING` | Source indexing is in progress. |
| `BACKING_UP` | A write is in progress. |
| `BACKUP_FAILED` | The most recent write failed. |
| `UNKNOWN` | The source could not be checked conclusively. |

Account details show live rows for each source. An associated-account screen offers **Consolidate backup**, which reconciles only that account across all enabled sources. Master status is shown on the dedicated Master Account screen, which exposes exactly one primary action: **Retry master setup** until quorum setup completes, then **Consolidate backups** for the whole vault afterward. The former global Backup Status destination has been removed.

Persisted status contains account IDs, source IDs, state, coarse error category, and timestamps only. It must never contain private keys, passphrases, encrypted payloads, Brave sync codes/authentication values, or NIP-42 challenges.

## Master setup quorum

Setup attempts all enabled sources sequentially. It writes the master only after a successful source index proves that the canonical master ID is absent; an index failure remains unknown and is never treated as permission to push another entry. The master is retained in secure local storage even when remote quorum fails. Setup completes only when at least one quorum-eligible Nostr source and at least one quorum-eligible Brave Sync source already contain or successfully accept the master. The user can retry without reimporting the master.

## Scheduling and interruption

- Associated-account creation saves locally first, then enqueues targeted consolidation for that account.
- Associated-account details offer **Consolidate backup**, not a direct retry-write action. The targeted cycle indexes every source and reconciles only the selected account: it does not restore, write, or update statuses for unrelated accounts. It writes the selected account only when its canonical ID is absent from a successful source index.
- The Master Account screen offers **Consolidate backups** after setup; startup and periodic work also run full-vault consolidation. Immediate requests use an append-or-replace unique-work policy so account creation during an active targeted cycle queues a follow-up cycle instead of being discarded.
- Legacy account-backup work left in WorkManager by older app versions is handled as forced consolidation rather than a blind write.
- A one-hour periodic worker invokes consolidation.
- Consolidation uses a one-hour freshness threshold when there are no pending writes.
- A persisted checkpoint identifies the active cycle, trigger, phase, source, and account without storing secrets.
- A later run is safe to repeat because presence is determined by canonical IDs.

## Source locations and tests

- Source model/defaults: `domain/BackupSources.kt`
- Source persistence: `domain/BackupSourceRepository.kt`
- Adapters: `domain/BackupSourceAdapters.kt`
- Orchestration: `domain/AccountConsolidationEngine.kt`
- Workers: `background/BackupWorkers.kt`
- Status persistence: `data/kv/AppStorageService.kt`
- UI: `ui/accounts/AccountDetailScreen.kt`, `ui/master/MasterAccountDetailScreen.kt`
- Core tests: `domain/AccountConsolidationEngineTest.kt`, `domain/BackupSourceRepositoryTest.kt`, `data/kv/AppStorageServiceTest.kt`
