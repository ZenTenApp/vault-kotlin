# Associated account management

## User outcome

After setup has satisfied the master backup quorum, a user can generate a separate Ed25519 SSH account for another service, project, or environment and find it in **My Accounts**.

## Current behavior

1. From **My Accounts**, the user selects the add floating action button.
2. The app displays **Create new SSH account** and explains that a fresh Ed25519 OpenSSH key pair will be generated.
3. The user selects **Create account**.
4. The app verifies that master setup is complete, meaning its required backup quorum was satisfied.
5. The app allocates a unique creation ID, generates the key pair encrypted with the master account passphrase, and saves the associated account locally.
6. Targeted consolidation starts after the local save. It reconciles only the new account, indexes every source, and writes only where its canonical ID is absent; it never blindly pushes another entry to a source that already has the account.
7. The account appears in **My Accounts**. Selecting it opens its public-key details.

The screen has no title or header section: it presents account cards plus two bottom-right actions. The extended **Master account** action opens master backup details and sits above the add floating action button.

Associated accounts use compact, credit-card-inspired cards and are listed alphabetically by their displayed creation ID. Each card shows the 16-digit creation ID in groups of four, a shortened SSH fingerprint (first four and last four characters), and a localized date/time decoded from the microsecond Unix creation ID. A stable dark card color is selected from an accessible palette using the account ID as the palette index; this gives the same account the same color across renders without treating color as account state. Selecting anywhere on a card opens its public-key details. The master account remains an internal recovery identity and does not appear in the routine account list.

## Failure and security expectations

- Creation is rejected before local persistence when master backup quorum has not been satisfied; no associated account or backup job is created.
- Once master quorum is satisfied, a newly created associated account remains locally available even when remote consolidation must run again later.
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
