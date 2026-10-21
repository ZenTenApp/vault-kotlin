# Scope and roadmap

## Status legend

- **Implemented (PoC):** code is present in this repository; it is not a production-readiness claim.
- **Partial:** a portion of the desired experience exists, but important behavior or polish remains.
- **Planned:** documented product direction that should not be promised as current app behavior.
- **Out of scope:** intentionally not part of ZenTen Vault.

## Capability status

| Capability | Status | Notes |
| --- | --- | --- |
| Android first-run entry and vault-key setup screen | Implemented (PoC) | Supports pasted Vault Key private-key material and passphrase entry. |
| Custom backup-source management | Deferred | The current app exposes no management UI. Domain persistence and source processing remain available for a future implementation. |
| Vault Key-account validation and deterministic vault identity | Implemented (PoC) | Technical behavior is covered by automated tests; requires production security review. |
| Generate associated Ed25519 SSH accounts | Implemented (PoC) | Created accounts appear in the account list using creation IDs. |
| View public key and fingerprint | Implemented (PoC) | Available on associated-account detail. |
| Confirm before showing/copying private key | Implemented (PoC) | Sensitive-view safeguards are present; expiry/export polish is still needed. |
| Re-display and verify VaultKey Secret Combinations | Implemented (PoC) | Reachable from **Settings**. Passphrase-gated, non-cancellable once entered, and resumes after a restart until verification completes. |
| Encrypted Nostr relay backup | Implemented (PoC) | Requires operational validation and production hardening. |
| Brave Sync backup and discovery | Implemented (PoC) | Requires end-to-end interoperability and release validation. |
| Backup status and manual sync | Partial | Current UI shows destination markers; richer plain-language status, timestamps, and recovery guidance remain. |
| Automatically discover and restore associated accounts | Partial | Recovery services exist; validate the end-to-end automatic restoration experience. |
| File import and QR key intake | Planned | Archived product specification describes these options; current UI is paste-oriented. |
| Key export/share workflow | Planned | Current proof of concept provides reveal/copy, not a complete export/share flow. |
| Rename or rotate associated accounts | Out of scope | Creation ID is currently the stable account label. |
| Delete an associated account or reset the vault | Not available | The current app provides neither action. |
| SSH agent, terminal, or Git client | Out of scope | ZenTen Vault manages credentials; it does not use them to log into servers. |
| Recover a lost Vault Key private key or passphrase | Out of scope | User-held Vault Key material is required. |

## Release gates before production use

The following are required before positioning the app as a production credential vault:

1. Independent security assessment of cryptography, storage, backups, and user-facing safety controls.
2. End-to-end interoperability testing with real supported backup destinations.
3. Approved setup, recovery, error, and offline UX—including accessible content.
4. Release signing, update, telemetry, privacy, support, and incident-response processes.
5. Clear user documentation and tested support procedures for lost vault-key recovery material.

## Roadmap governance

This page is a planning aid, not a delivery commitment. Change a capability to **Implemented** only when code, acceptance testing, and stakeholder-approved behavior are present. Record material changes to recovery, backup formats, or security assumptions in the technical decision record set.
