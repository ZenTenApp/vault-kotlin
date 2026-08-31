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
- The private-key dialog is protected from screenshots on supported Android devices.
- Normal UI should not expose private-key content in notifications, errors, or logs.

## Current limitations

- The private-key view does not currently auto-hide after a timeout.
- Clipboard-clearing behavior is not implemented.
- Android cannot verify that a selected Sharesheet target saved the key. The mandatory post-creation flow completes its export step when it opens the chooser.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/accounts/AccountDetailScreen.kt`
- Fingerprint/key utilities: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
