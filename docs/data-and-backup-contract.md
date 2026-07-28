# ZenTen Vault — Data and Backup Contract

This document defines portable product data semantics and remote interoperability. It deliberately describes records and validation rather than a database schema or SDK API.

## 1. Canonical representations

| Item | Canonical representation |
| --- | --- |
| Stored/remote SSH private key | Single-line Base64 encoding of the complete OpenSSH-v1 private-key blob; no PEM markers or whitespace. |
| Exported SSH private key | The canonical value may be wrapped as PEM (`BEGIN/END OPENSSH PRIVATE KEY`) for user export. |
| SSH public key | Standard `ssh-ed25519 <base64-public-key> <createdAt>` text for generated sub-accounts. |
| Sub-account ID | Decimal textual representation of `createdAt`; preserve exact digits rather than locale-formatting. |
| Master Nostr keys | Lowercase 64-character hexadecimal private and public secp256k1 values internally; `npub`/`nsec` are display/export encodings only. |
| Mnemonic | 24 space-separated BIP39 words. |
| Brave Sync code | 25 space-separated words. |
| Nostr event timestamp | Unix epoch seconds. |

Never use a formatted date, a fingerprint, public key, or remote event timestamp as an account's primary identity.

## 2. Logical local records

### 2.1 Master identity

Securely retain enough information to operate and recover the vault:

| Field | Meaning |
| --- | --- |
| `zenTenKey` | Canonical master SSH private key. |
| `zenTenPass` | Master SSH-key passphrase; secret. |
| `mnemonic` | Derived 24-word mnemonic; secret and re-derivable. |
| `nostr.pubkey`, `nostr.privkey` | Derived master Nostr identity; secret private component. |
| `braveSyncCode` | Derived 25-word sync code; secret and re-derivable. |

The mnemonic, Nostr pair, and sync code are convenience caches, not independent sources of truth. An implementation that detects disagreement must rederive from the master key and passphrase and treat inconsistent cached secrets as corruption.

### 2.2 Full sub-account

| Field | Meaning |
| --- | --- |
| `createdAt` | Canonical unique microsecond account ID. |
| `label` | Exact decimal `createdAt` text; immutable. |
| `zenTenKey` | Canonical generated OpenSSH private-key value. |
| `pubkey` | Generated SSH public-key text. |
| backup/job metadata | Local observational state, retry state, and timestamps; not identity material. |

### 2.3 Locked account and pending restore

A pending restore records only safe discovery information (ID, source(s), discovery/update time, and validation state) until the user requests import. A locked account records the ID and enough protected backup/recovery context to retry acquisition; it must not be presented as a normally usable account. Neither changes the canonical definition of a full sub-account.

## 3. Nostr interoperability

### 3.1 Event envelope

Nostr records use ordinary signed Nostr event semantics. The event author is always the master Nostr public key. The payload is encrypted using NIP-44 v2 with the master identity encrypting to itself.

```json
{
  "kind": 37802,
  "pubkey": "<64-character master public key hex>",
  "created_at": 1730000000,
  "tags": [["d", "1730000000123456"]],
  "content": "<NIP-44 v2 ciphertext of canonical SSH private-key string>",
  "id": "<signed Nostr event id>",
  "sig": "<Nostr signature>"
}
```

### 3.2 Event types

| Account | Event kind | `d` tag exact value | Normal recovery query? |
| --- | ---: | --- | --- |
| Master | `37801` | `masterkey` | No; retained as a backup record. |
| Sub-account | `37802` | Decimal `createdAt` | Yes. |

There is no `t` tag. Do not put labels, passphrases, mnemonic words, public keys, account JSON, or source metadata in the encrypted plaintext. The entire plaintext is exactly the canonical SSH private-key string.

### 3.3 Publishing and query semantics

- Publish to all configured relays where practical; one acknowledgement is the minimum success quorum.
- Attempt each publish at most three times and bound each attempt at about eight seconds.
- On recovery, query kind `37802` authored by the current master public key.
- Verify event ID/signature before trusting tags or content.
- Reject malformed, duplicate-ambiguous, non-decimal, or unusable `d` tags.
- A valid event with an already-local account ID is not a new restore; it may contribute to health and repair analysis.

### 3.4 Optional relay metadata

The master identity may publish kind `10002` (relay-list metadata) and kind `10050` (DM relay list) after setup. These records are useful metadata, not prerequisites for a completed vault and not substitutes for custom backup events.

## 4. Brave Sync interoperability

Brave Sync stores password entries using the following fixed contract:

| Field | Master | Sub-account |
| --- | --- | --- |
| URL/origin | `https://masterKey.zenten` | `https://<createdAt>.zenten` |
| Username | Master Nostr public key hex | Master Nostr public key hex |
| Password | Canonical master key | Canonical sub-account key |

Only `masterKey` with that exact capitalization denotes the master record. A restore parser must reject other nonnumeric hosts, malformed URLs, empty passwords, and IDs that cannot be represented as canonical account identifiers.

Brave Sync is independent of Nostr: a record present in one does not prove presence in the other. Implementations may reuse a connection per account or vault, but those mechanics must not change the record format.

## 5. Catalog and reconciliation model

For each refresh, form a catalog whose conceptual shape is:

```text
Account ID → {
  locallyPresent,
  relayPresence: set of configured relay URLs,
  braveSyncPresent,
  latestRemoteUpdatedAt,
  candidateSource(s),
  validationErrors
}
```

Catalog cache is non-secret observational data, keyed to the master public key and invalidated after meaningful configuration or account changes. It should retain last-refresh time so the UI can distinguish stale/unknown from confirmed absence.

Reconciliation rules:

1. Validate each remote candidate before adding it to the catalog.
2. Merge candidates by account ID; retain provenance from every valid source.
3. A remote-only ID becomes pending restore.
4. A local-only account is eligible for repair publication.
5. A local account is never removed because a remote source lacks it.
6. When same-account remote metadata conflicts, use newest `updatedAt` for catalog interpretation, while retaining validation/provenance diagnostics.

## 6. Compatibility and migration rules

- Preserve existing canonical IDs exactly through storage, UI, events, URLs, and exports.
- Do not reinterpret a master `37801` record as a sub-account, even if its payload resembles one.
- Do not add application JSON around the Nostr ciphertext plaintext; doing so breaks compatible recovery.
- New local fields may be added if absent fields have safe defaults and do not alter the existing remote contract.
- Before changing event kinds, tags, identifier spelling/case, encryption version, derivation path, or Brave Sync URL conventions, provide a versioned migration and cross-client compatibility test suite.
