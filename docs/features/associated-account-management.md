# Associated account management

## User outcome

After setup has satisfied the master backup quorum, a user can generate a separate Ed25519 SSH account for another service, project, or environment and find it in **My Accounts**.

## Current behavior

1. From **Accounts**, the user selects the bottom-centered **Create New Account** button.
2. The app displays **Create New Account** with a **MasterKey Passphrase** field. The user can show or hide the entered passphrase and selects **Verify Passphrase**.
3. After the app verifies the passphrase against the securely retained master-account passphrase, it asks for a mandatory **Account Name** containing 8 to 128 characters after surrounding whitespace is removed.
4. The user enters the account name and selects **Create Account**, or selects the outlined **Cancel** action to return without creating one. During key derivation, the primary button displays a spinner and **Creating Account** while the cryptographic work runs off the UI thread.
5. The app re-verifies the passphrase, rejects the request when 99 associated accounts already exist, then verifies that master setup is complete, meaning its required backup quorum was satisfied.
6. The app allocates a unique creation ID, generates the key pair encrypted with the verified master account passphrase, derives its Nostr key pair from that SSH key, and saves both identities and the account name locally.
7. Targeted consolidation starts after the local save. It reconciles only the new account, indexes every source, and writes only where its canonical ID is absent; it never blindly pushes another entry to a source that already has the account.
8. Before the account can be used, the app requires the user to export the encrypted OpenSSH private key through the Android Sharesheet.
9. After the user confirms that the encrypted key was saved, the app clears the pending export and queues durable background publication of a kind `0` Nostr profile. The profile sets `name` to the account creation ID and `display_name` to the mandatory account name; `banner`, `picture`, and `about` are published as blank. Publication does not delay completion and retries with backoff until all configured profile relays (`wss://user.kindpag.es`, `wss://purplepag.es`, `wss://directory.yabu.me`, `wss://profiles.nostr1.com`) acknowledge it. The same relay set is queried when fetching kind `0` profiles.
10. The account appears in **Accounts**. Selecting it opens its identity and backup-status details.

The landing screen has a centered **Accounts** title and no Back action because it is the app's main destination. A settings cog at the top right opens **Settings**, where the **Master account** item opens master backup details. A labeled **Create New Account** button stays centered at the bottom over a subtle gradient and shadow so the primary action remains prominent without obscuring the account list.

Associated accounts use neutral cards and are listed newest first by their creation ID. Accounts are grouped by creation month, marked by a centered month label between horizontal separators. Each list card and its account-detail identity card show the latest nonblank kind `0` `display_name`, falling back first to the locally persisted account name and then to the shortened NIP-19 identity (`npub...` plus the final four characters). Display and account names use the available row width and are ellipsized only when they exceed it. The card then shows the nearly full-width 16-digit creation ID in groups of four and a local-time timestamp such as `Created on Wed Sep 2, 2026 11:56 AM`. Below the identity card, the detail screen shows the account's full locally derived NIP-19 `npub` in a dedicated card with a copy action. Selecting anywhere on a list card opens the account backup-status details. The master account remains an internal recovery identity and does not appear in the routine account list.

## Failure and security expectations

- Creation is rejected before local persistence when master backup quorum has not been satisfied; no associated account or backup job is created.
- A user can create at most 99 associated accounts. The 100th creation attempt is rejected with a clear message before any key material is generated or persisted. This ceiling applies only to user-initiated creation; accounts restored by consolidation or recovery are not counted against or blocked by it.
- Once master quorum is satisfied, a newly created associated account remains locally available even when remote consolidation must run again later.
- Creation requires the user to re-enter the master-account passphrase. The app compares it with the securely retained master passphrase before persistence, and encrypts the generated OpenSSH private key with that same passphrase.
- Creation requires an account name whose trimmed length is between 8 and 128 characters. The same validated value is persisted and published publicly as the Nostr `display_name`.
- Creation errors are shown without disclosing secret key material.
- Every persisted associated account includes its derived Nostr key pair. The account signs its own kind `0` profile, and its private key is not sent to relays.
- Profile presentation data is public and cached separately from encrypted account secrets. Cards retain the locally derived shortened `npub` as a reliable fallback when relay retrieval fails or `display_name` is missing.
- Copying the public `npub` places it on the system clipboard; no private or SSH key material is copied.
- Account details do not expose SSH public keys, fingerprints, or private keys. The required encrypted private-key export happens only during account creation.
- The account list refreshes when the app returns to the foreground, including after recovery work.
- Profile retrieval is shared by the list and detail screens. Each relay connection receives bounded filters containing all requested account public keys, cached values render first, and each relay response updates cards independently. Overlapping list/detail requests reuse the active fetch, while later account-list refreshes retry previous relay failures.
- The mandatory local-backup screen blocks Back navigation and completes only after the Sharesheet has opened and the user confirms that the encrypted key was saved.
- The Sharesheet text contains the passphrase-encrypted OpenSSH private key. It remains sensitive: share recipients or anyone who learns the retained passphrase may be able to use the account.

## Current limitations

- Associated accounts cannot be renamed, rotated, deleted, or manually restored one at a time.
- The app has no vault-reset action.
- The creation screen includes a cancel action before creation starts; it does not remove an already-created account.
- The app persists a pending account ID before entering the backup sequence, then resumes the required export step after an app restart. It records only account IDs and cannot verify the durability, privacy, or future availability of a shared copy.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/accounts/` and `ui/newaccount/`
- Account lifecycle: `app/src/main/java/app/zenten/vault/domain/AccountService.kt`
- Account model: `app/src/main/java/app/zenten/vault/data/model/Account.kt`
- Setup/backup handoff: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
