# Encrypted backup

## Purpose and scope

This document specifies the current backup write path for the master account and associated accounts. Backup has two independent targets:

- **Nostr relays** — encrypted and signed Nostr events; and
- **Brave Sync** — password records reached using a sync code derived from the master account.

The account private-key payload is normalized before it is sent to either target. Only passphrase-protected OpenSSH private keys are accepted; unencrypted keys are rejected. Backup is not a substitute for retaining the master account private key and passphrase.

## Write lifecycle

### Master account during setup

1. Setup validates and derives the master account.
2. `SetupService` persists the master account in secure storage.
3. `AccountBackupCoordinator.backupMaster` writes to every configured target.
4. Setup completes only if all configured target writes report success.
5. On any failure, `SetupService` removes the stored master account and clears the setup-complete flag.

At present the coordinator is configured for Nostr and Brave Sync. A Nostr write succeeds only after at least one configured relay acknowledges the event. A Brave Sync write succeeds only after its record operation succeeds.

### Associated account after creation

1. `AccountService` allocates a unique `createdAt` identifier and generates an Ed25519 OpenSSH key pair encrypted with the master account passphrase (`aes256-ctr` with `bcrypt_pbkdf`).
2. `SetupService.addAccount` persists the associated account in secure storage **before** remote work begins.
3. `AccountBackupCoordinator.backupSubAccount` attempts every target.
4. If one or more targets fail, `AppStorageService` records the account ID and only the failed target IDs in `pendingAccountBackups`.
5. `RelayBackupRetryScheduler` requests background retry work.

A failed remote write must never remove a successfully created local associated account.

## Target-neutral contract

`AccountBackupCoordinator` calls each `AccountBackupTarget` once per operation and returns a `BackupReport` containing failed target IDs. Target IDs must be unique. The current IDs are:

```text
NOSTR
BRAVE_SYNC
```

The pending queue is target-aware. A later retry writes only destinations that failed previously, rather than duplicating successful writes unnecessarily.

## Nostr target

### Event contract

| Backup             | Nostr kind | `d` tag             | Plaintext before encryption                       |
| ------------------ | ---------: | ------------------- | ------------------------------------------------- |
| Master account     |    `37801` | `masterkey`         | normalized master-account OpenSSH private key     |
| Associated account |    `37802` | decimal `createdAt` | normalized passphrase-encrypted associated-account OpenSSH private key |

`created_at` on the Nostr event is the publication time in seconds; it is not an associated-account identifier. The event has one `d` tag and is signed by the Nostr identity derived from the master account.

### Protection and publish policy

- The exact plaintext is the single-line Base64 OpenSSH private-key value; it is not PEM and is not wrapped in metadata JSON.
- `BackupService` uses NIP-44 self-encryption with the master account's derived Nostr private/public key pair.
- `NostrEventCodec.sign` signs the encrypted event with the same derived Nostr identity.
- The relay transport rejects invalid events before publishing.
- `NostrRelayService.publish` tries up to three times, with an eight-second attempt timeout and a short delay between attempts.
- A publish is acknowledged when at least one configured relay confirms it. The result retains successful and failed relay URLs for observability.

### Idempotency

The account ID and kind are stable, but event publication time and encryption make an event write non-byte-identical across attempts. Reconciliation determines backup presence by the canonical `d` tag, not by a previous event ID.

## Brave Sync target

### Record contract

Brave Sync stores a password record with no notes:

| Backup             | URL                           | Username            | Password                                  |
| ------------------ | ----------------------------- | ------------------- | ----------------------------------------- |
| Master account     | `https://masterkey.zenten/`   | `masterkey`         | normalized master-account private key     |
| Associated account | `https://<createdAt>.zenten/` | decimal `createdAt` | normalized passphrase-encrypted associated-account private key |

The sync code is derived from the parsed master account seed by `Seedify.toBraveSyncCode`.

### Write and duplicate policy

Before a write, the service normalizes and parses the private key to require a valid Ed25519 OpenSSH key. It reads existing records for the derived sync code:

- If no matching URL/username record exists, it creates one.
- If a matching record contains the identical canonical key and no notes, the operation is treated as successful.
- If it contains a different value or notes, the operation fails rather than overwriting a potentially conflicting record.

## Retry behavior

`RelayBackupWorker` runs as unique WorkManager work with a connected-network constraint and exponential backoff starting at ten seconds. `RelayBackupRetryService`:

1. migrates legacy relay-only queue entries to the target-aware queue;
2. removes queue entries whose local account no longer exists;
3. retries only each pending target;
4. clears targets that succeed; and
5. asks WorkManager to retry while attempted work remains unsatisfied.

Periodic reconciliation can also repair confirmed backup gaps; see [Backup status and reconciliation](backup-status-and-reconciliation.md).

## Persistence and secret handling

- Account records, including private keys and the passphrase retained for their OpenSSH encryption, are stored in `SecureStorageService` using `EncryptedSharedPreferences` and an Android Keystore-backed `MasterKey`.
- Associated-account OpenSSH private keys are encrypted with the master account passphrase before local or remote storage; Nostr additionally encrypts the complete key payload with NIP-44.
- Retry queues and backup-status cache are non-secret DataStore preferences. They store account IDs, target IDs, status, and timestamps—not private-key payloads.
- Do not add private-key material, passphrases, derived sync codes, or encrypted event plaintext to logs, analytics, work input data, errors, or status storage.

## Failure semantics

| Situation                                     | Required behavior                                                    |
| --------------------------------------------- | -------------------------------------------------------------------- |
| Master backup target fails during setup       | Roll back local master setup and leave setup incomplete.             |
| Associated-account target fails               | Preserve local account, record failed target(s), and schedule retry. |
| Existing Brave record conflicts               | Fail that target; do not overwrite it.                               |
| Relay has no acknowledgement after attempts   | Fail Nostr target; do not claim a confirmed relay backup.            |
| Remote payload cannot be normalized/validated | Fail the operation; never publish malformed private-key material.    |

## Key source locations and tests

- Target coordination: `app/src/main/java/app/zenten/vault/domain/AccountBackupCoordinator.kt`
- Nostr event construction: `app/src/main/java/app/zenten/vault/domain/BackupService.kt`
- Nostr target adapter: `app/src/main/java/app/zenten/vault/domain/NostrAccountBackupTarget.kt`
- Relay transport: `app/src/main/java/app/zenten/vault/network/nostr/NostrRelayService.kt`
- Brave target: `app/src/main/java/app/zenten/vault/domain/BraveSyncBackupService.kt`
- Retry service/worker: `app/src/main/java/app/zenten/vault/domain/RelayBackupRetryService.kt`, `background/RelayBackupWorker.kt`
- Relevant tests: `domain/BackupRecoveryServiceTest.kt`, `domain/RelayBackupRetryServiceTest.kt`, `network/nostr/NostrEventCodecTest.kt`, and Brave Sync tests under `app/src/test/java/app/zenten/vault/bravesync/`.
