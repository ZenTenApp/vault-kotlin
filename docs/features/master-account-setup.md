# Master account setup

## User outcome

A new user sets up ZenTen Vault with the private key and passphrase for an existing Ed25519 OpenSSH master account. This material establishes the vault identity used for backup and recovery.

## Current behavior

1. The user opens **Get started** and selects **Login with MasterKey**, which proceeds to **Setup MasterKey Account**. Importing an existing key is the only entry path with a completed setup transaction. **Create MasterKey** now opens the first three screens of the in-app generation wizard (an unrecoverable-loss warning and consent gate, a **MasterKey Passphrase** step, and a **Confirm Passphrase** re-entry step); those screens only collect input, and the federated sign-in actions on the Get started screen remain design placeholders that report "coming soon".
2. On **Setup MasterKey Account**, the user copies a passphrase-protected OpenSSH private key and selects **Paste MasterKey**. The app reads the first clipboard item without displaying it, clears the clipboard immediately, and validates that it is a supported encrypted OpenSSH-v1 Ed25519 container before proceeding to the passphrase step. PEM, raw Base64, and the supported `{"OPENSSH PRIVATE KEY":"..."}` wrapper are accepted; malformed, unencrypted, non-Ed25519, and unsupported containers are rejected on the paste step.
3. The app validates the material locally and derives the vault identity.
4. The app stores the master account securely and attempts all enabled sources sequentially.
5. Setup completes only after at least one eligible Nostr relay and one eligible Brave Sync server already contain or acknowledge the master backup.
6. The app then schedules source consolidation, which discovers and restores associated accounts before repairing remote gaps.

If remote quorum is unavailable, setup remains incomplete but the validated master stays in secure local storage. The user can retry backup without pasting or deriving the master again.

## Failure and security expectations

- Invalid input and wrong-passphrase errors must not echo private-key or passphrase content.
- The private key is never rendered during import. It remains in memory only while the passphrase step is active and is discarded if the user returns to the paste step.
- Clipboard clearing is best-effort: Vault clears Android's current clipboard entry before proceeding, but the operating system, a keyboard, or another clipboard-history provider may retain an earlier copy outside Vault's control. If Vault cannot read or clear the clipboard, it does not proceed.
- The setup screen is protected from screenshots on supported Android devices.
- The primary setup action is unavailable while processing is in progress.
- The master account is not shown as a normal associated-account item; its per-source state is available from the dedicated **Master Account** screen.
- Quorum failure messages identify whether relay or Brave Sync coverage is missing without exposing source credentials or secret material.

## Current limitations

- The current UI supports clipboard paste only. Manual text entry, file import, and QR intake are not available.
- In-app master-key generation (**Create MasterKey**) is only implemented through its first three screens (the "Getting Started" consent gate, **MasterKey Passphrase**, and **Confirm Passphrase**). **Verify** on the confirmation step performs no action: passphrase matching, key generation, and recovery-material display are not implemented yet. The entered passphrase is held only in screen state, is never persisted, and is discarded when the wizard is left. The user must already hold an Ed25519 OpenSSH key to complete setup.
- Federated sign-in (Google, Apple, Microsoft) is not implemented. Its meaning for a key-rooted vault is an open product decision, since the root of trust is a keypair rather than a provider account.
- Production review is still required for supported key formats, error language, storage, backup destinations, and end-to-end setup recovery.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/onboarding/`
- Create MasterKey wizard (in-app key generation, screens 1-3 of N): `app/src/main/java/app/zenten/vault/ui/onboarding/createmasterkey/`
- Setup transaction: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- SSH parsing: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- Secure storage: `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
