# ZenTen Vault Product Documentation

This directory is the platform-neutral handoff for teams building ZenTen Vault on iOS, Android, or another client platform. It describes **the product contract**: user-visible behavior, data meaning, recovery, backup interoperability, security expectations, and acceptance criteria. It intentionally does not prescribe a programming language, UI framework, database, dependency, or internal architecture.

## Product in one sentence

ZenTen Vault lets a user anchor a vault to an existing passphrase-protected Ed25519 OpenSSH private key, derive a deterministic recovery identity from it, create additional SSH accounts, and keep those accounts recoverable through encrypted Nostr-relay backups and Brave Sync backups.

## Read in this order

1. [Product Brief](product-brief.md) — audience, goals, terminology, scope, and non-goals.
2. [Product Requirements](product-requirements.md) — complete functional behavior and rules.
3. [User Experience Specification](user-experience.md) — screens, navigation, content, states, and accessibility.
4. [Data and Backup Contract](data-and-backup-contract.md) — canonical data concepts, interoperability formats, remote backup records, and reconciliation.
5. [Security, Privacy, and Quality](security-privacy-quality.md) — trust boundaries, mandatory protections, operational behavior, and acceptance tests.

## Documentation conventions

- **Must** means a product requirement necessary for compatibility, security, or a consistent user experience.
- **Should** means expected behavior unless a platform limitation is documented and approved.
- **May** means an optional enhancement that must not change the required behavior.
- An **account** in the main account list always means a generated **sub-account**. The master identity is deliberately hidden from routine account-management UI.
- `createdAt` means the canonical microsecond account identifier, represented as decimal text when serialized or displayed.
- SSH private keys are represented remotely and in secure storage as a **single-line Base64 OpenSSH-v1 blob**, unless a screen explicitly renders a PEM wrapper for user export.

## Canonical product decisions

These decisions apply to all platform implementations:

- The master SSH key is supplied by the user; the app never creates it.
- A successful decryption of that SSH key is the passphrase check.
- The master key and passphrase deterministically produce the recovery identity. Losing the local device must not make a correctly held master key and passphrase unrecoverable.
- Remote backup is encrypted before it leaves the device. Nostr relay backup is mandatory before onboarding can complete; Brave Sync backup is an independent, retryable second source.
- Generated sub-accounts have no user passphrase and are identified by their creation ID. They are never Nostr signing identities.
- After successful onboarding, the relay and sync-server configuration is locked to prevent silent backup-destination substitution.
- Sync only adds or repairs local knowledge; it never silently deletes local accounts.

## Relationship to repository material

The root-level Android proof-of-concept plan and source code are useful implementation history, not the cross-platform product contract. If they conflict with this directory, this directory is the handoff source for a full product implementation. Any product change must update the affected documents here in the same change.
