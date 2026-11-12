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
  stored and cannot be decrypted", with `UnreadableReason.KeystoreKeyMissing` and
  `UnreadableReason.PayloadInvalid`.
- `SecureStorageService.hasUnreadableRecords()` reports whether any VaultKey **or account** record
  is present but unreadable. Checking only the VaultKey would misreport a partial transfer as a
  fresh install.
- `VaultUnlockGateViewModel` reports `VaultUnreadable` instead of `Onboarding` in that state, so the
  user is not shown an empty vault.
- `StrandedVaultScreen` explains the state and leads to **Get started** → **Recover VaultKey**.
- `StrandedVaultResetService` moves unreadable ciphertext under the `quarantined_` prefix and clears
  the routing flags (`setup_complete`, quorum, pending-import, wizard step, pending/local-backed-up
  account IDs) so onboarding and recovery are reachable. Storage is set aside **before** the flags
  are cleared, so an interrupted reset cannot leave the router treating the device as a fresh
  install while the gate still sees an unreadable vault.

Two limits are deliberate:

- Quarantined ciphertext is **retained, not recoverable**. It needs the original device's Keystore
  key, so neither a future build nor a recovered passphrase can open it here. Keeping it only avoids
  the app destroying the user's last local copy.
- Device-local configuration and history — custom backup sources, per-source statuses, the
  consolidation checkpoint, and consolidation timestamps — are **not** cleared, because they stay
  valid after recovery and contain no secrets.

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
