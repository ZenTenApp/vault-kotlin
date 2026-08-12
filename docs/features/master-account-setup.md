# Master account setup

## User outcome

A new user sets up ZenTen Vault with the private key and passphrase for an existing Ed25519 OpenSSH master account. This material establishes the vault identity used for backup and recovery.

## Current behavior

1. The user opens **Get started** and proceeds to **Set up your master account**.
2. The user pastes a passphrase-protected OpenSSH private key and enters its passphrase. Unencrypted private keys are rejected.
3. The app validates the material locally and derives the vault identity.
4. The app stores the master account securely and attempts all enabled sources sequentially.
5. Setup completes only after at least one eligible Nostr relay and one eligible Brave Sync server already contain or acknowledge the master backup.
6. The app then schedules source consolidation, which discovers and restores associated accounts before repairing remote gaps.

If remote quorum is unavailable, setup remains incomplete but the validated master stays in secure local storage. The user can retry backup without pasting or deriving the master again.

## Failure and security expectations

- Invalid input and wrong-passphrase errors must not echo private-key or passphrase content.
- The setup screen is protected from screenshots on supported Android devices.
- The primary setup action is unavailable while processing is in progress.
- The master account is not shown as a normal associated-account item; its per-source state is available from the dedicated **Master Account** screen.
- Quorum failure messages identify whether relay or Brave Sync coverage is missing without exposing source credentials or secret material.

## Current limitations

- The current UI supports pasted key material. File import and QR intake are not available.
- Production review is still required for supported key formats, error language, storage, backup destinations, and end-to-end setup recovery.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/onboarding/`
- Setup transaction: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- SSH parsing: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- Secure storage: `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
