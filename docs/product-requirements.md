# ZenTen Vault — Product Requirements

## 1. Setup and master identity

### 1.1 Entry rules

- On a new or reset installation, show onboarding.
- When setup is marked complete, load the master identity and show the accounts area.
- If setup is marked complete but the master identity cannot be read, treat the state as corrupted: do not expose account operations; return to onboarding after clearly explaining that local setup must be repaired or restarted.
- A completed vault must not expose onboarding as a normal route.

### 1.2 Master-key intake

The user must be able to provide a private key through paste. File import and QR scanning should also be available when platform permission and security policies permit.

Accepted representations:

- PEM-wrapped `OPENSSH PRIVATE KEY` text;
- a JSON object containing an `OPENSSH PRIVATE KEY` value;
- raw Base64, either line-wrapped or single-line; and
- any of the above encoded in a QR payload.

Normalize accepted input to a single-line Base64 value. Reject malformed Base64, truncated data, a non-OpenSSH-v1 container, any key type other than `ssh-ed25519`, or an invalid 32-byte Ed25519 public key. Errors must state the category without echoing the supplied key.

### 1.3 Passphrase and derivation

- Require a non-empty passphrase field.
- Validate it by decrypting the supplied SSH private key. A decryption failure is a wrong-passphrase error, unless the input itself is invalid.
- From the decrypted 32-byte Ed25519 private seed, derive a 24-word BIP39 mnemonic. The same normalized key and same passphrase must always yield the same mnemonic.
- Derive the master Nostr key pair from that mnemonic using NIP-06 at `m/44'/1237'/0'/0/0`.
- Derive the 25-word Brave Sync code from the same mnemonic.
- Do not present the mnemonic, master Nostr secret, or Brave Sync code in routine UI. If a product-approved recovery/export surface is later added, it requires equivalent sensitive-data safeguards.

### 1.4 Setup transaction

Setup must behave as a transaction:

1. validate and normalize the key;
2. validate the passphrase and derive the recovery identity;
3. save the master identity in platform-secure storage;
4. publish the encrypted master relay backup and receive acknowledgement from at least one configured relay;
5. initialize Brave Sync and attempt its master backup;
6. lock backup configuration;
7. mark setup complete;
8. discover remote sub-accounts and offer restoration; and
9. publish relay-list metadata records where supported; failures here are non-fatal.

Only step 4 is a fatal remote-backup gate. If it fails, remove the saved master identity, leave setup incomplete, preserve editable pre-setup configuration, and provide retry. Brave Sync failure must not undo a successful setup; it must be visible and queued for retry.

## 2. Sub-accounts

### 2.1 Creation

Creating a sub-account requires an explicit confirmation. The app must:

1. allocate a unique microsecond `createdAt` ID before key generation;
2. generate a cryptographically random Ed25519 SSH key pair;
3. use the decimal `createdAt` value as the OpenSSH key comment;
4. store the private key as a single-line Base64 OpenSSH-v1 blob and retain the standard public-key string;
5. use the same ID as its immutable label; and
6. save it locally before starting remote backup work.

Generated sub-account private keys are not passphrase-protected. They have no mnemonic, Nostr keys, or separate backup identity. The master Nostr identity owns all remote backup signing and encryption.

An account ID must be collision resistant even when multiple accounts are requested in one millisecond. Implementations may construct it from epoch milliseconds plus a random three-digit suffix; externally it remains a decimal microsecond-style canonical identifier.

### 2.2 View, reveal, export, and delete

The account list and account detail show only sub-accounts. For each, show label/ID, fingerprint, public key, creation time, and backup status.

- Copy actions must show confirmation feedback without placing secrets in notifications.
- Showing, copying, or sharing a private key requires a confirmation immediately before disclosure.
- Exported private-key text may be PEM-wrapped for user compatibility, although stored and backed-up form remains the single-line Base64 blob.
- A sensitive-data view should auto-hide on timeout/backgrounding and prevent screenshots where platform capabilities permit.
- Deleting a sub-account removes it from local secure storage, local registries, local retry state, and cached chain data. It does **not** delete existing Nostr or Brave Sync backups. The confirmation copy must say this plainly.

### 2.3 Full reset

A reset requires strong confirmation and removes all local master, sub-account, locked-account, registry, configuration, cache, and job state, then returns to onboarding. It does not guarantee deletion of any remote record.

## 3. Remote backup behavior

### 3.1 General rules

- All remote backups represent the normalized single-line Base64 OpenSSH private key, not PEM and not a metadata JSON envelope.
- The master Nostr key signs every Nostr backup.
- Nostr backup content is NIP-44 self-encrypted: master private key as sender and master public key as recipient.
- Relay publishing succeeds when at least one configured relay acknowledges it. Retry up to three attempts per publish with an approximately eight-second attempt limit.
- Sub-account backup failures are non-fatal. Persist failed IDs per source and retry on later startup, connectivity restoration, background scheduling, and user action.

### 3.2 Nostr relay backups

| Record | Kind | Required `d` tag | Plaintext after NIP-44 decryption |
| --- | ---: | --- | --- |
| Master backup | `37801` | `masterkey` | normalized master SSH private key |
| Sub-account backup | `37802` | decimal sub-account `createdAt` | normalized sub-account SSH private key |

`created_at` is the Nostr event publication time in Unix seconds; it is not an account ID. No `t` tag is used. A master event is published for disaster-recovery backup but is not queried by the normal sub-account restore flow.

### 3.3 Brave Sync backups

Create a password entry for each account:

| Account | URL/origin | Username | Password |
| --- | --- | --- | --- |
| Master | `https://masterKey.zenten` | master Nostr public key in hex | normalized master key |
| Sub-account | `https://<createdAt>.zenten` | master Nostr public key in hex | normalized sub-account key |

The master-derived 25-word sync code initializes or reconnects the chain. Sync-client connections may be cached, but caching may not alter data meaning. A failure is retryable and must be reflected in status.

## 4. Discovery, restore, sync, and repair

### 4.1 Triggers and limits

Run discovery after successful onboarding, when the app returns to foreground, after account creation, on manual refresh, and after network becomes available. Enforce a five-minute minimum automatic-sync cooldown and single-flight execution. Manual refresh may state that a sync is in progress rather than create a second one.

### 4.2 Relay candidate validation

For each candidate sub-account event, require all of the following before it can be offered for restore:

- valid event ID and signature;
- kind `37802`;
- author equals the derived master public key;
- exactly one usable decimal `d` tag;
- successful NIP-44 decryption with the master identity; and
- non-blank, valid normalized Base64 OpenSSH private-key payload.

Use the `d` tag as the recovered account ID. Invalid candidates are skipped and reported in aggregate; they do not alter local accounts.

### 4.3 Brave Sync discovery

Read password records through the derived sync chain. Recognize `https://<createdAt>.zenten`, ignore `https://masterKey.zenten` during sub-account discovery, validate the ID and private-key content, and merge valid candidates with relay discoveries.

### 4.4 Restore and reconciliation rules

- Remote candidates absent locally become pending restores; do not silently import them unless the user explicitly opts into automatic restoration in a future approved setting.
- Restoring persists a full local sub-account and removes its pending item.
- If a candidate cannot become a usable local account, report it and retain enough locked-account state to allow a later re-fetch. Never overwrite a usable local account with an invalid remote candidate.
- When equivalent records disagree, newest `updatedAt` information wins for remote catalog interpretation.
- Sync only adds local accounts or republishes missing remote backups. It never deletes a local account.
- For each local account, attempt to repair gaps by re-publishing to relays known not to contain it and retrying missing Brave Sync backup.

### 4.5 Backup health

For every account, calculate:

- **Healthy:** confirmed on every configured relay and in Brave Sync.
- **Partial:** confirmed on at least one, but not all, required destinations.
- **Missing:** confirmed on no remote destination.
- **Unknown:** no sufficiently recent catalog information exists.

Show source-by-source presence, last refresh time, and a retry action for partial or missing accounts. Unknown must not be displayed as failure.

## 5. Configuration, time, and network

### 5.1 Backup destination configuration

Before setup completes, allow the user to add and remove relay URLs. Require at least one URL and validate that each is parseable and uses `wss://` or `ws://`. Display the configured Brave Sync server as part of the same destination configuration.

After the mandatory master relay backup succeeds, persist a locked configuration with the time of locking. Relay URLs and sync-server destination become read-only. The UI may display them, but it must not offer mutation.

### 5.2 Device time

Before operations that sign or publish Nostr events, verify the device clock against a trustworthy reference. If drift exceeds the approved product threshold, block network-dependent vault actions with an explanation, steps to correct device time, and retry. Local non-network reads remain available.

### 5.3 Connectivity

Continuously observe connectivity. Local viewing remains available offline. Network-dependent actions must clearly say they are deferred or failed because the device is offline, queue retryable work safely, and resume eligible work when online.

## 6. Account and job state

A backup job/source status transitions through `idle`, `running`, `success`, or `failed`. Status changes must be observable by UI. Failed relay and Brave Sync IDs survive process restarts. The product must avoid duplicate concurrent jobs for the same account/source.
