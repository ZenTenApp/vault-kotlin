# Security model

> This document records current security intent and review scope. It is not a security audit or a claim that the proof of concept is safe for production credentials.

## Protected assets

- User-provided Vault Key private key and its passphrase.
- Derived recovery material and vault-key Nostr identity.
- Generated associated-account SSH private keys, encrypted in their OpenSSH containers with the Vault Key passphrase.
- Brave Sync recovery/access material.
- Remote backup payloads and local backup-status metadata.

## Trust boundaries

| Boundary | Expected protection | Review focus |
| --- | --- | --- |
| User input and UI | Private material is not revealed by default. | Screenshots, accessibility, clipboard, backgrounding, error text. |
| Device storage | Secrets are stored through Android secure-storage facilities. | Keystore behavior, backup policy, migration, corruption, device compromise. |
| Crypto/derivation | Deterministic behavior must remain compatible. | Input normalization, library versions, vectors, zeroization limits. |
| Network and relays | Payloads are protected before transmission. | Authentication/signatures, encryption, relay trust, replay/malformed records. |
| Brave Sync integration | Records and transport protect remote data. | Protocol compatibility, authentication, server trust, retry/error behavior. |
| Logs and operations | Secrets must not leave through diagnostics. | Crash reporting, analytics, build logs, support workflows. |
| KeyHive provider | Trusted sibling apps may read associated SSH private keys. | Signature identity, field allowlist, plaintext Binder IPC, provider process. |

## Current safeguards visible in code

- The interactive UI requires Android system authentication with a strong biometric or device PIN, pattern, or password. It always locks on cold/process start and relocks after 30 seconds in the background. Devices without an accepted secure lock are blocked until one is configured. Unlock state and monotonic grace timing are memory-only. Prompt errors that mean the device cannot satisfy the policy route to mandatory enrollment; cancellation and retryable errors stay locked.
- `MainActivity` applies `FLAG_SECURE` for its full lifetime, protecting screenshots, screen recording, and Recents previews where Android supports it. Sensitive screens no longer clear the flag on exit.
- The device-authentication gate is an interactive UI control only. The `EncryptedSharedPreferences` store is deliberately not bound to user authentication because unattended background backup and signature-authorized KeyHive provider access are approved requirements. Root, forensic, compromised-process, and trusted-sibling-app access are outside this gate.
- Private-key/passphrase setup screen sets Android's secure-window flag.
- Private-key disclosure requires user confirmation and its dialog sets the secure-window flag.
- Local private-key export keeps the secure-window flag, prioritizes installed supported note apps without excluding other compatible Sharesheet targets, and requires the user's explicit confirmation after export. That confirmation is an attestation, not verification that the receiving app retained the key.
- OpenSSH parsing, Nostr derivation/encryption, and remote record codecs have automated tests; crypto test vectors are in `app/src/test/resources/golden/`.
- Generated associated-account OpenSSH keys use `aes256-ctr` with `bcrypt_pbkdf` and the retained vault-key passphrase; Nostr backups add NIP-44 encryption around that encrypted key payload.
- Local secure storage, source adapters, consolidation, and background scheduling have distinct implementation areas.
- NIP-42 relay challenges are signed transiently with the operation's Nostr identity and are not persisted. The initial **Create VaultKey** relay backup uses the identity derived from the recoverable 24-word `brave` sub-account mnemonic; general backup and consolidation use the stored original-seed Vault Key identity.
- `RemoteOperationPrecondition` blocks both activity navigation and WorkManager consolidation before timestamped remote authentication unless Android reports a system-validated internet connection and the clock is within two minutes of a HTTPS response's server time. It uses the first HTTP `Date` header returned by Google or Cloudflare and compensates for half the observed request round-trip time. Background work retries without producing Nostr or Brave timestamped authentication when connectivity is not validated or skew is known bad. If neither source responds, the app allows access/work because it cannot establish that the device clock is wrong. The block screen identifies each proven unmet prerequisite without exposing vault data. TLS protects the response in transit, but this is not independent proof that either endpoint's clock or the network is trustworthy.
- Backup status, pending writes, and checkpoints persist only canonical account/source IDs, coarse state/error categories, counters, and timestamps—not keys, passphrases, payloads, sync codes, auth tokens, or challenges.
- Presence comparisons are identifier-only. A remote private-key payload is decrypted only when its associated account is absent locally and restoration is required.
- The KeyHive provider exposes only associated-account `createdAt`, `zentenKey`, and `nostrPubkey` fields as `json_v1` through a signature-protected ContentProvider. The signature permission is the IPC confidentiality boundary; certificate-derived application encryption is not used because the certificate is public. Vault Key material, passphrases, mnemonics, SSH public keys, and Nostr private keys are not exposed; the Nostr public key is shared so sibling apps can address the account.

## Required production review topics

1. Threat model for a stolen, unlocked, rooted, or malware-infected device.
2. Secure-storage and Android backup configuration on all supported OS versions.
3. Clipboard retention, screenshot/task-switcher behavior, accessibility exposure, and automatic hiding of sensitive content.
4. Remote-data validation, signature checks, encryption compatibility, downgrade/replay behavior, and denial-of-service limits.
5. Network timeouts, retries, offline state, remote-service compromise/outage, and status accuracy.
6. Secret handling in logs, telemetry, analytics, crash reporting, CI, support, and release builds.
7. Independent cryptography and protocol review before production use.
8. Whether interactive private-key disclosure should use a separate authentication-bound encryption domain, given that background backup and KeyHive provider access must remain unattended.

## Non-promises

- Device authentication is currently an interactive UI gate, not an authentication-required Keystore wrapper around stored vault data. It does not replace the Vault Key private key and passphrase for recovery, restrict background workers, or change signature-authorized KeyHive provider access.
- Remote backup does not guarantee availability. Vault Key setup requires one eligible Nostr and one eligible Brave source, not successful writes to every source. The current app has no account-deletion or vault-reset action.
- The application cannot recover Vault Key material that the user has lost.
- Every application signed with Vault's trusted release certificate can read each shared associated SSH private key. A compromised trusted sibling app or device is outside this protection boundary.

## Change control

Changes to derivation, remote record formats, secure storage, or sensitive UI behavior require security review, compatibility tests, and updates to the relevant feature and stakeholder documentation.
