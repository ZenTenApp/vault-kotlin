# Backup and recovery

## What backup means

ZenTen Vault stores protected copies of the Vault Key and generated associated accounts at five independent default sources: two Nostr relays and three Brave Sync-compatible servers. A newly generated associated account is always saved on the device first, so it remains visible and usable even if every remote write fails.

Remote work is sequential rather than simultaneous. One unavailable service does not stop the app from trying later sources.

## What users must retain

Recovery needs one of two *material* sets, and **always** the passphrase:

- the **Vault Key private key** the user supplied or exported at setup; or
- the 24 **Secret Combinations** written down during in-app generation.

| What the user still has | What the app can do |
| --- | --- |
| Private key + passphrase | Re-import the key directly on **Login with VaultKey**. No remote copy or Secret Combinations are needed. |
| Secret Combinations + passphrase | **Recover VaultKey** derives the recovery addresses from the 24 positions and reads the encrypted Vault Key back from an enabled relay or Brave Sync source. It requires at least one valid earlier backup copy. |
| Secret Combinations only (passphrase forgotten) | Nothing. The remote copies stay encrypted; the app makes no attempt to guess or reset the passphrase. |
| Private key only (passphrase forgotten) | Nothing. There is no second way into the stored key. |

So the private key is not strictly required — the Secret Combinations plus a remote copy substitute for it. The passphrase is strictly required: it is never stored, reset, or derivable by the app, and every path above needs it. The app cannot replace either with email, a device PIN, biometrics, or customer support. When both material sets are gone, the Vault Key cannot be recreated.

## Setup protection level

The app attempts all five sources. Initial setup completes when the Vault Key is confirmed on at least:

- one eligible Nostr relay; and
- one eligible Brave Sync server.

If that minimum is unavailable, the app keeps the validated Vault Key securely on the device and offers retry without making the user paste it again. It does not falsely mark setup complete.

## Recovery expectation

On a new device, the user either re-imports the same Vault Key and passphrase or recovers the Vault Key from the eight saved Secret Combinations plus its passphrase — the two rows of the [retention matrix](#what-users-must-retain) that lead to a working vault. **Recover VaultKey** derives the Vault Key's two recovery addresses from the 24 saved words and checks every enabled Nostr relay and Brave Sync source for the `vaultkey` record, accepting the first copy the passphrase decrypts. After the Vault Key is available, the app checks every source and automatically restores valid associated accounts that are absent locally. It completes all source checks/restoration before copying local accounts into confirmed remote gaps.

Recovery availability depends on network and service availability and whether at least one valid copy was previously created. It is not an unconditional guarantee.

## Understanding source status

Status appears per source in associated-account details and on a dedicated Vault Key screen. There is no global Backup Status screen.

| Status | Meaning | Suggested action |
| --- | --- | --- |
| Backed up | The source acknowledged the write. The app may separately perform an internal read-back without presenting the backup as incomplete. | None. |
| Missing | A successful check did not find this account ID. | Retry/consolidate. |
| Checking / Backing up | Work is currently in progress. | Wait. |
| Backup failed | The latest write failed. | Check connectivity and retry. |
| Unknown | The source could not be checked conclusively. | Refresh; do not assume the copy is absent. |

Consolidation is enqueued after each passphrase-unlocked launch, subject to network availability, and avoids unnecessary rechecks when fresh and no writes are pending. It requires the in-memory VaultKey passphrase, so it does not run while the app is locked. General consolidation processes associated accounts only: it uses the Vault Key Nostr identity on relays and the Vault Key backup seed on Brave Sync. Vault Key consolidation is separate and uses the `vkbckp` (Vault Key BacKup)-derived Nostr identity on relays plus the same Vault Key backup seed on Brave Sync. Such consolidation only repairs an already-set-up device; a lost Vault Key is regained through **Recover VaultKey** using the saved Secret Combinations and passphrase, which is the only path that reads those relay and Brave copies back into the app.

## Privacy expectations

Operational status records contain source/account identifiers, state, coarse error categories, and timestamps—not private keys, passphrases, backup payloads, sync codes, authentication tokens, or relay challenges. User-facing and support diagnostics must preserve this boundary.

Profile publication is separate from backup and is deliberately public. Once the mandatory creation export is confirmed, the app publishes the account name as the public kind-0 `display_name`, the creation ID as its `name`, and a kind-10050 NIP-17 DM relay list to seven public Nostr relays. Relays and other clients can copy or re-publish these events, so the app cannot delete them or guarantee they are forgotten. The creation screen intentionally does not repeat this warning, to keep the flow minimal; see [Security model](../technical/security-model.md) for the exact fields and relay set.

## Current limitations

- Default sources cannot be removed or disabled.
- The current app exposes no custom backup-source management UI. The underlying source configuration and processing capability is retained for future use.
- The app does not currently provide account deletion or vault reset; remote deletion/retention is therefore not a user-facing flow.
- Remote backup cannot recover lost Vault Key material on its own. A remote copy only helps when the user still holds the Secret Combinations and the passphrase; without those, the stored copy is unreadable.
