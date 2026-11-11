# Sensitive key access

## User outcome

A user can inspect an associated account's public key and fingerprint, and can deliberately export its passphrase-encrypted private key when needed. The decrypted private key is never rendered.

## Current behavior

1. The user opens an associated account from **Accounts**.
2. The app displays the account creation ID, SSH public key, and SHA-256 fingerprint; each identifier can be tapped to copy it. The account's Nostr identity stays internal: no `npub` is shown or copied, and the SSH public-key line carries no comment linking it to that identity.
3. The app shows per-source backup state below the identity cards.
4. Optionally, the user selects **Export Account to Notes**, re-enters the passphrase protecting the account, then picks an installed notes-capable app from the in-app target picker. The app sends the self-describing encrypted-private-key export text, which does not include the account's `npub`.
5. Optionally, the user selects **Export Account to Clipboard**, re-enters the same passphrase, and the app copies only the passphrase-encrypted key as an OpenSSH PEM block.
6. In both export paths only the encrypted key leaves the app; the decrypted private key is never displayed or copied.

No screen in the app displays or copies the decrypted private key. The only private-key content a user can obtain is the passphrase-encrypted export.

## Failure and security expectations

- A private key leaves the app only through an explicit export action the user starts; opening account details reveals nothing secret.
- Before the encrypted private key is exported through the voluntary account-detail exports (**Export Account to Notes** or **Export Account to Clipboard**) or the Vault Key export action, the user must re-enter the passphrase that protects that account or Vault Key. A wrong or blank passphrase shows an inline error and keeps the export from running. The mandatory creation-time exports do not re-ask, because the passphrase was just entered to create the record.
- Passphrase verification runs off the main thread. The check is a bcrypt_pbkdf derivation that can take seconds at the highest accepted work factor, so it executes on a worker dispatcher rather than the main looper and cannot freeze the UI or trigger an ANR. While the derivation is in flight, the export dialog shows a busy indicator and its Confirm and Cancel actions and passphrase field are disabled and non-interactive; the dialog cannot be dismissed during that window, and a mismatch is reported after it completes.
- The VaultKey's 24 BIP-39 Secret Combinations are also passphrase-gated. They are only derived and displayed after the user re-enters the VaultKey passphrase, and that review flow is described in [Secret Combinations review](secret-combinations-review.md).
- The account-detail and export screens are screenshot-protected (`FLAG_SECURE` on the activity), and no plaintext key is ever rendered, so there is no plaintext view to capture.
- Normal UI should not expose private-key content in notifications, errors, or logs.

## Current limitations

- There is no plaintext reveal by design. A user who needs the bare private key must export the encrypted form and decrypt it elsewhere with the account passphrase.
- Clipboard-clearing behavior is not implemented. **Export Account to Clipboard** copies only the passphrase-encrypted key as an OpenSSH PEM block and deliberately leaves it on the clipboard until the user replaces or clears it, so it is a user-requested disclosure rather than an automatic one.
- Android cannot verify that a selected Sharesheet target saved the key. The mandatory post-creation flow completes its export step when it opens the chooser, the voluntary **Export Account to Notes** action on account details cannot verify the receiving app at all, and **Export Account to Clipboard** cannot verify whether or when the clipboard text was used.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailScreen.kt`
- Secret Combinations review: `app/src/main/java/app/zenten/vault/ui/secretcombinations/`
- Fingerprint/key utilities: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
