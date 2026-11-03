# Backup status and consolidation

## Purpose

Vault consolidates account backups across independently addressed sources. A cycle restores missing local accounts from every trusted source before filling confirmed gaps at any source.

The fixed default sources, in processing order, are:

| Label | Type | Endpoint |
| --- | --- | --- |
| appdata1 | Nostr relay | `wss://appdata1.zentext.me` |
| appdata2 | Nostr relay | `wss://appdata2.nostr.box` |
| litesync1 | Brave Sync | `https://litesync1.zentext.me/litesync` |
| litesync2 | Brave Sync | `https://litesync2.nostr.box/litesync` |
| brave sync | Brave Sync | `https://sync-v2.brave.com/v2` |

Defaults are enabled, trusted for restore, count toward Vault Key quorum, and cannot be removed or disabled. `BackupSourceRepository` supports persisted custom Nostr and Brave-compatible sources, but source-management UI is not implemented yet.

## Sequential execution

`AccountConsolidationEngine` protects all remote backup operations with one process-wide mutex. Sources are processed in stable order, one at a time. Accounts within each source are also processed one at a time; remote network operations are never deliberately concurrent.

## Phase 1: index and restore

For every enabled source:

1. Index canonical IDs on that source.
2. A failed index marks status `UNKNOWN`; failure does not prove an account is absent.
3. A successful index records `vaultkey` and decimal associated-account IDs. General consolidation uses only the associated-account IDs; `vaultkey` belongs to the separate Vault Key flow.
4. Existing local accounts are compared by canonical ID only. Their remote payloads are not decrypted or compared.
5. For an associated ID missing locally, candidates are tried newest-first until one decrypts and validates.
6. A valid recovered account is saved immediately and becomes part of the inventory considered for later sources.

The Vault Key is indexed but this reconciliation pass never restores it remotely. A remote Vault Key copy is read back only by the separate **Recover VaultKey** flow, which needs the 24 Secret Combinations and the passphrase; the user must otherwise retain and import the Vault Key private key and passphrase.

Custom-source restoration requires both `restoreEnabled` and `trustedForRestore`; untrusted sources can still be indexed for status and backup repair.

## Phase 2: backup and read-back

Only after all phase-1 source reads finish:

1. Retain each successful phase-1 index; do not refresh it before writes.
2. Compare that snapshot with the complete local inventory, including accounts restored from later sources.
3. Write associated accounts in local registry order, only when the canonical ID is absent. The Vault Key is not written by this phase; `AccountConsolidationEngine.backupVaultKey` owns it because it publishes under a different Nostr identity.
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

Account details show a **Backup Status** section with live cards for each source. Each card uses the source endpoint as its title and places one status line at the bottom right: successful sources show **Backed up** with a compact relative time such as `30 min ago`, `5d ago`, or `2y ago`; in-progress, missing, unavailable, and failed states show their current status there, with failed rows also showing the error category. The associated-account details content scrolls when it exceeds the available screen height, keeping the source list and account action reachable; it uses the shared header row with an icon-only back arrow that returns to the account list. An associated-account screen offers **Consolidate backup**, which reconciles only that account across all enabled sources. Vault Key status is shown without its SSH public key on the dedicated Vault Key screen. It offers an **Export VaultKey to Notes** action at the top that first asks the user to re-enter the passphrase protecting the VaultKey, then sends the self-describing encrypted VaultKey text through the in-app target picker. Its **Backup Status** source cards have consistent small gaps and it exposes exactly one backup action: **Retry Vault Key setup** until quorum setup completes, then **Consolidate VaultKey backup** afterward, which reconciles the Vault Key record only. Like the account-detail screen, the Vault Key content scrolls when it exceeds the available screen height so the source cards and the backup action stay reachable. The former global Backup Status destination has been removed.

Persisted status contains account IDs, source IDs, state, coarse error category, and timestamps only. It must never contain private keys, passphrases, encrypted payloads, Brave sync codes/authentication values, or NIP-42 challenges.

## Vault Key setup quorum

Setup attempts all enabled sources sequentially. It writes the Vault Key only after a successful source index proves that the canonical Vault Key ID is absent; an index failure remains unknown and is never treated as permission to push another entry. The Vault Key is retained in secure local storage even when remote quorum fails. Setup completes only when at least one quorum-eligible Nostr source and at least one quorum-eligible Brave Sync source already contain or successfully accept the Vault Key. The user can retry without reimporting the Vault Key. The in-app generation wizard enforces the same rule at its final step: it persists the create-flow backup outcome and refuses to complete setup until that outcome shows quorum, offering **Retry VaultKey backup** on the verification screen instead.

## Scheduling and interruption

- Associated-account creation saves locally first, then enqueues targeted consolidation for that account.
- Associated-account details offer **Consolidate backup**, not a direct retry-write action. The targeted cycle indexes every source and reconciles only the selected account: it does not restore, write, or update statuses for unrelated accounts. It writes the selected account only when its canonical ID is absent from a successful source index.
- The Vault Key screen offers **Consolidate VaultKey backup** after setup. It reconciles the Vault Key record only, because that record is published under a different Nostr identity than associated accounts. Immediate requests use an append-or-replace unique-work policy so account creation during an active targeted cycle queues a follow-up cycle instead of being discarded.
- There is no periodic scheduling. Because the VaultKey passphrase is never persisted, consolidation only runs while an in-memory session is unlocked: the passphrase gate enqueues one `VaultKeyConsolidationWorker` and one `AccountConsolidationWorker` after each successful unlock, and user actions enqueue targeted work.
- The workers read the passphrase from `VaultKeySession` at execution time and never place it in WorkManager input data. If the process was restarted with no unlocked session, they fail closed and the next unlocked launch re-enqueues them.
- Each cycle applies its own one-hour freshness threshold and its own pending-write check, so a stalled Vault Key backup is retried without waiting on account work. Manual actions bypass freshness.
- A persisted checkpoint identifies the active cycle, trigger, phase, source, and account without storing secrets.
- A later run is safe to repeat because presence is determined by canonical IDs.

## Source locations and tests

- Source model/defaults: `domain/BackupSources.kt`
- Source persistence: `domain/BackupSourceRepository.kt`
- Adapters: `domain/BackupSourceAdapters.kt`
- Orchestration: `domain/AccountConsolidationEngine.kt`
- Worker: `background/BackupWorkers.kt` (`AccountConsolidationWorker` handles targeted and all-account cycles)
- Status persistence: `data/kv/AppStorageService.kt`
- UI: `ui/accounts/AccountDetailScreen.kt`, `ui/Vault Key/VaultKeyDetailScreen.kt`
- Core tests: `domain/AccountConsolidationEngineTest.kt`, `domain/BackupSourceRepositoryTest.kt`, `data/kv/AppStorageServiceTest.kt`
