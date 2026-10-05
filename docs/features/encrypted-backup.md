# Encrypted backup

## Purpose and scope

Vault writes the Vault Key and associated accounts independently to Nostr relay and Brave Sync sources. Remote formats remain compatible with the earlier implementation. Backup does not replace the user's obligation to retain the Vault Key private key and passphrase.

The default source list and consolidation behavior are documented in [Backup status and consolidation](backup-status-and-reconciliation.md).

## Local-first lifecycle

### Vault Key setup

1. Setup validates and derives the Vault Key.
2. `SetupService` stores it using `SecureStorageService`.
3. `AccountConsolidationEngine.backupVaultKey` checks and, where absent, writes every enabled source sequentially.
4. Setup completes only after one quorum-eligible Nostr relay and one quorum-eligible Brave Sync server contain or acknowledge the Vault Key.
5. If quorum fails, the Vault Key remains securely stored, setup remains incomplete, and retry does not require reimport.

### Associated-account creation

1. `AccountService` generates a passphrase-protected Ed25519 OpenSSH key and allocates its `createdAt` identifier.
2. `SetupService.addAccount` persists the account locally before any remote operation.
3. WorkManager schedules source-by-source backup.
4. Failed writes remain pending by account ID and source ID; the local account remains usable.

## Canonical identifiers

- Vault Key: `vaultkey`
- Associated account: decimal `createdAt`

Presence is determined by identifier only. Consolidation does not decrypt an already-present payload to compare it with local material. Payload decryption and OpenSSH validation happen only when a missing local associated account must be restored.

## Nostr format

| Backup | Kind | `d` tag | Plaintext before NIP-44 |
| --- | ---: | --- | --- |
| Vault Key | `37801` | `vaultkey` | normalized Vault Key OpenSSH private key |
| Associated | `37802` | decimal `createdAt` | normalized encrypted OpenSSH private key |

Normally, the event author/signature identity is derived from the original Vault Key seed. The initial remote backup performed specifically by the **Create VaultKey** wizard is the exception: it derives a NIP-06 identity from the 24-word mnemonic of Seedify's deterministic `brave` associated account and uses that identity for relay indexing, NIP-44 self-encryption, event signing, publication, and NIP-42 authentication. The backed-up plaintext is still the original VaultKey's canonical single-line Base64 OpenSSH private key. Imported-Vault Key setup, later retries, associated-account backup, and consolidation continue to use the stored original-seed Vault Key Nostr identity; later consolidation may consequently add a duplicate relay Vault Key backup under that identity.

### NIP-42 lifecycle

`NostrRelayService` creates an authenticated one-shot Quartz connection for source reads and writes:

1. Install `RelayAuthenticator` before connecting.
2. When the relay sends an `AUTH` challenge, build and sign kind `22242` with the Nostr key selected for that operation (the Brave sub-account identity only during initial in-app creation; otherwise the stored Vault Key identity).
3. Send the response on the same relay connection; challenges and signed auth events are not persisted.
4. Pin the challenged relay in Quartz's connection pool for the full operation timeout while waiting for a definitive AUTH acknowledgement. `fetchAll` removes its subscription after `CLOSED`, so an authenticated read maintains a temporary keep-alive subscription until the handshake and retry finish. If the initial subscription is closed with `auth-required`, wait for the successful AUTH `OK` and issue a fresh `REQ` on that same authenticated connection; the closed subscription cannot be reused.
5. Retry a requested publication on the authenticated connection when necessary and observe publication acknowledgements for the entire connection lifetime so an `OK` produced by Quartz's post-authentication outbox resynchronization is not lost between retries.
6. Require the target relay's successful `OK` acknowledgement before reporting a confirmed write. An exact-ID `event already exists` response is also accepted as idempotent confirmation because it proves that relay already stores the same signed event; other duplicate or blocked responses remain failures.

A relay that does not request NIP-42 proceeds normally. Only an explicit negative AUTH `OK` is an authentication rejection; a missing AUTH acknowledgement is a timeout. Authentication rejection, network failure, timeout, and event rejection remain distinct source error categories and cannot satisfy quorum. The two default relay endpoints are `wss://appdata1.zentext.me` and `wss://appdata2.nostr.box`.

NIP-42 auth events use the device wall clock and relays may reject events outside their freshness window. Persistent authentication rejection on one device therefore requires checking that Android automatic date and time are enabled; extending a network timeout cannot correct clock skew.

## Brave Sync format

The sync code is derived from the parsed Vault Key seed by `Seedify.toBraveSyncCode`. Consolidation derives it once per in-memory run, fixing the date-derived word for that run, and reuses it across Brave endpoints without persisting or logging it. Each independently configured server still receives its own transport, cached in-memory session, Nigori settings, and operation. Because the engine writes only after a successful source index proves a record absent, that write reuses the indexed result instead of listing records again; the later consolidation read-back remains the authoritative verification.

| Backup | URL | Username | Password |
| --- | --- | --- | --- |
| Vault Key | `https://vaultkey.zenten/` | `vaultkey` | normalized Vault Key private key |
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
| Vault Key quorum unavailable | Retain local Vault Key, leave setup incomplete, allow retry. |
| Associated write fails everywhere | Preserve usable local account and pending source writes. |
| Initial source index fails | Mark `UNKNOWN`; do not infer absence or write that source in the cycle. |
| Existing Brave record conflicts | Fail that source; do not overwrite. |
| NIP-42 authentication fails | Report authentication failure; fail that relay and do not count it toward quorum. |
| Relay connection, timeout, or event rejection | Report the corresponding network, timeout, or remote-rejection category rather than authentication failure. |
| Relay reports the exact event ID already exists | Treat the retry as an idempotently confirmed write. |
| Remote restore payload is invalid | Try newer/older duplicate candidates as applicable, then skip without altering local accounts. |

## Key locations

- Engine: `domain/AccountConsolidationEngine.kt`
- Source adapters: `domain/BackupSourceAdapters.kt`
- Nostr event construction: `domain/BackupService.kt`
- Relay transport/authentication: `network/nostr/NostrRelayService.kt`
- Brave records: `domain/BraveSyncBackupService.kt`
- Workers: `background/BackupWorkers.kt`
