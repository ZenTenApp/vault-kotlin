# Security model

> This document records current security intent and review scope. It is not a security audit or a claim that the proof of concept is safe for production credentials.

## Protected assets

- User-provided master account private key and its passphrase.
- Derived recovery material and master-account Nostr identity.
- Generated associated-account SSH private keys.
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

## Current safeguards visible in code

- Private-key/passphrase setup screen sets Android's secure-window flag.
- Private-key disclosure requires user confirmation and its dialog sets the secure-window flag.
- OpenSSH parsing, Nostr derivation/encryption, and remote record codecs have automated tests; crypto test vectors are in `app/src/test/resources/golden/`.
- Local secure storage, relay backup, sync backup, retry, and reconciliation have distinct implementation areas.

## Required production review topics

1. Threat model for a stolen, unlocked, rooted, or malware-infected device.
2. Secure-storage and Android backup configuration on all supported OS versions.
3. Clipboard retention, screenshot/task-switcher behavior, accessibility exposure, and automatic hiding of sensitive content.
4. Remote-data validation, signature checks, encryption compatibility, downgrade/replay behavior, and denial-of-service limits.
5. Network timeouts, retries, offline state, remote-service compromise/outage, and status accuracy.
6. Secret handling in logs, telemetry, analytics, crash reporting, CI, support, and release builds.
7. Independent cryptography and protocol review before production use.

## Non-promises

- A device-level PIN, biometric unlock, or operating-system protection does not replace the master account private key and passphrase for recovery.
- Remote backup does not guarantee availability. The current app has no account-deletion or vault-reset action.
- The application cannot recover master account material that the user has lost.

## Change control

Changes to derivation, remote record formats, secure storage, or sensitive UI behavior require security review, compatibility tests, and updates to the relevant feature and stakeholder documentation.
