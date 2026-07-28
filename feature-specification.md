# ZenTen Vault — Feature Specification

This document provides a complete, implementation-agnostic specification of every feature in ZenTen Vault. It is intended as the primary reference for reimplementing the app in Kotlin (Android) or Swift (iOS).

## Table of Contents

1. [Feature Overview](#1-feature-overview)
2. [F-001: Master Account Onboarding](#f-001-master-account-onboarding)
3. [F-002: SSH Key Import](#f-002-ssh-key-import)
4. [F-003: BIP39 Mnemonic Derivation](#f-003-bip39-mnemonic-derivation)
5. [F-004: Nostr Key Derivation (NIP-06)](#f-004-nostr-key-derivation-nip-06)
6. [F-005: Brave Sync Code Derivation](#f-005-brave-sync-code-derivation)
7. [F-006: Sub-Account Creation](#f-006-sub-account-creation)
8. [F-007: SSH Key Generation (Sub-Accounts)](#f-007-ssh-key-generation-sub-accounts)
9. [F-008: Account Secure Storage](#f-008-account-secure-storage)
10. [F-009: Relay Backup](#f-009-relay-backup)
11. [F-010: Brave Sync Backup](#f-010-brave-sync-backup)
12. [F-011: Background Backup Jobs](#f-011-background-backup-jobs)
13. [F-012: Backup Sync (Two-Source Reconciliation)](#f-012-backup-sync-two-source-reconciliation)
14. [F-013: Account Recovery from Relays](#f-013-account-recovery-from-relays)
15. [F-014: Account Recovery from Brave Sync](#f-014-account-recovery-from-brave-sync)
16. [F-015: Locked Account Unlock](#f-015-locked-account-unlock)
17. [F-016: Backup Status Screen](#f-016-backup-status-screen)
18. [F-017: Account Management](#f-017-account-management)
19. [F-018: App Configuration](#f-018-app-configuration)
20. [F-019: Device Time Validation](#f-019-device-time-validation)
21. [F-020: Network Connectivity Handling](#f-020-network-connectivity-handling)
22. [F-021: Relay URL Configuration](#f-021-relay-url-configuration)

---

## 1. Feature Overview

| ID    | Feature                           | Priority | Platform |
| ----- | --------------------------------- | -------- | -------- |
| F-001 | Master Account Onboarding         | Critical | Both     |
| F-002 | SSH Key Import                    | Critical | Both     |
| F-003 | BIP39 Mnemonic Derivation         | Critical | Both     |
| F-004 | Nostr Key Derivation              | Critical | Both     |
| F-005 | Brave Sync Code Derivation        | Critical | Both     |
| F-006 | Sub-Account Creation              | Critical | Both     |
| F-007 | SSH Key Generation (Sub-Accounts) | Critical | Both     |
| F-008 | Account Secure Storage            | Critical | Both     |
| F-009 | Relay Backup                      | Critical | Both     |
| F-010 | Brave Sync Backup                 | High     | Both     |
| F-011 | Background Backup Jobs            | High     | Both     |
| F-012 | Backup Sync                       | High     | Both     |
| F-013 | Account Recovery from Relays      | Critical | Both     |
| F-014 | Account Recovery from Brave Sync  | High     | Both     |
| F-015 | Locked Account Unlock             | High     | Both     |
| F-016 | Backup Status Screen              | Medium   | Both     |
| F-017 | Account Management                | High     | Both     |
| F-018 | App Configuration                 | Medium   | Both     |
| F-019 | Device Time Validation            | Medium   | Both     |
| F-020 | Network Connectivity Handling     | Medium   | Both     |
| F-021 | Relay URL Configuration           | Medium   | Both     |

---

## F-001: Master Account Onboarding

### Description

The first-run experience that creates the master account. This is the root identity for the entire app. It can only be performed once per app installation.

### Preconditions

- App is freshly installed or reset
- `isSetupComplete()` returns false

### User Flow

1. User sees welcome screen
2. User provides their SSH private key (paste, QR scan, or file import)
3. User enters their passphrase
4. App validates the passphrase by decrypting the SSH key
5. App derives 24-word BIP39 mnemonic from SSH key + passphrase
6. App derives Nostr key pair from mnemonic (NIP-06)
7. App derives 25-word Brave Sync code from mnemonic
8. App publishes relay backup (mandatory — fatal if fails)
9. App initializes Brave Sync chain with the 25-word code
10. App publishes Brave Sync backup (non-fatal)
11. App looks for existing sub-accounts on Nostr relays and Brave Sync
12. If sub-accounts found → initiate recovery flow
13. App publishes kind 10002 + 10050 Nostr events (non-fatal)
14. App locks configuration
15. App marks setup complete
16. App navigates to accounts list

### Success Criteria

- Master account stored in secure storage
- Relay backup published to at least 1 relay
- `isSetupComplete()` returns true
- User lands on accounts list (or recovery flow if sub-accounts found)

### Failure Handling

- If relay backup fails: entire setup is rolled back
  - Master account deleted from secure storage
  - Setup complete flag NOT set
  - User shown error with retry option
- If passphrase is wrong: error shown, user can retry
- If SSH key is invalid: error shown inline

### Rollback Procedure

```
1. SecureStorageService.removeMasterAccount()
2. AppStorageService.clearSetupComplete()
3. Show error to user
```

### Implementation Notes

- Relay backup failure is the ONLY fatal failure in setup
- Brave Sync backup failure is non-fatal (background retry)
- Configuration is locked AFTER successful relay backup
- Kind 10002 and 10050 events are published after setup completes (non-fatal)
- Sub-account discovery runs after setup completes, before navigating to accounts list

---

## F-002: SSH Key Import

### Description

Accepts an existing SSH private key from the user. This is the entry point for master account creation — the user always provides their own key.

### Accepted Input Formats

1. **PEM format**: `-----BEGIN OPENSSH PRIVATE KEY-----\n<base64>\n-----END OPENSSH PRIVATE KEY-----`
2. **JSON format**: `{"OPENSSH PRIVATE KEY": "<base64>"}`
3. **Raw base64**: Single-line or multi-line base64 string
4. **QR code**: Any of the above formats scanned via camera

### Validation Steps

1. Extract base64 from input (strip PEM markers or parse JSON)
2. Decode base64 to bytes
3. Verify OpenSSH magic header: `openssh-key-v1\0` (15 bytes + null)
4. Verify key type is `ssh-ed25519`
5. Verify public key is 32 bytes

### Storage Format

Always stored as single-line base64 (no PEM markers, no whitespace).

### Error Messages

- "Invalid SSH key: invalid base64"
- "Invalid SSH key: truncated OpenSSH blob"
- "Invalid SSH key: not an OpenSSH v1 private key"
- "Only ed25519 keys supported"
- "Invalid ed25519 public key length"

---

## F-003: BIP39 Mnemonic Derivation

### Description

Derives a 24-word BIP39 mnemonic from an SSH private key and passphrase. This is the bridge between the SSH key world and the Nostr/Brave Sync world.

### Algorithm

Uses the seedify algorithm: `Seedify(pemKey, passphrase).toMnemonic(24)`

The algorithm:

1. Decrypts the SSH private key using the passphrase (bcrypt_pbkdf + AES-256-CTR)
2. Extracts the raw 32-byte Ed25519 private key seed
3. Uses the seed as entropy for BIP39 mnemonic generation

### Input

- `zenTenKey`: Single-line base64 SSH private key
- `zenTenPass`: Passphrase string

### Output

- 24-word BIP39 mnemonic (space-separated)

### Properties

- **Deterministic**: Same key + passphrase always produces same mnemonic
- **One-way**: Cannot derive SSH key from mnemonic
- **Passphrase-dependent**: Different passphrase → different mnemonic
- **Passphrase validation**: If decryption succeeds, the passphrase is correct

### Error Handling

- Wrong passphrase → decryption fails → throw error
- Invalid SSH key format → throw error

---

## F-004: Nostr Key Derivation (NIP-06)

### Description

Derives a secp256k1 Nostr key pair from the master account's BIP39 mnemonic using NIP-06. Only the master account has a Nostr key pair.

### Algorithm

NIP-06 standard derivation path: `m/44'/1237'/0'/0/0` (secp256k1)

### Input

- BIP39 mnemonic (24 words) — master account only

### Output

```
NostrKeyPair {
  pubkey:  string   // 64-char lowercase hex secp256k1 public key
  privkey: string   // 64-char lowercase hex secp256k1 private key
}
```

### Encoding

- `npub1...`: bech32 encoding of pubkey (for display/sharing)
- `nsec1...`: bech32 encoding of privkey (for export)

### Usage

| Account        | Nostr Key Usage                                     |
| -------------- | --------------------------------------------------- |
| Master account | Signs all backup events, publishes kind 10002/10050 |
| Sub-accounts   | No Nostr key pair                                   |

---

## F-005: Brave Sync Code Derivation

### Description

Derives the 25-word Brave Sync sync code from the master account's 24-word BIP39 mnemonic. This code is used to initialize and connect to the Brave Sync chain for backup.

### Algorithm

- Input: 24-word BIP39 mnemonic
- Output: 25-word Brave Sync code
- The 25th word is derived as a checksum/extension word from the 24-word mnemonic
- Deterministic: always recoverable from master SSH key + passphrase

### Properties

- **Deterministic**: Same mnemonic always produces same Brave Sync code
- **Recovery**: Brave Sync chain is always recoverable from master SSH key + passphrase
- **One-time derivation**: Derived once at setup, stored in master account

---

## F-006: Sub-Account Creation

### Description

Creates a new associated (sub) account under the master account. A sub-account is a freshly generated SSH key pair backed up to all remote sources. All sub-accounts are of the same type — there are no account type distinctions.

### Creation Steps

1. Record microsecond timestamp (`createdAt`) — this becomes the account ID and label
2. Generate new Ed25519 SSH key pair with the `createdAt` timestamp in the SSH key comment field
3. Save account to secure storage with label = `createdAt` timestamp string
4. Enqueue relay backup job (background, non-fatal)
5. Enqueue Brave Sync backup job (background, non-fatal)

### Sub-Account Properties

| Property        | Value                                                              |
| --------------- | ------------------------------------------------------------------ |
| `createdAt`     | Microsecond timestamp (account ID)                                 |
| `label`         | `createdAt` timestamp as string (auto-assigned, not user-editable) |
| SSH key comment | `createdAt` microsecond timestamp                                  |
| Nostr keys      | None                                                               |
| Passphrase      | None — sub-account SSH keys are not passphrase-protected           |

### Duplicate Prevention

- Accounts are identified by `createdAt` (microsecond timestamp)
- Registry prevents duplicate IDs

---

## F-007: SSH Key Generation (Sub-Accounts)

### Description

Generates a new Ed25519 SSH key pair for a sub-account. The microsecond timestamp is embedded in the SSH key comment field before generation.

### Algorithm

1. Record microsecond timestamp: `createdAt = currentTimeMicroseconds()`
2. Generate 32 random bytes as Ed25519 private key seed
3. Derive Ed25519 public key from seed
4. Encode as OpenSSH v1 format with comment = `createdAt` (as string)
   - Sub-account keys are **not** passphrase-encrypted (no KDF applied)
5. Encode as single-line base64 (no PEM markers for storage)

### Output

```
{
  privateKey: string   // Single-line base64 OpenSSH v1 private key (unencrypted)
  publicKey:  string   // "ssh-ed25519 <base64> <createdAt_timestamp>"
  createdAt:  number   // Microsecond timestamp used as comment and account ID
}
```

### Security Requirements

- Private key seed must use cryptographically secure random number generator
- The `createdAt` timestamp must be recorded before key generation begins
- Comment field must be set to the `createdAt` microsecond timestamp string

### Validation

- Generated key must be parseable and fingerprint-extractable
- Fingerprint format: `SHA256:<base64_without_trailing_equals>`

---

## F-008: Account Secure Storage

### Description

All account data is stored in platform-secure storage (iOS Keychain / Android Keystore).

### Storage Operations

#### Write

- `addMasterAccount(account)`: Stores master account
- `addAccount(account)`: Stores sub-account + updates registry
- `updateAccount(account)`: Updates existing sub-account
- `updateMasterAccount(account)`: Updates master account

#### Read

- `getMasterAccount()`: Returns master account or null
- `getAccounts()`: Returns all sub-accounts in registry order
- `getAccountById(createdAt)`: Returns specific account or null

#### Delete

- `removeMasterAccount()`: Deletes master account
- `removeAccount(createdAt)`: Deletes sub-account + removes from registry

#### Locked Accounts

- `upsertLockedAccount(lockedAccount)`: Add/update locked account
- `removeLockedAccount(createdAt)`: Remove locked account
- `getLockedAccounts()`: Returns all locked accounts

### Data Integrity

- Account registry is the source of truth for sub-account ordering
- Registry deduplication prevents duplicate entries
- Missing accounts (in registry but not in storage) are silently skipped

---

## F-009: Relay Backup

### Description

Backs up SSH private keys to Nostr relays using signed custom backup events.
Every event is authored and signed by the master Nostr identity and is NIP-44
self-encrypted to that same identity.

### Event Structure

The NIP-44 plaintext is **only** the single-line base64 OpenSSH private key.
It is not JSON and contains no duplicate metadata. `created_at` is the Nostr
publish-time Unix timestamp in seconds, distinct from an account's microsecond
`createdAt` value.

| Account | Kind | Tags | Recovery scope |
| --- | ---: | --- | --- |
| Master | `37801` | `[["d", "masterkey"]]` | Published during setup; never queried in this PoC |
| Sub-account | `37802` | `[["d", "<createdAt decimal microseconds>"]]` | Queried and restored |

There is no `t` tag.

### Encryption

- Algorithm: NIP-44 (ECDH + ChaCha20-Poly1305)
- Sender private key: master Nostr private key
- Recipient public key: master Nostr public key (self-encryption)
- Plaintext: the account's single-line base64 OpenSSH private key
- Only the master Nostr private key can decrypt

### Publishing

- Connects to the fixed PoC relay set.
- Publishes with quorum: minimum 1 relay must acknowledge.
- Up to 3 retry attempts per publish, with an 8-second attempt timeout.
- Master backup acknowledgement is mandatory during setup; failure rolls setup
  back. Sub-account failures are non-fatal and are persisted for background retry.

### Validation on Sub-Account Recovery

1. Event ID and signature must be valid.
2. Event kind must be `37802`.
3. Event author must be the master pubkey.
4. There must be one usable decimal `d` tag.
5. Content must decrypt successfully with the master key.
6. The decrypted plaintext must be a non-blank single-line base64 OpenSSH private key.
7. The account `createdAt` is reconstructed from `d.toLong()`.

Master events (`37801`) are never queried for recovery or sync in this PoC.

## F-010: Brave Sync Backup

### Description

Backs up SSH private keys to Brave Sync as password entries. Provides an independent backup source separate from Nostr relays.

### Brave Sync Code

- Derived from master account's 24-word BIP39 mnemonic → 25-word Brave Sync code
- Deterministic: always recoverable from master SSH key + passphrase

### Password Entry Format

| Field        | Value                                              |
| ------------ | -------------------------------------------------- |
| URL / Origin | `https://<backupIdentifier>.zenten`                |
| Username     | `<nostrPubkey>` (hex, master account pubkey)       |
| Password     | `<zenTenKey>` (single-line base64 SSH private key) |

### Backup Identifiers

| Account        | URL                          |
| -------------- | ---------------------------- |
| Master account | `https://masterKey.zenten`   |
| Sub-account    | `https://<createdAt>.zenten` |

### Client Caching

- One `BraveSyncClient` per account (keyed by `createdAt`)
- Clients are created lazily and reused
- `initializeChain()` can be called eagerly to pre-warm connection

### Failure Handling

- Brave Sync backup failure is non-fatal
- Failed account IDs persisted to `failed_brave_backup_account_ids`
- Retried on next app startup

---

## F-011: Background Backup Jobs

### Description

Manages asynchronous backup jobs that run after account creation.

### Job Types

1. **Relay backup**: Publishes a custom kind-`37802` sub-account event to Nostr relays
2. **Brave Sync backup**: Uploads SSH key to Brave Sync

### Job Lifecycle

```
Account Created
      │
      ▼
startBackupForAccount()
      │
      ├── Relay backup (async)
      │     ├── Success → status = "success"
      │     └── Failure → status = "failed", persist ID
      │
      └── Brave Sync backup (async)
            ├── Success → status = "success"
            └── Failure → status = "failed", persist ID
```

### Persistence

Failed job IDs are persisted to AsyncStorage:

- `failed_backup_account_ids`: relay backup failures
- `failed_brave_backup_account_ids`: Brave Sync backup failures

### Startup Retry

On app startup, `runStartupRetries()` re-attempts all persisted failures.

### Manual Retry

Users can manually retry failed backups from the account detail screen.

### Status Observable

UI subscribes to job status changes via `subscribe()` method.

---

## F-012: Backup Sync (Two-Source Reconciliation)

### Description

Periodically discovers sub-account backups from relays. Brave Sync reconciliation is deferred outside the PoC.

### Trigger Conditions

- App comes to foreground
- Manual refresh
- After account creation
- After master account setup completes (initial sub-account discovery)

### Guards

- **5-minute cooldown**: Will not run if last sync was < 5 minutes ago
- **Single-flight**: Only one sync runs at a time

### Algorithm

#### Phase 1: Snapshot and Fetch

```
Fetch kind-`37802` relay events authored by the master Nostr pubkey.
```

#### Phase 2: Discover Pending Restores

```
For each valid decrypted sub-account event:
  If its `d`-tag-derived ID is not local:
    Add it to pending restores.
```

#### Phase 3: Backfill Gaps

```
For each local account:
  Find relays missing this account's backup
  Re-publish backup to missing relays
```

#### Phase 4: Cache Refresh

```
Update BackupStatusCacheService with fresh catalogs
```

### Conflict Resolution

- Newest `updatedAt` wins when same account exists in multiple sources
- Local accounts are never deleted by sync (sync only adds)

### Result

```
BackupSyncResult {
  pendingRestores: PendingRestore[]
  backfilled:      number
  errors:          string[]
}
```

---

## F-013: Account Recovery from Relays

### Description

Recovers sub-accounts from Nostr relay backups. Triggered automatically after master account setup and manually on demand.

### Trigger

- Automatically after master account setup completes (initial discovery)
- Manual "Restore from relays" action

### Algorithm

1. Query all configured relays for kind `37802` events by master pubkey.
2. For each event:
   a. Validate its ID/signature, kind, master author, and usable decimal `d` tag.
   b. Decrypt content with the master Nostr key (NIP-44).
   c. Treat plaintext as the single-line base64 SSH private key.
   d. Reconstruct `createdAt` from the `d` tag and skip if it already exists locally.
   e. Import as a full sub-account (sub-accounts have no passphrase).
   f. If import fails, skip and report the event; locked-account handling is deferred.

### Full Account Import

1. Extract SSH private key from backup
2. Create `Account` object with `createdAt` from backup
3. Save to secure storage

### Import Failure Handling

If a candidate event cannot be validated, decrypted, or persisted, recovery skips it
and reports the failure. It does not delete local accounts or create a locked account;
locked-account handling is deferred outside the PoC.

### Progress Reporting

Progress callback fires for each processed account:

```
{ total, processed, restored, locked, skipped, message }
```

---

## F-014: Account Recovery from Brave Sync

### Description

Recovers sub-accounts from Brave Sync password entries.

### Algorithm

1. Connect to Brave Sync using master account's 25-word sync code
2. Read all password entries
3. For each entry:
   a. Parse `createdAt` from URL: `https://<createdAt>.zenten`
   b. Skip master account entry (`masterKey` in URL)
   c. Extract SSH private key from password field
   d. Import account (same logic as relay recovery)

### Integration with Backup Sync

Brave Sync recovery is integrated into the backup sync algorithm (Phase 2). Pending restores from Brave Sync appear in the same list as relay pending restores.

---

## F-015: Locked Account Unlock

### Description

Unlocks a locked account. Since sub-accounts have no passphrase, a locked account indicates a data integrity issue or a manually locked state. Unlocking attempts to re-import the account from backup.

### Preconditions

- Account exists in locked accounts storage

### Algorithm

1. Attempt to re-fetch account backup from relays or Brave Sync
2. If backup found and valid:
   - Create full `Account` object
   - Save to secure storage
   - Remove from locked accounts
3. If backup not found: show error, account remains locked

### UI

- Dialog with retry/cancel options
- Account moves from locked list to full accounts list on success

---

## F-016: Backup Status Screen

### Description

Shows the backup health of all accounts across all configured relays and Brave Sync.

### Data Sources

- `BackupStatusCacheService`: Cached catalog from last sync
- Refreshed on screen focus and manual refresh

### Health Computation

For each account:

- **healthy**: Present on ALL configured relays AND in Brave Sync
- **partial**: Present on SOME relays OR in Brave Sync (but not all)
- **missing**: Not found on any remote source
- **unknown**: No catalog data available

### Display

- Summary counts (healthy/partial/missing/unknown)
- Per-account cards with per-relay presence indicators
- Last refresh timestamp
- Manual refresh button

### Retry

Users can retry backup for accounts with `partial` or `missing` health.

---

## F-017: Account Management

### Description

Operations for accounts after initial creation.

### View Sub-Account

- SSH key fingerprint
- Public key
- `createdAt` timestamp (label)
- Backup status per source

The master account is a hidden signing and recovery identity. It is not included in
account lists, has no detail route, and its SSH/Nostr material is not displayed.

### Delete Account

1. Remove from secure storage
2. Remove chain address data from AsyncStorage
3. Clear backup job status

**Note**: Deleting an account does NOT delete its remote backups (relay events or Brave Sync entries). Remote backups remain until they expire or are manually deleted.

### Full Reset (App-Level Action)

1. Remove master account from secure storage
2. Remove all sub-accounts from secure storage
3. Clear accounts registry
4. Clear locked accounts
5. Clear setup complete flag
6. Clear app server config
7. Navigate to onboarding

---

## F-018: App Configuration

### Description

Manages app-level configuration (relay URLs, Brave Sync server URL).

### Configuration Fields

```
AppServerConfig {
  relays:             string[]   // WebSocket relay URLs
  braveSyncServerUrl: string     // Brave Sync server URL
  locked:             boolean    // Locked after setup
  lockedAt:           number?    // Timestamp when locked
}
```

### Default Configuration

```
relays: [
  "wss://relay.damus.io",
  "wss://relay.nostr.band",
  "wss://nos.lol",
  "wss://relay.snort.social",
  "wss://nostr.wine",
  "wss://relay.primal.net"
]
braveSyncServerUrl: "<configured brave sync server>"
locked: false
```

### Locking

- Configuration is locked after setup completes
- Locked configuration cannot be modified
- Prevents relay substitution attacks

### Relay URL Validation

- Must start with `wss://` or `ws://`
- Must be a valid URL
- Validated before saving

---

## F-019: Device Time Validation

### Description

Validates that the device clock is within an acceptable range. Required because Nostr events use Unix timestamps.

### Validation

- Checks device time against a reference (NTP or server time)
- If clock is off by more than a threshold: show `IncorrectTimeScreen`
- User must fix device time before using the app

### IncorrectTimeScreen

- Explains the problem
- Instructions to fix device time
- Retry button

---

## F-020: Network Connectivity Handling

### Description

Handles offline state gracefully.

### Behavior

- Network status monitored continuously
- If offline: show `OfflineScreen` for network-dependent operations
- Background sync deferred until online
- Local read operations work offline

### OfflineScreen

- Offline indicator
- Retry button (re-checks connectivity)

---

## F-021: Relay URL Configuration

### Description

Allows users to configure custom relay URLs before setup completes.

### Pre-Setup Configuration

- Accessible from welcome screen via settings button
- User can add/remove relay URLs
- Validated before saving
- Saved as draft config (unlocked)

### Post-Setup

- Configuration is locked
- Relay URLs cannot be changed
- Settings screen shows relay URLs as read-only

### Validation Rules

- URL must start with `wss://` or `ws://`
- URL must be parseable as a valid URL
- At least 1 relay required

---

## Appendix A: Cryptographic Algorithms Reference

| Algorithm    | Usage                      | Parameters                             | Android library (impl plan §2)     |
| ------------ | -------------------------- | -------------------------------------- | ---------------------------------- |
| Ed25519      | SSH key parsing/generation | 32-byte seed                           | BouncyCastle 1.79+                  |
| bcrypt_pbkdf | SSH key encryption KDF     | 1 round (PoC), 16-byte salt, 48-byte output | BouncyCastle bcrypt_pbkdf     |
| AES-256-CTR  | SSH key encryption         | 32-byte key, 16-byte IV                | JCE "AES/CTR/NoPadding"           |
| SHA-256      | SSH key fingerprint        | -                                      | JCE `MessageDigest`                |
| secp256k1    | Nostr keys (master only)   | NIP-06 derivation                      | `fr.acinq.secp256k1`               |
| BIP39        | Mnemonic generation        | 24 words (256-bit entropy)             | Vendored wordlist + BC HMAC-SHA512 |
| BIP32        | HD key derivation          | m/44'/1237'/0'/0/0 (Nostr)             | `fr.acinq.secp256k1` / BitcoinJ    |
| NIP-44       | Backup content encryption  | ECDH + ChaCha20-Poly1305               | BouncyCastle ChaCha20-Poly1305     |

> **Android note.** BouncyCastle (`org.bouncycastle:bcprov-jdk18on`, 1.79+) is the
> single primary crypto provider. Extracting the raw 32-byte Ed25519 seed from a
> passphrase-protected OpenSSH key (F-003) requires a custom decrypt path
> (bcrypt_pbkdf → AES-256-CTR → parse inner blob) on BC primitives, since
> libraries expose opaque key objects rather than the seed. See
> implementation-plan.md §2 and §9.

## Appendix B: Nostr Event Kinds Reference

| Kind  | Purpose                            | Author         |
| ----- | ---------------------------------- | -------------- |
| 10002 | Relay list metadata (NIP-65)       | Master account |
| 10050 | DM relay list (NIP-17)             | Master account |
| 37801 | Master SSH key backup (custom) | Master account |
| 37802 | Sub-account SSH key backup (custom) | Master account |

## Appendix C: Storage Keys Reference

### Secure Storage (iOS Keychain / Android Keystore)

| Key                    | Content                           |
| ---------------------- | --------------------------------- |
| `master_account`       | Master Account JSON               |
| `accounts_<createdAt>` | Sub-Account JSON                  |
| `accounts_registry`    | `[createdAt, ...]` JSON array     |
| `locked_accounts`      | `[LockedAccount, ...]` JSON array |

### AsyncStorage (kv-store)

| Key                               | Content                    |
| --------------------------------- | -------------------------- |
| `setup_complete`                  | `"true"`                   |
| `app_server_config`               | AppServerConfig JSON       |
| `backup_status_catalog_<pubkey>`  | BackupStatusCacheWire JSON |
| `failed_backup_account_ids`       | `[id, ...]` JSON array     |
| `failed_brave_backup_account_ids` | `[id, ...]` JSON array     |
