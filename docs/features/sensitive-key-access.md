# Sensitive key access

## User outcome

A user can inspect an associated account's public key and fingerprint, and can deliberately reveal or copy its private key when needed.

## Current behavior

1. The user opens an associated account from **My Accounts**.
2. The app displays the account creation ID, public key, and fingerprint.
3. The user selects **Show private key**.
4. The app warns that anyone who sees the private key can access the account.
5. After the user confirms, the app shows the private key and offers **Copy**.
6. The user closes the private-key dialog when finished.

Private-key disclosure is never the default account-detail state.

## Failure and security expectations

- The user must confirm immediately before private-key disclosure.
- Before the encrypted private key is exported through the voluntary account-detail exports (**Export Account to Notes** or **Export Account to Clipboard**) or the Vault Key export action, the user must re-enter the passphrase that protects that account or Vault Key. A wrong or blank passphrase shows an inline error and keeps the export from running. The mandatory creation-time exports do not re-ask, because the passphrase was just entered to create the record.
- The VaultKey's 24 BIP-39 Secret Combinations are also passphrase-gated. They are only derived and displayed after the user re-enters the VaultKey passphrase, and that review flow is described in [Secret Combinations review](secret-combinations-review.md).
- The private-key dialog is protected from screenshots on supported Android devices.
- Normal UI should not expose private-key content in notifications, errors, or logs.

## Current limitations

- The private-key view does not currently auto-hide after a timeout.
- Clipboard-clearing behavior is not implemented. **Export Account to Clipboard** copies only the passphrase-encrypted key as an OpenSSH PEM block and deliberately leaves it on the clipboard until the user replaces or clears it, so it is a user-requested disclosure rather than an automatic one.
- Android cannot verify that a selected Sharesheet target saved the key. The mandatory post-creation flow completes its export step when it opens the chooser, the voluntary **Export Account to Notes** action on account details cannot verify the receiving app at all, and **Export Account to Clipboard** cannot verify whether or when the clipboard text was used.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailScreen.kt`
- Secret Combinations review: `app/src/main/java/app/zenten/vault/ui/secretcombinations/`
- Fingerprint/key utilities: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
