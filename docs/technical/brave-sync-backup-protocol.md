# Brave Sync and litesync backup protocol

## Purpose and scope

This document explains how ZenTen Vault writes account backups to Brave Sync-compatible servers, including the project's `litesync` endpoints. It covers the record format, credential derivation, transport, session lifecycle, write/verify flow, and failure handling at the implementation level.

User-visible backup behavior, source policy, and recovery semantics live in [Encrypted backup](../features/encrypted-backup.md) and [Backup status and consolidation](../features/backup-status-and-reconciliation.md). This document is the engineering walkthrough behind those guarantees.

## Terms used in this document

| Term | Meaning here |
| --- | --- |
| **Sync chain** | One server-side encrypted dataset, identified by a Sync code. All records in a chain share the same encryption keys. |
| **Sync code** | Brave's 25-word BIP-39-style recovery phrase. Vault derives it from the Vault Key seed. |
| **Nigori** | Chromium Sync's encryption layer and the special synced record that stores its key metadata (keybag, KDF method, salt). The server stores it but cannot decrypt it alone. |
| **Keybag** | The container inside Nigori holding the symmetric encryption key and MAC key, itself encrypted with a key derived from the Sync code. |
| **Key name** | A fingerprint of the active key set. Every encrypted record names the key that locked it, which allows key rotation. |
| **KDF** | Key derivation function. Turns a passphrase into key bytes. This implementation supports PBKDF2-SHA1 and scrypt. |
| **Salt** | Random per-chain input to the KDF, so the same passphrase yields different keys in different chains. |
| **PBKDF2 / scrypt** | Deliberately slow KDFs. Slowness makes offline guessing of the Sync code expensive. |
| **AES-CBC** | Symmetric block cipher mode used to encrypt record payloads. |
| **HMAC-SHA256** | Message authentication code appended to ciphertext to detect tampering. |
| **Protobuf** | Chromium Sync's binary wire format. The app encodes/decodes only the fields it needs. |
| **`GET_UPDATES` / `COMMIT`** | The two Sync protocol operations used: list records of one data type, and submit new records. |
| **Sync entity / record** | One protobuf item in the chain. Passwords are entities of data type `45873`; Nigori is `47745`. |
| **Client ID** | Ed25519 public key (hex) derived from the Sync code. Brave uses it to identify the client making a request. |
| **Auth token** | Bearer credential proving possession of the Client ID's private key for a timestamp. |
| **Quorum** | The minimum successful destinations required during Vault Key setup: one Nostr relay and one Brave Sync source. |
| **Consolidation** | The periodic/triggered reconciliation pass that indexes sources, restores missing local accounts, writes missing remote records, and verifies them. |
| **Canonical key form** | The single-line Base64 OpenSSH private-key representation stored in every backup. |

## Protocol flow

One endpoint's lifecycle for a single run: establish the session, learn the chain's keys, list what is already stored, then write only what the current index proved absent and confirm it by reading the chain back. The sections below explain each step in detail.

```mermaid
flowchart TD
    Start["Engine derives the Sync code once per run: 24 words plus the date-derived 25th word"] --> Clock{"Network validated and device clock within 2 min of HTTPS server time?"}
    Clock -->|No| Deferred["Fail before any network call; retry later without touching remote state"]
    Clock -->|Yes| Token["Derive Ed25519 keypair from the Sync code and mint a time-stamped Bearer token"]
    Token --> NigoriGet["GET_UPDATES for NIGORI_TYPE_ID (47745)"]
    NigoriGet --> NigoriPresent{"Chain has a Nigori record?"}
    NigoriPresent -->|No| NigoriInit["COMMIT a Nigori entity: PBKDF2 keybag, CUSTOM_PASSPHRASE, encrypt_everything"]
    NigoriPresent -->|Yes| Keys
    NigoriInit --> Keys["Derive AES encryption key and HMAC key from the chain's KDF method and salt"]
    Keys --> ListRecords["GET_UPDATES for PASSWORD_TYPE_ID (45873)"]
    ListRecords --> ListOK{"HTTP 2xx and server error code 0?"}
    ListOK -->|No| Unknown["Mark the source UNKNOWN: never infer absence and never write it this cycle"]
    ListOK -->|Yes| Decrypt["PasswordReader decrypts each entity"]
    Decrypt --> Valid{"MAC, decryption, and URL/username contract valid?"}
    Valid -->|No| Invalid["Counted as invalid; never treated as a backup"]
    Valid -->|Yes| Candidates["Group into RemoteAccountCandidate by BackupAccountId"]
    Candidates --> Absent{"Record absent in this cycle's index?"}
    Absent -->|No| Identical{"Existing record has the identical canonical key?"}
    Identical -->|Yes| Success["Idempotent success"]
    Identical -->|No| Conflict["Fail the source; never overwrite"]
    Absent -->|Yes| Canon["canonicalKey: normalize to single-line Base64 and validate the Ed25519 OpenSSH key"]
    Canon --> Build["PasswordEntityCodec builds the entity and its Nigori-encrypted payload"]
    Build --> Commit["COMMIT with a random commit ID"]
    Commit --> Entry{"response_type == 1 for the entry result?"}
    Entry -->|No| WriteFail["Source write fails; queue the record for retry"]
    Entry -->|Yes| Verify["Re-index the source and look for the record ID"]
    Verify --> Visible{"Record ID visible on read-back?"}
    Visible -->|No| WriteFail
    Visible -->|Yes| Success
    Success --> Quorum{"Vault Key pass only: one eligible Nostr relay and one eligible Brave source succeeded?"}
    Quorum -->|No| Incomplete["Keep the local Vault Key and leave setup incomplete so it can be retried"]
    Quorum -->|Yes| Complete["Setup completes; consolidation records the account as backed up"]
```

Session and key setup are done once per endpoint per run (`withSession`), while the list, write, and verify steps repeat for every record the run handles at that endpoint. The quorum check applies only to the Vault Key pass; account consolidation treats each source independently and reports per-source status.

## What is stored

Backups are stored as **password records**, the same entity type Brave's password manager syncs. One record exists per backup, and records carry no notes.

| Backup | URL | Username | Password field |
| --- | --- | --- | --- |
| Vault Key | `https://vaultkey.zenten/` | `vaultkey` | Canonical single-line Base64 OpenSSH private key |
| Associated account | `https://<createdAt>.zenten/` | decimal `createdAt` | Canonical single-line Base64 encrypted OpenSSH private key |

The password field holds the SSH key text, not JSON or an app-specific blob. The key remains passphrase-protected: the account passphrase is **never** uploaded. The Vault Key's own passphrase is also never stored remotely, which is why the Vault Key only re-enters the app through onboarding.

A record is recognized by URL **and** username matching its canonical backup ID (`vaultkey` or a decimal `createdAt`), with an empty notes list. Presence checks therefore do not decrypt or compare payloads; decryption and OpenSSH validation happen only when a record is restored.

Before any write, `canonicalKey` normalizes the value to the single-line Base64 form and parses it as an Ed25519 OpenSSH key. Malformed keys never reach the server.

## Credential derivation

Neither Brave nor litesync uses user accounts. Every credential is derived deterministically from the Vault Key's SSH seed.

```text
Vault Key SSH seed (32 bytes)
  └─ Vault Key backup sub-account seed = SHA256( SHA256("vkbckp") + seed )
       ├─ BIP-39 24 words + date-derived 25th word  → Sync code
       └─ same 24-word mnemonic → Nostr identity for Vault Key relay records

Sync code (first 24 words)
  ├─ BIP-39 data bits → 32-byte entropy
  │    └─ HKDF-SHA512(salt, info="sync-auth-key") → 32-byte Ed25519 private key
  │         ├─ Client ID = public key hex
  │         └─ Bearer token = Base64(timestamp | signature | public key)
  └─ Nigori keys
       ├─ PBKDF2-SHA1 iterations 1003 / 1004 (or scrypt if the chain says so)
       └─ → AES encryption key + HMAC key
```

Relevant code:

- Sync code and 25th word: `crypto/Seedify.kt`
- Auth token and Client ID: `bravesync/auth/BraveSyncAuth.kt`
- Nigori KDF and record encryption: `bravesync/crypto/NigoriCrypto.kt`

The engine derives the Sync code **once per run** in `DefaultBackupSourceFactory.createRunContext` and reuses it across every Brave endpoint. This avoids repeated SSH decryption and fixes the date-derived 25th word, so a run spanning midnight cannot address two different chains. The code is kept in memory only and is never persisted or logged.

In this implementation, both the auth keypair and the Nigori keys consume **only the first 24 words**. The 25th word is part of the generated Sync code format, but it is not an input to the HKDF or PBKDF2 derivations.

## Configured endpoints

Default Brave Sync sources from `domain/BackupSources.kt`:

| Label | Endpoint | Counts toward quorum |
| --- | --- | --- |
| `litesync1` | `https://litesync1.zentext.me/litesync` | Yes |
| `litesync2` | `https://litesync2.nostr.box/litesync` | Yes |
| `brave sync` | `https://sync-v2.brave.com/v2` | Yes |

Each source is an independent chain with its own transport, in-memory session, Nigori settings, and operation. `BravePasswordService` caches a session per endpoint for the duration of a run (`cacheSession = true`), so authentication and KDF work are not repeated per record within that endpoint.

## Transport

`bravesync/transport/BraveSyncTransport.kt` sends all protocol operations to a single configured server:

```text
POST {endpoint}/command/?client=Chromium&client_id=<URL-encoded Base64(32-byte public key)>
Authorization: Bearer <auth token>
BraveServiceKey: <Brave's public service key>
Content-Type: application/octet-stream
Body: protobuf ClientToServer message (COMMIT or GET_UPDATES)
```

Responses are protobuf and may be gzip-compressed. A non-2xx HTTP status or a non-zero server error code fails the source operation. The `BraveServiceKey` is Brave's public, open-source protocol constant, not a user credential.

## Session lifecycle

`BravePasswordService.withSession` performs the following once per endpoint per run:

1. Derive the Ed25519 keypair from the Sync code and mint a time-stamped Bearer token.
2. Send `GET_UPDATES` for `NIGORI_TYPE_ID` (`47745`).
3. Read the returned Nigori settings and derive the encryption/MAC keys using the chain's KDF method and salt.
4. If the chain has no Nigori record (a fresh server or chain), commit a Nigori entity via `NigoriInitializer`:
   - PBKDF2 keybag derived from the Sync code,
   - `CUSTOM_PASSPHRASE` type,
   - `encrypt_everything` enabled.

The server never receives the Sync code or the derived keys. It stores only the encrypted keybag and the KDF metadata.

## Index, write, and verify

### Index

`BraveBackupSource.index` calls `BraveSyncBackupService.listRecords`, which sends `GET_UPDATES` for `PASSWORD_TYPE_ID` (`45873`) and decrypts each returned entity with `PasswordReader`.

Entities that fail MAC verification, decryption, or the URL/username contract are counted as invalid and never treated as backups. Valid entities become `RemoteAccountCandidate` values grouped by `BackupAccountId`.

### Write

A write happens only when the same operation's successful index proved the record absent. The flow is:

1. Canonicalize and validate the OpenSSH key.
2. Build a Chromium password entity (`PasswordEntityCodec`):
   - realm/origin derived from the record URL,
   - username and password,
   - manually-added marker and timestamps,
   - client tag hash from `PasswordUniqueTag`,
   - Nigori-encrypted payload (`IV || AES-CBC ciphertext || HMAC-SHA256`).
3. Send `COMMIT` with a random commit ID.
4. Require `response_type == 1` for the entry result; anything else fails the source.

Engine-driven writes use `createKnownMissing`, avoiding a second `GET_UPDATES` because the current cycle already listed the source. Standalone callers keep an idempotency list-and-compare step: an existing identical record is success, and an existing record with a different key is a failure that is never overwritten.

The service also exposes `update` and `delete`, but the backup flow only ever creates records.

### Verify

The engine does not trust the commit acknowledgement alone. After writing, it re-indexes the source and requires the record ID to appear. An acknowledged-but-absent write is recorded as a failure and queued for retry.

## Orchestration and quorum

`AccountConsolidationEngine` serializes all remote work behind one coroutine mutex and processes sources sequentially.

- **Vault Key pass** (`backupVaultKey`): indexes and, where absent, writes the Vault Key record to every enabled source. Setup completes only when quorum is met — at least one quorum-eligible relay and one quorum-eligible Brave source. Each source is attempted even after earlier failures so the full quorum picture is available.
- **Full account consolidation** (`consolidate`): two phases. Phase 1 indexes all sources and restores locally missing accounts from trusted sources. Phase 2 writes every local account missing from each phase-1 index, then performs one read-back per source.
- **Single-account consolidation** (`consolidateAccount`): index, write if absent, verify immediately, without touching other accounts' statuses or records.

Work is scheduled by `background/BackupWorkers.kt`:

- separate hourly workers for the Vault Key and associated accounts,
- one-time targeted runs after account creation,
- manual consolidation that bypasses the freshness threshold,
- retries for pending `(account, source)` writes.

Both workers require a connected network and refuse to produce timestamped tokens or records while the device clock is known to be wrong.

## Failure semantics

| Situation | Behavior |
| --- | --- |
| Chain has no Nigori record | Commit a Nigori entity first, then write the password record |
| Source index fails | Mark the source `UNKNOWN`; never infer absence and never write that source in the cycle |
| Commit rejected / entry result not success | Source write fails and is queued for retry |
| Write acknowledged but not visible on read-back | Treated as failure and queued for retry |
| Existing record with a different payload | Fail the source; never overwrite |
| Record fails decryption | Counted as invalid and skipped |
| Device clock known incorrect | Worker retries without touching remote state |
| Sync code cannot be derived | Operation fails before any network call |

## Security properties

- The server stores ciphertext and KDF metadata only. Reading a backup requires the Sync code, which is derived from the Vault Key seed.
- The 24-word mnemonic provides the entropy behind the Sync code; the slow KDF raises the cost of offline guessing.
- Account passphrases and Vault Key passphrases are never uploaded.
- The Sync code, derived keys, auth tokens, and decrypted payloads stay in memory. Persisted state contains only IDs, statuses, timestamps, and coarse error categories.
- Each endpoint receives its own transport and session; credentials and sessions never cross server boundaries.

## Primary implementation locations

- Record contract and Sync code usage: `domain/BraveSyncBackupService.kt`
- Source adapter and run context: `domain/BackupSourceAdapters.kt`
- Orchestration, quorum, retries: `domain/AccountConsolidationEngine.kt`
- Session and record API: `bravesync/BravePasswordService.kt`
- Auth derivation: `bravesync/auth/BraveSyncAuth.kt`
- Nigori crypto and initialization: `bravesync/crypto/NigoriCrypto.kt`, `bravesync/NigoriInitializer.kt`
- Record encoding/decoding: `bravesync/password/PasswordEntityCodec.kt`, `PasswordReader.kt`, `PasswordUniqueTag.kt`
- Protocol messages: `bravesync/proto/SyncMessages.kt`, `ProtoWire.kt`
- HTTP transport: `bravesync/transport/BraveSyncTransport.kt`
- Workers: `background/BackupWorkers.kt`
