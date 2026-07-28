# ZenTen Vault — User Experience Specification

## 1. App structure

The primary navigation has three tabs:

- **Accounts** — sub-account list, pending restores, locked accounts, and creation.
- **Backup Status** — cross-source health and repair actions.
- **Settings** — read-only locked destinations, app information, and reset.

Use stack navigation for account detail and creation. Use native modal/dialog presentation for sensitive disclosure, restore confirmation, unlock, and destructive actions. Deep links may open `/accounts/<createdAt>` only for a local sub-account; there is no master-account detail route.

## 2. Launch and global gates

Show a non-interactive loading state while setup status and secure vault state are read.

| Condition | Required result |
| --- | --- |
| Setup incomplete | Welcome/onboarding. |
| Setup complete + master identity available | Accounts, then background retry/sync without blocking local UI. |
| Setup complete + master unavailable | Explain corrupted local state and direct to safe onboarding/reset recovery path. |
| Device time outside permitted drift | Full-screen incorrect-time explanation; block network operations; Retry rechecks. |
| Offline during network-dependent task | Offline screen/state with retry; local reading remains available. |

## 3. Onboarding

### Welcome

Display product name, short recovery-focused explanation, primary **Get Started / Import SSH Key** action, and a pre-setup destination-settings action. Do not imply that the product creates the master key.

### Key entry

Display a multiline private-key input, paste action, and where supported QR scan/file import actions. Explain supported OpenSSH Ed25519 input succinctly. Validate inline and retain input only for the active setup attempt. A back action returns to Welcome without completing setup.

### Passphrase entry

Use a masked field, an accessible show/hide control, and no password autofill. State that this is the existing SSH key passphrase. On Continue, validate through decryption; show a clear inline failure without exposing whether any other derived material was produced.

### Setup progress

This screen is non-interactive except for failure retry/cancel behavior. Present truthful step status, such as:

1. Validating SSH key
2. Deriving recovery identity
3. Creating Nostr identity
4. Creating Brave Sync recovery code
5. Publishing protected relay backup
6. Backing up to Brave Sync
7. Looking for existing accounts
8. Complete

Relay master-backup failure is blocking and must offer retry. Brave Sync failure is shown as a non-blocking warning: setup can finish, with later retry. When discovery finds accounts, present pending restores before or upon arriving at Accounts.

## 4. Accounts tab

### Main list

Header: **My Accounts**, with concise counts for pending restores and locked accounts when applicable. List normal sub-account cards first, then Pending Restores, then Locked Accounts. The master identity is never listed.

A normal card includes an avatar/identifier treatment, canonical label, truncated SSH fingerprint, health indicator, and disclosure affordance. Tapping opens details. The primary floating action creates a new account.

If all sections are empty, show an empty state: **No accounts yet** and an action to create the first account.

### Pending restores

A pending card uses a download/recovery indicator, account ID, source badges (Relay and/or Brave Sync), and tap action to show a restore dialog. The dialog identifies the account and sources, explains that it was found in backup, and offers Restore or Cancel. On restore, refresh the list and announce success, already-present, or recovery failure accessibly.

### Locked accounts

A locked card uses a clearly distinct lock/error treatment. Tapping opens **Unlock Account**, explains that the account must be re-fetched, and offers Retry and Cancel. Successful retry moves it into normal accounts; failure keeps it locked with a useful non-secret explanation.

## 5. Account detail

Show:

- back navigation and exact account label;
- human-readable creation time in addition to the exact ID;
- SSH fingerprint and public key, each copyable;
- backup status by relay and Brave Sync, including last checked time; and
- Retry Backup when status is partial or missing.

The private-key area must be visually separated and described as sensitive. **Show Private Key** opens a confirmation first. After confirmation, show the PEM-formatted value with Copy and Share/Export. Never preselect the key or reveal it merely by opening the detail page. Apply auto-hide/background protection where supported.

A danger zone contains **Delete Account**. Its confirmation says local access will be removed but remote backups are not deleted.

## 6. Create account

Start with a confirmation screen titled **Create New Account**. Explain that a new SSH key pair will be generated, assigned a timestamp-based label, saved locally, and backed up automatically. Offer Create and Cancel.

During creation, show progress for recording timestamp, generating key pair, saving account, starting backup, and done. On local success, return to Accounts immediately; remote backup may still be running. Do not frame a background backup failure as failure to create the account.

## 7. Backup Status tab

Show title, last refreshed timestamp, manual Refresh, and summary counts for Healthy, Partial, Missing, and Unknown. List configured relay names and a card for every local sub-account. Each card shows its label, health badge, per-relay indicators, Brave Sync indicator, and Retry when appropriate.

While refreshing, preserve the last usable catalog where possible and show a progress indication. If no catalog exists, use an explicit **No backup data yet** state rather than displaying every account as missing.

## 8. Settings and reset

### Before setup

Pre-setup settings are a modal or screen reachable from Welcome. Users can add/remove relay URLs, must retain at least one valid `ws://` or `wss://` URL, and explicitly save changes.

### After setup

Show relay URLs and Brave Sync server destination read-only, with clear copy that destinations are locked after setup for security. Also show app version/build information.

The reset action sits in a danger zone. Require confirmation that all local vault data will be cleared and remote backups remain. On success, return to Welcome.

## 9. Interaction, accessibility, and content rules

- Use native semantic controls, visible focus, logical screen-reader order, dynamic type, sufficient contrast, and labels that do not rely on color alone.
- Announce background job status changes and restore results through accessible live feedback, without revealing keys or passphrases.
- Password controls are masked by default and provide an explicitly labeled visibility toggle.
- Do not include private keys, mnemonics, sync codes, Nostr private keys, or passphrases in analytics, crash reports, toast text, clipboard history, screenshots, or notifications.
- Provide loading, empty, error, and disabled states for every asynchronous action. A retry must be available when retry is meaningful.
- Use platform-native haptics/animations only as enhancement; they must not be the sole success or failure cue.
- Local operations should remain usable offline. Explain queued/deferred backup work in plain language.
