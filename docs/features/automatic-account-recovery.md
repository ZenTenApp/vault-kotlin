# Automatic account recovery

## Purpose

After the user imports or recovers the same Vault Key, source consolidation restores every valid remotely backed-up associated account that is absent locally. The Vault Key is not restored by this consolidation flow: it re-enters the app through **Recover VaultKey**, which uses the user's Secret Combinations and passphrase to read the separate `vaultkey` record from enabled relays and Brave Sync sources.

## Trigger and ordering

A successful Vault Key-quorum setup schedules immediate consolidation. Consolidation also runs once after each unlocked app launch (the passphrase gate enqueues it) and can be invoked manually; there is no periodic schedule. It requires the in-memory VaultKey passphrase, so it cannot run while the passphrase gate is locked. `AccountConsolidationEngine` serializes all remote work and completes its restore phase across all sources before starting any backup repair.

This account cycle is scheduled independently of the Vault Key cycle, which has its own post-unlock worker. Consolidation never restores the Vault Key remotely; only the separate **Recover VaultKey** flow reads a remote Vault Key copy back, using the Secret Combinations and passphrase.

For each enabled source in stable order:

1. Index canonical IDs, retaining the associated-account ones; the Vault Key ID belongs to the separate Vault Key flow.
2. Compare IDs with current local secure storage.
3. For IDs already local, do not decrypt or compare payloads.
4. For each associated ID absent locally on a trusted restore-enabled source, try duplicate candidates newest-first.
5. Decrypt and validate candidates only until one produces a valid passphrase-protected Ed25519 OpenSSH account.
6. Save a valid account immediately. Later sources see it as local and do not overwrite it.

A failed source does not prevent later sources from restoring accounts. Invalid records are skipped. Recovery never overwrites an existing local account.

Secure account storage publishes account-list changes within the app process. When background consolidation restores an account after the **Accounts** screen is already visible, the list updates immediately without requiring an app refresh or foreground transition.

## Nostr validation

A Nostr candidate is eligible only when:

- the event signature is valid;
- kind is `37802`;
- author matches the Vault Key-derived Nostr public key;
- the single `d` tag is a decimal associated-account ID;
- NIP-44 self-decryption succeeds; and
- the decrypted value normalizes and parses as an Ed25519 OpenSSH private key with the retained Vault Key passphrase.

Source reads use NIP-42 authentication on the same connection when a relay challenges the client.

## Brave Sync validation

A Brave candidate is eligible only when:

- username is a decimal account ID;
- URL is exactly `https://<accountId>.zenten/`;
- notes are empty; and
- password normalizes and parses as an Ed25519 OpenSSH private key with the retained Vault Key passphrase.

Each Brave-compatible server is queried independently with the Vault Key-derived sync code.

## Duplicate behavior

Presence is ID-only. Duplicate remote candidates for a missing ID are sorted newest-first and attempted defensively until one validates. Once an account is restored locally, candidates for the same ID on later sources are not decrypted. Local records are authoritative and never replaced by remote payload differences.

## Security and persistence

Recovered accounts are written only through `SecureStorageService`. Status, pending writes, and checkpoints store IDs and coarse operational metadata only. Decrypted payloads, passphrases, sync codes, auth tokens, and NIP-42 challenges must not be logged or persisted outside secure account storage.

## Failure semantics

| Situation | Behavior |
| --- | --- |
| No local Vault Key | Consolidation does not run. |
| Source index fails | Mark source status unknown and continue. |
| No matching valid record | Restore nothing and continue. |
| Newest duplicate is invalid | Try the next candidate for that ID. |
| Same ID already exists locally | Do not decrypt, overwrite, or duplicate it. |
| One source fails | Continue sequentially with remaining sources. |

## Key locations and tests

- Orchestration: `domain/AccountConsolidationEngine.kt`
- Source adapters/validation: `domain/BackupSourceAdapters.kt`
- Secure storage: `data/secure/SecureStorageService.kt`
- Reactive account list: `ui/accounts/AccountsViewModel.kt`
- Core sequencing tests: `domain/AccountConsolidationEngineTest.kt`
