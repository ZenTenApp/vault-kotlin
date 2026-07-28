# ZenTen Vault — Security, Privacy, and Quality Requirements

## 1. Security objectives

ZenTen Vault protects user-held SSH credentials at rest, in transit to backup destinations, and in ordinary product interaction. It must preserve deterministic recovery while minimizing unnecessary disclosure.

### Required cryptographic behavior

| Purpose | Required behavior |
| --- | --- |
| Master SSH key validation | Decrypt the passphrase-protected OpenSSH-v1 Ed25519 key; successful decryption validates the passphrase. |
| Mnemonic source | Use the decrypted raw 32-byte Ed25519 private seed as entropy for a 24-word BIP39 mnemonic. |
| Master Nostr identity | Derive with NIP-06 path `m/44'/1237'/0'/0/0`. |
| Sub-account generation | Generate a fresh Ed25519 seed using the operating system's cryptographically secure random generator. |
| SSH key fingerprint | SHA-256 format `SHA256:<Base64 digest without trailing =>`. |
| Relay confidentiality | Encrypt canonical key plaintext with NIP-44 v2 self-encryption using the master Nostr identity. |
| Relay authenticity | Sign every backup event with the master Nostr private key and validate signatures on recovery. |

The product relies on the OpenSSH key's existing encryption parameters for the master key. Implementations must preserve compatibility with supported OpenSSH-v1 Ed25519 encrypted keys and must never silently weaken or replace a user-supplied master key's passphrase protection.

## 2. Trust boundaries and controls

| Boundary / threat | Required control |
| --- | --- |
| Lost or stolen device | Store secrets only in platform secure storage protected by OS-backed encryption/key management; do not keep duplicate plaintext files. |
| Compromised relay | NIP-44 encryption plus signature verification; use multiple configured relays. |
| One backup provider unavailable | Maintain independent Nostr and Brave Sync backup paths and persistent retry. |
| Malicious remote event | Verify ID, signature, author, kind, tag, and decrypted payload before discovery/import. |
| Relay-substitution attempt | Lock relay and sync-server destinations after successful master relay backup. |
| UI shoulder surfing | Require deliberate reveal, protect sensitive screens, and automatically hide where possible. |
| Logs, telemetry, and crash reporting | Redact or prohibit all secrets and full private-key material. |
| Clipboard and sharing | Copy/share only after user action; warn that external destinations are outside vault control. |

Use secure transport for all remote connections. Relay endpoints must be validated as WebSocket URLs. Sync-server transport must meet the provider's secure transport requirements.

## 3. Secret handling rules

The following are secrets: master and sub-account private keys, master SSH passphrase, mnemonic, Nostr private key, Brave Sync code, decrypted backup payloads, and any serialized record containing them.

Implementations must:

- keep secrets out of ordinary app preferences, unencrypted databases, URL parameters, logs, exception messages, analytics, and test fixtures committed to source control;
- clear temporary in-memory buffers/references as promptly as practical for the platform;
- avoid displaying secrets in background task names, system notifications, accessibility announcements, or screenshots;
- disable or reduce screen capture on sensitive views where the platform permits;
- prevent automatic backup of secure-store material through platform cloud/device backup mechanisms unless that behavior is explicitly secured and approved; and
- ensure diagnostic exports include only redacted identifiers and source status.

Biometric or device-auth gates may add protection to opening the vault or revealing a key, but do not change deterministic recovery semantics.

## 4. Reliability and operational requirements

### Background work

- Persist failed backup IDs separately for relay and Brave Sync sources.
- Retry on app startup, scheduled background opportunity, reconnection, and explicit user request.
- Make jobs idempotent: retrying must not create a different account identity or corrupt local registry state.
- Avoid concurrent duplicate execution for the same account/source.
- Surface meaningful status even when the operating system delays background execution.

### Time and connectivity

- Validate clock before signing/publishing; block network operations when clock drift breaches the approved threshold.
- Do not treat a lack of connectivity as data loss. Preserve local data and retry intent.
- Enforce one active sync and a five-minute automatic-sync cooldown to avoid relay spam.

### Data integrity

- The ordered local sub-account registry is the source of truth for display order.
- Deduplicate IDs; silently skip a registry entry whose protected record is unavailable, while recording enough diagnostics for support.
- Never mark setup complete before required master relay acknowledgement.
- Never delete local accounts as a side effect of sync or a failed remote fetch.

## 5. Testable release acceptance scenarios

Every supported client should automate these scenarios against controlled relay and sync test environments where possible.

1. **Deterministic identity:** the same supported master key and passphrase produce the same mnemonic, Nostr public/private keys, and Brave Sync code on independent clients.
2. **Incorrect passphrase:** wrong passphrase is rejected; no completed setup or usable derived identity is created.
3. **Fatal setup backup:** when all relay acknowledgements fail, master local state is rolled back and onboarding remains incomplete.
4. **Nonfatal second source:** when Brave Sync fails but relay master backup succeeds, onboarding completes and Brave retry is queued/status-visible.
5. **Sub-account contract:** generated account uses unique canonical ID in label and SSH comment; its remote Nostr event has kind `37802`, decimal `d` tag, valid signature, and decrypts to only the canonical SSH value.
6. **Full recovery:** onboard, create accounts, ensure backup, erase local vault, onboard with the same master material, discover and restore all sub-accounts.
7. **Hostile event rejection:** wrong author/signature/kind/tag, invalid ciphertext, and invalid SSH payload never create or overwrite an account.
8. **Two-source status:** health correctly distinguishes healthy, partial, missing, and unknown across configured relays and Brave Sync.
9. **Deletion honesty:** local delete removes local record but a later remote discovery can offer restoration; UI makes this consequence clear.
10. **Reset:** reset clears local secrets, setup flag, registry, cached catalogs, locked state, and retries, but does not claim remote deletion.
11. **Offline:** local list/detail works offline; backup work is deferred and retried after connectivity returns.
12. **Accessibility and privacy:** onboarding, reveal confirmation, restore, retry, and reset are usable with assistive technology; no secret appears in logs, notifications, accessibility announcements, or standard screenshots.

## 6. Cross-platform compatibility gate

Before a client is released, verify byte-for-byte/canonical parity with approved shared vectors for:

- master-key normalization and passphrase validation;
- seed extraction and 24-word mnemonic;
- NIP-06 key derivation and NIP-19 encodings;
- NIP-44 v2 encrypt/decrypt behavior;
- generated OpenSSH key encoding, comment, public key, and fingerprint;
- Nostr event serialization/signature/validation; and
- Brave Sync entry parsing and emission.

A platform may use any approved library or implementation strategy, but it must produce and consume the same product-contract data.
