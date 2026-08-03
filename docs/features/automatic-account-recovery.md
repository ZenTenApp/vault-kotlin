# Automatic account recovery

## Purpose and scope

Automatic account recovery rebuilds local associated-account records from remote backups after a user sets up the vault with the same master account private key and passphrase. It does not recover the master account itself: the user must supply that material again during setup.

All valid discovered associated accounts are restored automatically. There is no selection screen, pending-restore state, or manual restore action.

## Recovery triggers

Recovery is invoked in two places:

1. **After successful master-account setup.** `SetupService.recoverExistingAccounts()` loads the stored master account and delegates to `RecoveryService.discoverAssociatedAccounts`. It is intentionally separate from the setup transaction so the account-list UI can report progress while remote services respond.
2. **During backup reconciliation.** `BackupSyncService.sync()` discovers associated accounts while inspecting each remote destination and stores any valid account that is not already local.

A recovery failure does not remove the already-established master account or mark setup incomplete. It is returned to the UI/worker path so a later sync can retry.

## Prerequisites

Recovery requires:

- a locally stored master account;
- the derived master Nostr identity for relay discovery/decryption;
- availability of at least one configured remote destination containing a valid backup; and
- a valid encrypted private-key payload for each recovered associated account.

The original master account private key and its passphrase remain mandatory user-held recovery material. The app cannot recreate either item from a remote associated-account backup.

## Direct discovery flow

`RecoveryService.discoverAssociatedAccounts(master)` performs the post-setup discovery path:

1. It requires the master account's Nostr identity.
2. It fetches Nostr events authored by the master Nostr public key with kind `37802`.
3. It loads existing associated-account IDs from secure storage.
4. It validates and decodes each relay event.
5. For each valid account ID not already local, it writes the account to secure storage and records it in the recovery result.
6. If Brave Sync is configured, it independently calls `BraveSyncBackupService.discoverAssociatedAccounts`.
7. Valid Brave Sync accounts not already local are stored; duplicate IDs are ignored.
8. The result reports recovered accounts, invalid/skipped record IDs, relay event count, Brave record count, and Brave decryption-failure count.

The service accepts records from either source. A failure in the optional Brave discovery path is contained with `runCatching`; relay recovery can still return its valid results.

## Nostr candidate validation

A relay event becomes an associated account only when all checks below succeed:

1. `NostrEventCodec.isValid(event)` verifies event integrity/signature validity.
2. The event kind is exactly `37802`.
3. The event author is exactly the master account's derived Nostr public key.
4. There is exactly one usable `d` tag, and its value is non-blank decimal text.
5. The content decrypts successfully using NIP-44 self-decryption with the master account's derived Nostr keys.
6. The decrypted private-key payload is non-blank.

The resulting local record uses the numeric `d` tag as `createdAt` and the decrypted value as `zenTenKey`. A candidate that fails any check is skipped and its event ID is included in the skipped-result list.

> Implementation note: the direct Nostr recovery path currently requires a non-blank decrypted payload. Broader OpenSSH normalization/parse validation is enforced on backup writes and Brave Sync discovery. Production hardening should make validation consistent across all discovery paths before release.

## Brave Sync candidate validation

`BraveSyncBackupService.discoverAssociatedAccounts` considers each password record valid only when:

1. its username parses as a numeric account ID;
2. its URL is exactly `https://<accountId>.zenten/`;
3. its notes field is empty; and
4. its password normalizes and parses as a valid Ed25519 OpenSSH private key.

Valid records become local associated accounts using the username as `createdAt`. Invalid records are skipped and their record IDs are reported. Brave Sync decryption failures are counted separately.

The master Brave record uses `masterkey` and is checked for coverage, not restored as an associated account.

## Duplicate and conflict behavior

- Local account IDs form the authoritative duplicate set for a recovery pass.
- A discovered account is added only when its `createdAt` ID is not already local.
- If the same ID appears from both relays and Brave Sync, the first valid source added wins for that run; later duplicates are ignored.
- Recovery never overwrites an existing local associated account with remote material.
- Invalid remote records never alter local accounts.

## Secure persistence

Recovered associated accounts are stored through `SecureStorageService.addAccount`, which writes encrypted account records and appends the ID to the registry once. Recovery status/progress metadata belongs in non-secret app state; private keys and decrypted event contents must not be placed in logs, analytics, or DataStore status caches.

## UI behavior

The Accounts screen refreshes on resume and can show that recovery is in progress or report the recovery outcome. The feature currently has no user choice over which discovered accounts are added. The production UI should make automatic restoration and failures clear without revealing private-key material.

## Failure semantics

| Situation                                                                                | Behavior                                                                           |
| ---------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| No master account is available                                                           | Recovery fails; account operations should not proceed as if recovery succeeded.    |
| Relay query returns no matching valid records                                            | Recovery completes with no relay accounts.                                         |
| Relay event is malformed, invalid, wrong author/kind, cannot decrypt, or has unusable ID | Skip it and continue with other candidates.                                        |
| Brave record is malformed or key parsing fails                                           | Skip it and continue with other records.                                           |
| Brave discovery call fails in direct recovery                                            | Keep relay recovery results; Brave contributes no recovered accounts in that pass. |
| Same account exists locally                                                              | Do not overwrite or duplicate it.                                                  |

## Current limitations

- Automatic restoration has no approval/selection interface by design.
- The final recovery-progress, error, and accessibility experience needs end-to-end product validation.
- The current app has no associated-account deletion or vault-reset action.
- Recovery cannot recreate a lost master account private key or passphrase.
- Relay candidate private-key validation should be strengthened to match the strict normalization/parsing already used for Brave Sync candidates.

## Key source locations and tests

- Direct recovery: `app/src/main/java/app/zenten/vault/domain/RecoveryService.kt`
- Setup handoff: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
- Reconciliation recovery: `app/src/main/java/app/zenten/vault/domain/BackupSyncService.kt`
- Secure storage: `app/src/main/java/app/zenten/vault/data/secure/SecureStorageService.kt`
- Brave Sync validation: `app/src/main/java/app/zenten/vault/domain/BraveSyncBackupService.kt`
- UI progress: `app/src/main/java/app/zenten/vault/ui/accounts/`
- Relevant tests: `app/src/test/java/app/zenten/vault/domain/BackupRecoveryServiceTest.kt` and `app/src/test/java/app/zenten/vault/domain/AccountServiceTest.kt`.
