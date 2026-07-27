# ZenTen Vault — Architecture Overview

## Table of Contents

1. [Project Purpose](#1-project-purpose)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Technology Stack](#3-technology-stack)
4. [Core Cryptographic Foundation](#4-core-cryptographic-foundation)
5. [Account Model](#5-account-model)
6. [Storage Architecture](#6-storage-architecture)
7. [Backup Architecture](#7-backup-architecture)
8. [Networking Architecture](#8-networking-architecture)
9. [State Management Architecture](#9-state-management-architecture)
10. [Background Job Architecture](#10-background-job-architecture)
11. [Security Model](#11-security-model)
12. [Key Invariants](#12-key-invariants)

---

## 1. Project Purpose

ZenTen Vault is a key-management application. Its primary function is to:

- **Accept an existing Ed25519 SSH key pair** provided by the user at app start
- **Derive a 24-word BIP39 mnemonic** from the SSH key + passphrase
- **Generate a 25-word Brave Sync code** derived from the 24-word mnemonic
- **Derive a Nostr key pair** (secp256k1) from the mnemonic via NIP-06
- **Back up SSH keys redundantly** to Nostr relays (NIP-78 events) and Brave Sync
- **Manage associated (sub) accounts** — each a fresh SSH key pair backed up to all sources

The app is designed so that a user's cryptographic identity is always recoverable from their SSH key + passphrase, even if the device is lost.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ZenTen Vault App                         │
│                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐   │
│  │  UI Layer    │   │ Context/State│   │  Service Layer   │   │
│  │  (Screens &  │◄──│  (App State) │◄──│  (Business       │   │
│  │  Components) │   │              │   │   Logic)         │   │
│  └──────────────┘   └──────────────┘   └──────────────────┘   │
│                                                │                │
│                              ┌─────────────────┼──────────┐    │
│                              ▼                 ▼          ▼    │
│                    ┌──────────────┐  ┌──────────────┐         │
│                    │ Secure Store │  │  AsyncStorage│         │
│                    │(iOS Keychain/│  │  (kv-store)  │         │
│                    │ Android KS)  │  │              │         │
│                    └──────────────┘  └──────────────┘         │
└─────────────────────────────────────────────────────────────────┘
         │                    │
         ▼                    ▼
  ┌─────────────┐    ┌──────────────┐
  │Nostr Relays │    │  Brave Sync  │
  │(NIP-78      │    │  Server      │
  │  Backup)    │    │  (Backup)    │
  └─────────────┘    └──────────────┘
```

---

## 3. Technology Stack

### Runtime & Framework

| Component | Technology                       |
| --------- | -------------------------------- |
| Language  | Platform-native (Kotlin / Swift) |
| UI        | Jetpack Compose / SwiftUI        |

### Cryptography Libraries

| Library            | Purpose                                                | Android impl (see implementation-plan.md §2) |
| ------------------ | ------------------------------------------------------ | -------------------------------------------- |
| Ed25519            | SSH key parsing and signing                            | BouncyCastle 1.79+ (`Ed25519` primitives)    |
| AES-256-CTR        | SSH private key encryption/decryption                  | JCE `Cipher` "AES/CTR/NoPadding"             |
| bcrypt_pbkdf       | bcrypt_pbkdf key derivation (OpenSSH KDF)              | BouncyCastle bcrypt_pbkdf primitive          |
| BIP39              | 24-word mnemonic generation/validation                 | Vendored wordlist + BC HMAC-SHA512           |
| BIP32              | HD key derivation                                      | `fr.acinq.secp256k1` / BitcoinJ              |
| secp256k1 / NIP-06 | Nostr key pair derivation from mnemonic                | `fr.acinq.secp256k1` (bundled JNI)           |
| NIP-44             | ECDH + ChaCha20-Poly1305 encryption for backup content | BouncyCastle ChaCha20-Poly1305 + HKDF        |

> **Android note.** BouncyCastle (`org.bouncycastle:bcprov-jdk18on`, 1.79+) is the
> primary provider — it covers Ed25519, bcrypt_pbkdf, AES-CTR, ChaCha20-Poly1305,
> and OpenSSH-v1 parsing in one dependency and works down to minSdk 26. The
> JDK-native `EdDSA` provider is **not** used because Ed25519 only ships in the
> Android platform provider on API 33+. Extracting the raw 32-byte Ed25519 seed
> from a passphrase-protected OpenSSH key (needed to seed BIP39) uses a custom
> decrypt path on BC primitives — see implementation-plan.md §2.

### Storage

| Store                   | Purpose                                                     |
| ----------------------- | ----------------------------------------------------------- |
| Secure Store            | Encrypted key-value store (iOS Keychain / Android Keystore) |
| AsyncStorage / kv-store | Non-sensitive key-value store                               |

### Networking & Protocols

| Library / Protocol | Purpose                                   |
| ------------------ | ----------------------------------------- |
| Nostr (WebSocket)  | Nostr relay connections (NIP-78 backup)   |
| Brave Sync         | Brave Sync client (password entry backup) |

---

## 4. Core Cryptographic Foundation

The entire key hierarchy is deterministic and derives from a single Ed25519 SSH key pair provided by the user.

### Key Derivation Chain

```
User-provided SSH Private Key + Passphrase
         │
         ▼ (validate passphrase by decrypting SSH key)
Ed25519 Key Pair (OpenSSH v1 format)
   ├── Private Key: encrypted with bcrypt_pbkdf + AES-256-CTR
   └── Public Key: ssh-ed25519 format
         │
         ▼ (seedify: SSH key + passphrase → BIP39)
BIP39 24-word Mnemonic (256-bit entropy)
         │
         ├──▶ NIP-06 Nostr Key Pair (master account only)
         │      ├── privkey: hex (secp256k1)
         │      └── pubkey: hex (secp256k1)
         │
         └──▶ 25-word Brave Sync Code
                (derived from 24-word mnemonic + 1 checksum/extension word)
```

### SSH Key Encryption (OpenSSH v1)

```
passphrase + random 16-byte salt
         │
         ▼ bcrypt_pbkdf (1 round — PoC; production uses more)
48 bytes of key material
  ├── [0:32]  → AES-256 key
  └── [32:48] → AES-CTR IV
         │
         ▼ AES-256-CTR
Encrypted private key block
  (contains: check_int×2, key_type, pubkey, privkey+pubkey, comment, padding)
```

### NIP-44 Encryption (Backup Content)

Backup event content is encrypted using NIP-44 (ECDH + ChaCha20-Poly1305):

- **Sender**: master account's Nostr private key
- **Recipient**: master account's Nostr public key (self-encryption)
- This means only the holder of the master Nostr private key can decrypt backups

### Brave Sync Code Derivation

The 25-word Brave Sync sync code is derived from the master account's 24-word BIP39 mnemonic:

- The 24 mnemonic words plus 1 derived extension word form the 25-word Brave Sync code
- This makes Brave Sync recovery deterministic from the master SSH key + passphrase

---

## 5. Account Model

### Master Account

The master account is created once at first app launch. It is the root identity for the entire vault.

| Property          | Value                                            |
| ----------------- | ------------------------------------------------ |
| Storage key       | `master_account`                                 |
| SSH key source    | Provided by the user at onboarding               |
| Nostr key pair    | Derived from mnemonic via NIP-06                 |
| Backup identifier | `"masterKey"`                                    |
| Backup signing    | Signs its own backup and all sub-account backups |
| Relay events      | Publishes kind 10002 + 10050 after setup         |
| Creation flow     | `SetupService.performSetup()`                    |
| Backup failure    | Fatal (setup rolls back)                         |

### Associated (Sub) Accounts

Associated accounts are additional SSH key pairs created under the master account. They have no account type — all are identical in structure.

| Property          | Value                                                       |
| ----------------- | ----------------------------------------------------------- |
| Storage key       | `accounts_<createdAt>`                                      |
| SSH key source    | Freshly generated at creation time                          |
| SSH key comment   | Microsecond timestamp (set in the comment field at key gen) |
| Nostr key pair    | Not derived — sub-accounts have no Nostr identity           |
| Label             | `createdAt` microsecond timestamp (auto-assigned)           |
| Backup identifier | `"<createdAt>"` (microsecond timestamp string)              |
| Backup signing    | Master account signs all sub-account backups                |
| Creation flow     | `SetupService.addAccount()`                                 |
| Backup failure    | Non-fatal (background retry)                                |

### Account States

1. **Full Account**: SSH key stored, all operations possible
2. **Locked Account**: Restored from backup but passphrase unknown. Only `zenTenKey`, `pubkey`, `label`, `createdAt` stored.
3. **Pending Restore**: Discovered in remote backup but not yet imported locally

### Account Identity

Account IDs are microsecond-precision Unix timestamps with a 3-digit random suffix:

```
accountId = timestamp_ms * 1000 + random_3_digits
```

This ensures uniqueness even for accounts created within the same millisecond.

---

## 6. Storage Architecture

### Secure Storage (iOS Keychain / Android Keystore)

Stores sensitive account data.

| Key                    | Value                  | Description                     |
| ---------------------- | ---------------------- | ------------------------------- |
| `master_account`       | JSON `Account`         | Master account (full)           |
| `accounts_<createdAt>` | JSON `Account`         | Sub-account by ID               |
| `accounts_registry`    | JSON `number[]`        | Ordered list of sub-account IDs |
| `locked_accounts`      | JSON `LockedAccount[]` | Locked accounts array           |

### Non-Sensitive Storage (kv-store / AsyncStorage)

| Key                               | Value                        | Description                               |
| --------------------------------- | ---------------------------- | ----------------------------------------- |
| `setup_complete`                  | `"true"`                     | Setup completion flag                     |
| `app_server_config`               | JSON `AppServerConfig`       | Relay URLs + Brave Sync URL               |
| `backup_status_catalog_<pubkey>`  | JSON `BackupStatusCacheWire` | Cached backup catalog                     |
| `failed_backup_account_ids`       | JSON `number[]`              | IDs of accounts with failed relay backups |
| `failed_brave_backup_account_ids` | JSON `number[]`              | IDs with failed Brave Sync backups        |

---

## 7. Backup Architecture

### Two-Source Remote Backup Model

Every account is backed up to two independent remote sources:

```
Account SSH Key
      │
      ├──▶ [1] Nostr Relays (NIP-78 kind 30078 events)
      │         Signed by master Nostr key
      │         Content: NIP-44 encrypted SSH private key
      │
      └──▶ [2] Brave Sync
                Password entry: URL=https://<createdAt>.zenten, password=SSH key
                Sync code derived from master mnemonic (25-word Brave Sync code)
```

### Relay Backup Event Structure (NIP-78)

```json
{
  "kind": 30078,
  "pubkey": "<master_nostr_pubkey>",
  "tags": [
    ["d", "<createdAt_or_masterKey>"],
    ["t", "ssh-key-backup"]
  ],
  "content": "<NIP-44 encrypted JSON>",
  "created_at": <unix_timestamp>
}
```

Encrypted content:

```json
{
  "dTag": "<createdAt_or_masterKey>",
  "createdAt": <account_createdAt>,
  "encryptedContent": "<base64_ssh_private_key>"
}
```

### Backup Sync Algorithm

The `BackupSyncService` runs periodically (5-minute cooldown) to reconcile two remote sources:

**Phase 1**: Snapshot local accounts + fetch remote catalogs (relays + Brave Sync) in parallel  
**Phase 2**: Discover pending restores (remote accounts not present locally)  
**Phase 3**: Backfill gaps (local accounts missing from some relays)  
**Phase 4**: Refresh backup status cache

### Backup Status Health

Each account's backup health is computed as:

- `healthy`: Present on all configured relays + Brave Sync
- `partial`: Present on some but not all sources
- `missing`: Not found on any remote source
- `unknown`: Status not yet determined

---

## 8. Networking Architecture

### Nostr Relay Service

`NostrRelayService` manages WebSocket connections to Nostr relays.

**Publish quorum**: Minimum 1 relay must acknowledge. Up to 3 retry attempts, 8-second timeout per attempt.

**Event types published**:
| Kind | Purpose |
|------|---------|
| 30078 | NIP-78 SSH key backup (application-specific data) |
| 10002 | Relay list metadata (master account) |
| 10050 | DM relay list (master account) |

---

## 9. State Management Architecture

### App State Hierarchy

```
DeviceTimeProvider          // Device clock validation
  NetworkProvider           // Network connectivity
  ToastProvider             // Toast notifications
  BackupStatusProvider      // Backup catalog cache
    KeysProvider            // Main app state (accounts, jobs)
      App Screens
```

### KeysProvider Responsibilities

The `KeysProvider` is the central state manager. It:

1. **Initializes app on mount**: Loads master account → loads sub-accounts → runs background sync → starts background jobs
2. **Manages account CRUD**: Create, update, remove accounts
3. **Manages background jobs**: Backup jobs
4. **Manages backup sync**: Runs periodic sync, exposes pending restores
5. **Handles app state changes**: Re-validates setup when app comes to foreground

### App Initialization Sequence

```
App Mount
    │
    ▼
isSetupComplete() ?
    │
    ├── No ──▶ Navigate to /get-started
    │
    └── Yes ──▶ getMasterAccount()
                    │
                    ▼
                getAccounts() + getLockedAccounts()
                    │
                    ▼
                hydrateBackgroundJobs()
                    │
                    ▼
                runStartupRetries() (failed backup jobs)
                    │
                    ▼
                runBackgroundSync()
```

---

## 10. Background Job Architecture

### Job Types

| Job               | Trigger          | Retry                  |
| ----------------- | ---------------- | ---------------------- |
| Relay backup      | Account creation | Yes (startup + manual) |
| Brave Sync backup | Account creation | Yes (startup + manual) |

### Job Status States

**Backup job** (`BackupJobStatus`):

- `idle` → `running` → `success` / `failed`

**Per-source backup** (`SourceBackupStatus`):

- `idle` → `running` → `success` / `failed`

### Persistence

Failed job IDs are persisted to AsyncStorage:

- `failed_backup_account_ids`: relay backup failures
- `failed_brave_backup_account_ids`: Brave Sync backup failures

On app startup, `runStartupRetries()` re-attempts all persisted failures.

---

## 11. Security Model

### Threat Model

| Threat              | Mitigation                                                         |
| ------------------- | ------------------------------------------------------------------ |
| Device theft        | SSH keys encrypted at rest (bcrypt_pbkdf + AES-256-CTR)            |
| Backup interception | NIP-44 encryption (ECDH, only master key can decrypt)              |
| Relay compromise    | Multiple relays (quorum publish), Brave Sync as independent backup |
| Account loss        | Two-source remote backup (relays + Brave Sync)                     |

### Key Isolation

- Master account Nostr private key is used to sign and encrypt all backups
- SSH private keys are stored encrypted; mnemonic is derived on-demand
- Nostr private keys are stored in plaintext within the encrypted secure store blob

### App Configuration Locking

After setup completes, `AppServerConfig` is locked:

- Relay URLs cannot be changed after first setup
- `locked: true` flag prevents modification
- Prevents relay substitution attacks

---

## 12. Key Invariants

These invariants must be maintained in any reimplementation:

1. **Master account SSH key is user-provided** — The master account is never generated by the app; the user brings their own SSH key.

2. **Passphrase validation is the gate** — The passphrase is validated by attempting to decrypt the SSH key. If decryption succeeds, the passphrase is correct.

3. **Relay backup failure during setup is fatal** — If the master account's relay backup fails, the entire setup is rolled back (account deleted from secure storage).

4. **Account ID is the canonical identity** — `createdAt` (microsecond timestamp) is the primary key used across all storage, routing, and backup systems.

5. **Backup identifier for master = `"masterKey"`** — The NIP-78 `d` tag and Brave Sync URL for the master account use the literal string `"masterKey"`, not the createdAt timestamp.

6. **NIP-44 self-encryption** — Backup content is encrypted with master's private key to master's public key (sender = recipient = master).

7. **Mnemonic is derived, not stored independently** — The mnemonic is always derived from `zenTenKey + zenTenPass` via the seedify algorithm. It is stored in the Account blob for convenience but must always be re-derivable.

8. **Locked accounts have no mnemonic** — A locked account only stores the SSH key; the mnemonic cannot be derived without the passphrase.

9. **Sub-account backups are signed by master** — The master Nostr key signs all NIP-78 backup events, including sub-account backups.

10. **Brave Sync code = 25 words derived from 24-word mnemonic** — The Brave Sync sync code is derived deterministically from the master account's BIP39 mnemonic.

11. **Sub-account SSH key comment = createdAt timestamp** — When generating a sub-account SSH key, the microsecond timestamp is written into the SSH key comment field before generation.

12. **Sub-account label = createdAt timestamp** — The label for all associated accounts is automatically set to the microsecond creation timestamp.

13. **5-minute sync cooldown** — Background sync must not run more frequently than once every 5 minutes to avoid relay spam.
