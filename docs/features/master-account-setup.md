# Master account setup

## User outcome

A new user sets up ZenTen Vault with the private key and passphrase for an existing Ed25519 OpenSSH master account. This material establishes the vault identity used for backup and recovery. The in-app generation path also presents the Brave recovery mnemonic as 24 BIP-39 word-list indices, grouped into eight transcription-friendly rows of three.

## Current behavior

1. The user opens **Get started** and selects **Login with MasterKey**, which proceeds to **Setup MasterKey Account**. **Create MasterKey** opens the implemented generation and backup screens (an unrecoverable-loss warning and consent gate, **MasterKey Passphrase**, **Confirm Passphrase**, **Create Backup**, and **Confirm Backup**). The federated sign-in actions remain design placeholders that report "coming soon".
2. On **Setup MasterKey Account**, the user copies a passphrase-protected OpenSSH private key and selects **Paste MasterKey**. The app reads the first clipboard item without displaying it, clears the clipboard immediately, and validates that it is a supported encrypted OpenSSH-v1 Ed25519 container before proceeding to the passphrase step. PEM, raw Base64, and the supported `{"OPENSSH PRIVATE KEY":"..."}` wrapper are accepted; malformed, unencrypted, non-Ed25519, and unsupported containers are rejected on the paste step.
3. The app validates the material locally and derives the vault identity.
4. The app stores the master account securely and attempts all enabled sources sequentially.
5. Setup completes only after at least one eligible Nostr relay and one eligible Brave Sync server already contain or acknowledge the master backup.
6. The app then schedules source consolidation, which discovers and restores associated accounts before repairing remote gaps.

If remote quorum is unavailable, setup remains incomplete but the validated master stays in secure local storage. The user can retry backup without pasting or deriving the master again.

For an in-app generated MasterKey, successful passphrase verification is the irreversible commit point. Vault derives the account record, writes it to keystore-backed encrypted storage, records the next wizard step, and checks/writes the master on every enabled backup source before showing **Create Backup**. Individual source failures are retained for later repair and do not return the user to key generation. Closing or leaving the app resumes **Create Backup**, **Confirm Backup** after Android has accepted the local export, or **Save your Secret Combinations** after the pasted key matches. Back navigation is disabled from **Create Backup** toward key generation so another identity cannot be generated accidentally; **Confirm Backup** may return to **Create Backup** to export again. After saving the combinations, the user must verify all 24 indices sequentially on **Secret Combinations**. Successful verification opens **Congratulations**, where animated confetti and a completion summary confirm that the MasterKey and recovery backups are ready. Selecting **Complete** marks setup complete and opens the account list; if the app closes before that action, it resumes on **Congratulations**.

## Failure and security expectations

- Invalid input and wrong-passphrase errors must not echo private-key or passphrase content. When creating a MasterKey in-app, Vault requires at least 8 passphrase characters with at least one uppercase letter, one lowercase letter, one number, and one non-whitespace special character; the flow cannot advance until every rule is met. The Create MasterKey wizard reports a passphrase mismatch and a key-generation failure with fixed messages that contain no key or passphrase material.
- In the Create MasterKey wizard, the chosen passphrase remains only in the graph-scoped ViewModel until verification. The generated account (including the encrypted SSH key and passphrase needed by current backup adapters) is then written to keystore-backed encrypted storage before remote backup starts. Only a non-secret wizard-step marker is stored in DataStore. Neither secret is placed in navigation arguments. After the exported key is verified, the app derives the Brave associated-account seed and shows the one-based English BIP-39 positions of its 24 mnemonic words; it does not persist a second copy of these displayed values.
- The private key is never rendered during import. It remains in memory only while the passphrase step is active and is discarded if the user returns to the paste step.
- Clipboard clearing is best-effort: Vault clears Android's current clipboard entry before proceeding, but the operating system, a keyboard, or another clipboard-history provider may retain an earlier copy outside Vault's control. If Vault cannot read or clear the clipboard, it does not proceed.
- The setup screen is protected from screenshots on supported Android devices.
- The primary setup action is unavailable while processing is in progress.
- The master account is not shown as a normal associated-account item; its per-source state is available from the dedicated **Master Account** screen.
- Quorum failure messages identify whether relay or Brave Sync coverage is missing without exposing source credentials or secret material.

## Current limitations

- The current UI supports clipboard paste only. Manual text entry, file import, and QR intake are not available.
- In-app master-key generation (**Create MasterKey**) is implemented through **Secret Combinations**. **Verify** matches the passphrases, generates an encrypted Ed25519 OpenSSH MasterKey, stores it locally, and attempts backup on all enabled sources. **Export MasterKey** uses the same direct local-backup target picker and canonical key representation as associated-account creation: raw Base64 without OpenSSH PEM wrappers or line breaks. Vault then requires the user to paste the exported key, clears the clipboard, validates its encrypted Ed25519 OpenSSH format, and confirms it exactly matches the stored MasterKey. A successful match advances to **Save your Secret Combinations**, where the 24 one-based BIP-39 indices are grouped in eight rows of three. A compact warning directs the user to write down all eight combinations and store them offline, and explains that Vault cannot restore lost combinations. A three-row snapping picker keeps the centered row fully opaque and fades its neighbors; edge padding allows the first and last rows to become centered without looping. The values can be hidden and gate the inactive **Continue** action behind acknowledgement that all eight combinations were saved. **Continue** opens a verification screen that accepts only the current numeric segment: a correct value automatically focuses the next segment and advances to the next row after each complete combination, while an incorrect value receives an error border. The picker cannot be manually scrolled, and navigating back clears all verification entries. After all segments match, a resumable **Congratulations** screen shows the completion artwork, green success message, backup reminder, and animated confetti. Its **Complete** action marks setup complete, removes the wizard from navigation history, and opens **Accounts**.
- Federated sign-in (Google, Apple, Microsoft) is not implemented. Its meaning for a key-rooted vault is an open product decision, since the root of trust is a keypair rather than a provider account.
- Production review is still required for supported key formats, error language, storage, backup destinations, and end-to-end setup recovery.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/onboarding/`
- Create MasterKey wizard (in-app key generation and backup verification): `app/src/main/java/app/zenten/vault/ui/onboarding/createmasterkey/`
- Create MasterKey wizard shared state, passphrase verification, and key generation: `app/src/main/java/app/zenten/vault/ui/onboarding/createmasterkey/CreateMasterKeyViewModel.kt`
- Key generation primitive: `SshKeyCodec.generateEncrypted` in `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- Setup transaction: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- SSH parsing: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- Secure storage: `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
