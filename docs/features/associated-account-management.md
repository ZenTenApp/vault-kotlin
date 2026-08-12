# Associated account management

## User outcome

After setup has satisfied the master backup quorum, a user can generate a separate Ed25519 SSH account for another service, project, or environment and find it in **My Accounts**.

## Current behavior

1. From **My Accounts**, the user selects the add button or **Add Account**.
2. The app displays **Create new SSH account** and explains that a fresh Ed25519 OpenSSH key pair will be generated.
3. The user selects **Create account**.
4. The app verifies that master setup is complete, meaning its required backup quorum was satisfied.
5. The app allocates a unique creation ID, generates the key pair encrypted with the master account passphrase, and saves the associated account locally.
6. Backup work starts after the local save.
7. The account appears in **My Accounts**. Selecting it opens its public-key details.

Associated accounts are displayed with their creation ID. The master account remains an internal recovery identity and does not appear in the routine account list.

## Failure and security expectations

- Creation is rejected before local persistence when master backup quorum has not been satisfied; no associated account or backup job is created.
- Once master quorum is satisfied, a newly created associated account remains locally available even when that account's remote backup later needs retrying.
- Creation requires the retained master-account passphrase; the generated OpenSSH private key is encrypted with that same passphrase.
- Creation errors are shown without disclosing secret key material.
- The account list refreshes when the app returns to the foreground, including after recovery work.

## Current limitations

- Associated accounts cannot be renamed, rotated, deleted, or manually restored one at a time.
- The app has no vault-reset action.
- The creation screen includes a cancel action before creation starts; it does not remove an already-created account.

## Primary implementation locations

- UI: `app/src/main/java/app/zenten/vault/ui/accounts/` and `ui/newaccount/`
- Account lifecycle: `app/src/main/java/app/zenten/vault/domain/AccountService.kt`
- Account model: `app/src/main/java/app/zenten/vault/data/model/Account.kt`
- Setup/backup handoff: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
