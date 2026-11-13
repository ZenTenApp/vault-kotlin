# Device migration and backup

## User outcome

Moving to a new phone is **never** a data transfer between devices. The user installs Vault fresh
and then re-establishes the vault from material they deliberately kept:

- the exported **VaultKey** (or the 24 **Secret Combinations**), plus
- the **VaultKey passphrase**,

after which consolidation restores associated accounts from their relay and Brave Sync backups.
See [Backup and recovery](../stakeholder/backup-and-recovery.md) for the retention matrix.

## Why no app data is migrated

Vault's local state is not portable, and copying it would produce damage rather than continuity.

| Layer | Portable to another device? | Why |
| --- | --- | --- |
| `sharedprefs/vault_secure_accounts_v2.xml` (VaultKey + every account record) | No | Each value is AES-256-GCM ciphertext under an Android Keystore key. Keystore keys are hardware-bound and are never transferred by cloud backup or device-to-device (D2D) transfer. |
| `files/datastore/vault_app.preferences_pb` (setup, quorum, and export flags) | Yes, but must not be | Plaintext, so it *would* arrive — leaving the app claiming a completed setup over records no screen can open. |
| `AndroidKeyStore` alias `vault_secure_storage_key_v1` | Never | Never included in any backup or transfer path. |

A copy of the ciphertext without its wrapping key is therefore not a partial migration; it is a
state in which the app holds records it can never read. Migrating it also conflicts with the
Data safety declaration: nothing about the user's vault should be described as transferred by the
operating system.

## Declared policy

`app/src/main/AndroidManifest.xml` sets:

- `android:allowBackup="false"` — disables pre-Android-12 backup and Google Drive backup;
- `android:fullBackupContent="false"` — keeps the pre-API-31 file-based path explicitly closed;
- `android:dataExtractionRules="@xml/data_extraction_rules"` — the only control that covers D2D
  transfer on Android 12+.

`app/src/main/res/xml/data_extraction_rules.xml` declares both the `<cloud-backup>` and
`<device-transfer>` sections with **every** domain excluded (`root`, `file`, `database`,
`sharedpref`, `external` and the four `device_*` variants). Both sections are required: a missing
section leaves that mode fully enabled.

This matters because `allowBackup="false"` alone is not sufficient. Platform documentation states
that on Android 12+, for apps targeting API 31+, it disables Google Drive backup but does not
disable D2D transfer on every manufacturer's device, and that the older
`full-backup-content` include/exclude rules no longer affect D2D.

Cross-platform transfer (`<cross-platform-transfer platform="ios">`, Android 16 QPR2 / API 36.1) is
deliberately not declared. That mode requires an iOS `bundleId`/`teamId` pairing to a counterpart
app, and this project has no iOS app. If one is ever added, that section must carry the same
exclusions.

## Implementation: fail-closed fallback

An exclusion is a declaration the operating system may not honour, so the app also detects the
state where a copy exists anyway — an OEM that ignores the rules, a partial transfer, a
backup-tool file restore, or damaged bytes.

- `SecureRecordState` (`data/secure/`) keeps "nothing was ever stored" apart from "something is
  stored and cannot be decrypted", with `UnreadableReason.KeystoreKeyMissing`,
  `UnreadableReason.PayloadInvalid`, and `UnreadableReason.KeystoreUnavailable`. The third value
  exists because "the key is gone" and "the Keystore could not be queried" look identical to
  `decrypt`, which returns `null` for both, but they need opposite responses: the first is
  permanent, the second may clear on retry.
- `SecureStorageService.getVaultKeyState()` reports the VaultKey's state and why it is unavailable;
  `unreadableRecords` publishes the most consequential reason found across **any** VaultKey or
  account record. Checking only the VaultKey would misreport a partial transfer as a fresh install.
- `VaultUnlockGateViewModel` reports `VaultUnreadable` instead of `Onboarding` in that state, and
  carries the scan's reason rather than assuming a missing key, so the screen describes the actual
  cause.
- `StrandedVaultScreen` explains the state in copy chosen by that reason and leads to **Get
  started** → **Recover VaultKey**. When the reason is `KeystoreUnavailable` it offers **Check
  again** instead of the set-aside action, because nothing may be set aside on an unknown answer.
- `StrandedVaultResetService` clears the routing flags first and then moves unreadable ciphertext
  under the `quarantined_` prefix. Flags cleared: `setup_complete`, quorum, pending-import, wizard
  step, Secret Combinations review step, the Confirm Backup paste guard, and the pending/local-
  backed-up account IDs. The review step matters because the launch router checks it *before*
  setup completion, so leaving it set would reopen the review flow ahead of onboarding with no
  readable VaultKey to review.

The reset order is flags first, then storage, and the reason is not the obvious one. Both steps are
retryable and neither deletes anything, so ordering only decides what the user sees if the process
dies between them. The launch gate is evaluated **before** the router, so:

- flags cleared but storage not yet moved → the gate still finds the unreadable record and keeps
  showing the recovery screen; the user retries and nothing is orphaned;
- storage moved but flags not yet cleared → the gate no longer sees an unreadable record, reports a
  fresh install while `setup_complete` still says otherwise, and the router opens an empty account
  list — the silent-loss presentation this feature exists to prevent.

Quarantine is a single generation: a previous reset's parked records are dropped in the same
preference edit, so repeated resets cannot accumulate unreadable ciphertext without bound. Records
whose cause is `KeystoreUnavailable` are never quarantined.

Three limits are deliberate:

- Quarantined ciphertext is **retained, not recoverable**. It needs the original device's Keystore
  key, so neither a future build nor a recovered passphrase can open it here. Keeping it only avoids
  the app destroying the user's last local copy.
- Device-local configuration and history — custom backup sources, per-source statuses, the
  consolidation checkpoint, and consolidation timestamps — are **not** cleared, because they stay
  valid after recovery and contain no secrets.
- A Keystore that cannot be queried makes the vault state **unknown** rather than stranded. The app
  says so and offers a re-check; it never offers to set records aside on that answer.

## Why this is not simply "reset the vault"

The obvious alternative — wipe the local vault and send the user to **Get started** — is closer to
what the app already does than it first appears, so the distinction is worth stating precisely
rather than leaving it implicit.

What a reset could change is only the third row here; the user's recovery outcome is identical.

| Step | Reset | Shipped behaviour |
| --- | --- | --- |
| Clear launch-routing flags | yes | yes |
| Reach **Get started** → **Recover VaultKey** | yes | yes |
| Local ciphertext | deleted | re-keyed to `quarantined_*` |
| Remote relay and Brave Sync backups | untouched | untouched |

Remote backups are the only thing that actually restores the user's accounts, and they live off the
device: no local action, reset or otherwise, can affect them. So this is not a recovery mechanism
versus a reset — both land the user on the same recovery path. The difference is only what happens
to the unreadable local bytes.

Deleting them is rejected for two reasons, one of which is load-bearing:

- **The action is triggered by detection, not by request.** The screen appears because the app
  concluded its own records are unreadable. If that conclusion is wrong — an OEM Keystore quirk, a
  transient failure, a bug in this detection — a delete destroys a working vault with no undo.
  A few kilobytes of retained ciphertext is cheap insurance against the app being the thing that
  destroyed the user's last local copy, and quarantine is bounded to one generation.
- **In the third case a reset is simply incorrect.** `UnreadableReason.KeystoreUnavailable` means
  the device could not read its own key store, not that the records are broken. Resetting there
  would be data loss masquerading as recovery, which is why that state offers **Check again** and
  refuses to set anything aside.

The retention argument is therefore strongest for the unavailable-Keystore case and weakest for
`PayloadInvalid`, where the bytes truly cannot be read here. It is kept uniform because a single
rule is easier to reason about and verify than a per-reason matrix, and because the cost is one
bounded preference entry.

A genuine **delete my local records** action is a different feature with different consent
requirements: it would need an explicit, user-initiated confirmation rather than reuse of this
recovery screen. That is the still-open M-06 delete/reset item and is deliberately not folded into
this path.

## Known limitation

The fallback is still a dead end for a user who never kept a VaultKey backup or the Secret
Combinations: no path on the device opens quarantined ciphertext. That is a property of the key
model, not a bug to fix, and is why the creation flow gates on the backup quorum and forced local
export.

## Primary implementation locations

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/java/app/zenten/vault/data/secure/SecureRecordState.kt`
- `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
- `app/src/main/java/app/zenten/vault/domain/StrandedVaultResetService.kt`
- `app/src/main/java/app/zenten/vault/ui/auth/VaultUnlockGateViewModel.kt`
- `app/src/main/java/app/zenten/vault/ui/auth/StrandedVaultScreen.kt`
- `app/src/main/java/app/zenten/vault/data/kv/AppStorageService.kt`
