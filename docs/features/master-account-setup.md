# Master account setup

## User outcome

A new user sets up ZenTen Vault with the private key and passphrase for an existing Ed25519 OpenSSH master account. This material establishes the vault identity used for backup and recovery.

## Current behavior

1. The user opens **Get started** and proceeds to **Set up your master account**.
2. The user pastes an OpenSSH private key and enters its passphrase.
3. The app validates the material locally and derives the vault identity.
4. The app stores the master account securely, creates the required master backup, and marks setup complete only when that required backup succeeds.
5. The app then starts automatic discovery and recovery of associated accounts.

If setup fails after the master account is stored, local master-account state and the setup-complete marker are rolled back. The user can correct the input or retry.

## Failure and security expectations

- Invalid input and wrong-passphrase errors must not echo private-key or passphrase content.
- The setup screen is protected from screenshots on supported Android devices.
- The primary setup action is unavailable while processing is in progress.
- The master account is not shown as a normal item in **My Accounts**.

## Current limitations

- The current UI supports pasted key material. File import and QR intake are not available.
- Production review is still required for supported key formats, error language, storage, backup destinations, and end-to-end setup recovery.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/onboarding/`
- Setup transaction: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- SSH parsing: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- Secure storage: `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
