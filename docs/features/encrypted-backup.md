# Encrypted backup

## Purpose and scope

Vault writes the master and associated accounts independently to Nostr relay and Brave Sync sources. Remote formats remain compatible with the earlier implementation. Backup does not replace the user's obligation to retain the master private key and passphrase.

The default source list and consolidation behavior are documented in [Backup status and consolidation](backup-status-and-reconciliation.md).

## Local-first lifecycle

### Master setup

1. Setup validates and derives the master account.
2. `SetupService` stores it using `SecureStorageService`.
3. `AccountConsolidationEngine.backupMaster` checks and, where absent, writes every enabled source sequentially.
4. Setup completes only after one quorum-eligible Nostr relay and one quorum-eligible Brave Sync server contain or acknowledge the master.
5. If quorum fails, the master remains securely stored, setup remains incomplete, and retry does not require reimport.

### Associated-account creation

1. `AccountService` generates a passphrase-protected Ed25519 OpenSSH key and allocates its `createdAt` identifier.
2. `SetupService.addAccount` persists the account locally before any remote operation.
3. WorkManager schedules source-by-source backup.
4. Failed writes remain pending by account ID and source ID; the local account remains usable.

## Canonical identifiers

- Master: `masterkey`
- Associated account: decimal `createdAt`

Presence is determined by identifier only. Consolidation does not decrypt an already-present payload to compare it with local material. Payload decryption and OpenSSH validation happen only when a missing local associated account must be restored.

## Nostr format

| Backup | Kind | `d` tag | Plaintext before NIP-44 |
| --- | ---: | --- | --- |
| Master | `37801` | `masterkey` | normalized master OpenSSH private key |
| Associated | `37802` | decimal `createdAt` | normalized encrypted OpenSSH private key |

The event author/signature identity is derived from the master account. The plaintext is a canonical single-line Base64 OpenSSH private-key value. `BackupService` NIP-44 self-encrypts it, then `NostrEventCodec` signs the event.

### NIP-42 lifecycle

`NostrRelayService` creates an authenticated one-shot Quartz connection for source reads and writes:

1. Install `RelayAuthenticator` before connecting.
2. When the relay sends an `AUTH` challenge, build and sign kind `22242` with the master-derived Nostr key.
3. Send the response on the same relay connection; challenges and signed auth events are not persisted.
4. Keep the connection alive while waiting for AUTH acknowledgement and retry the requested publication on that authenticated connection when necessary.
5. Require the target relay's `OK` acknowledgement before reporting a confirmed write.

A relay that does not request NIP-42 proceeds normally. Timeout, rejection, or incomplete authentication is a source failure and cannot satisfy quorum. The two default relay endpoints are `wss://appdata1.zentext.me` and `wss://appdata2.nostr.box`.

## Brave Sync format

The sync code is derived from the parsed master seed by `Seedify.toBraveSyncCode`. Each independently configured server receives its own transport and operation.

| Backup | URL | Username | Password |
| --- | --- | --- | --- |
| Master | `https://masterkey.zenten/` | `masterkey` | normalized master private key |
| Associated | `https://<createdAt>.zenten/` | decimal `createdAt` | normalized encrypted associated private key |

Records have no notes. Before writing, the service parses the key as Ed25519 OpenSSH. An identical matching record is success; a conflicting matching record fails and is not overwritten.

## Retry and consolidation

Account backup, pending retry, and two-phase consolidation are implemented by `AccountConsolidationEngine` and `background/BackupWorkers.kt`. Work is constrained to a connected network, all remote operations are serialized, and periodic consolidation runs hourly.

## Secret handling

Secure storage contains account private keys and passphrases. Non-secret DataStore state may contain canonical account IDs, source IDs, status/error categories, timestamps, pending-write counters, and checkpoints. It must not contain private keys, passphrases, payloads, sync codes, authentication tokens, or NIP-42 challenges.

Errors, logs, analytics, WorkManager input, and UI status must follow the same restriction.

## Failure semantics

| Situation | Behavior |
| --- | --- |
| Master quorum unavailable | Retain local master, leave setup incomplete, allow retry. |
| Associated write fails everywhere | Preserve usable local account and pending source writes. |
| Initial source index fails | Mark `UNKNOWN`; do not infer absence or write that source in the cycle. |
| Existing Brave record conflicts | Fail that source; do not overwrite. |
| NIP-42/relay acknowledgement fails | Fail that relay and do not count it toward quorum. |
| Remote restore payload is invalid | Try newer/older duplicate candidates as applicable, then skip without altering local accounts. |

## Key locations

- Engine: `domain/AccountConsolidationEngine.kt`
- Source adapters: `domain/BackupSourceAdapters.kt`
- Nostr event construction: `domain/BackupService.kt`
- Relay transport/authentication: `network/nostr/NostrRelayService.kt`
- Brave records: `domain/BraveSyncBackupService.kt`
- Workers: `background/BackupWorkers.kt`
