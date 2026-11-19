# Sensitive key access

## User outcome

A user can inspect an associated key's public key and fingerprint, and can deliberately export its passphrase-encrypted private key when needed. The decrypted private key is never rendered.

## Current behavior

1. The user opens an associated key from **Keys**.
2. The app displays the key creation ID, SSH public key, and SHA-256 fingerprint; each identifier can be tapped to copy it. The key's Nostr identity stays internal: no `npub` is shown or copied, and the SSH public-key line carries no comment linking it to that identity.
3. The app shows per-source backup state below the identity cards.
4. Optionally, the user selects **Export Key to Notes**, re-enters the passphrase protecting the key, then picks an installed notes-capable app from the in-app target picker. The app sends the self-describing encrypted-private-key export text, which does not include the key's `npub`.
5. Optionally, the user selects **Export Key to Clipboard**, re-enters the same passphrase, and the app copies the same self-describing passphrase-encrypted key text that the notes export sends. The clip is flagged as sensitive and the confirmation tells the user it will be cleared in 60 seconds (see Limitations).
6. Optionally, the user selects **Show Key as QR Code**, re-enters the same passphrase, and the app shows the same text as a QR code in a dialog. The code is not copied anywhere and is dropped when the dialog closes.
7. In every export path (notes, clipboard, and QR code) only the encrypted key leaves the app; the decrypted private key is never displayed or copied.

No screen in the app displays or copies the decrypted private key. The only private-key content a user can obtain is the passphrase-encrypted export.

## Failure and security expectations

- A private key leaves the app only through an explicit export action the user starts; opening key details reveals nothing secret.
- Before the encrypted private key is exported through the voluntary key-detail exports (**Export Key to Notes**, **Export Key to Clipboard**, or **Show Key as QR Code**) or the Vault Key actions (**Export VaultKey to Notes** or **Show VaultKey as QR Code**), the user must re-enter the passphrase that protects that key or Vault Key. A wrong or blank passphrase shows an inline error and keeps the export from running. The mandatory creation-time exports do not re-ask, because the passphrase was just entered to create the record.
- Passphrase verification runs off the main thread. The check is a bcrypt_pbkdf derivation that can take seconds at the highest accepted work factor, so it executes on a worker dispatcher rather than the main looper and cannot freeze the UI or trigger an ANR. While the derivation is in flight, the export dialog shows a busy indicator and its Confirm and Cancel actions and passphrase field are disabled and non-interactive; the dialog cannot be dismissed during that window, and a mismatch is reported after it completes.
- The VaultKey's 24 BIP-39 Secret Combinations are also passphrase-gated. They are only derived and displayed after the user re-enters the VaultKey passphrase, and that review flow is described in [Secret Combinations review](secret-combinations-review.md).
- The key-detail and export screens are screenshot-protected (`FLAG_SECURE` on the activity), and no plaintext key is ever rendered, so there is no plaintext view to capture.
- Normal UI should not expose private-key content in notifications, errors, or logs.

## Current limitations

- There is no plaintext reveal by design. A user who needs the bare private key must export the encrypted form and decrypt it elsewhere with the key passphrase.
- **Export Key to Clipboard** (`ui/components/SensitiveClipboard.kt`) clears the clipboard 60 seconds after the copy. The clear is a WorkManager job (`background/ClipboardClearWorker.kt`), so it also runs if Android kills the app process meanwhile; the job's stored input is only a random ownership token, never the export. Each clip carries that token in its description extras, and the clear is skipped when the clipboard visibly holds something else. A clip-changed listener also cancels the job when Vault can see that other content replaced the export; an unreadable clipboard (for example while the Android 13+ copy overlay holds focus) never cancels it. On Android 10+ a background app cannot read the clipboard, so a clear that runs after process death cannot check ownership and clears unconditionally, which can remove text the user copied within that minute. The clip is also flagged with `EXTRA_IS_SENSITIVE`, which on Android 13+ hides the preview in the system copy confirmation and asks keyboards not to suggest or store it. Vault cannot remove copies already saved by OEM or keyboard clipboard-history features, and any app that reads the clipboard during the 60-second window can still obtain the passphrase-encrypted text.
- The QR code shows the same text as the notes and clipboard exports, so it is a user-requested disclosure. `FLAG_SECURE` blocks screenshots and screen recording of the code, but it cannot stop someone photographing the screen with another camera. Scanning the code yields only that passphrase-encrypted export text, which still needs the passphrase to use.
- Android cannot verify that a selected Sharesheet target saved the key. The mandatory post-creation flow completes its export step when it opens the chooser, the voluntary **Export Key to Notes** action on key details cannot verify the receiving app at all, and **Export Key to Clipboard** cannot verify whether or when the clipboard text was used.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/keys/KeyDetailScreen.kt`
- Secret Combinations review: `app/src/main/java/app/zenten/vault/ui/secretcombinations/`
- Fingerprint/key utilities: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
