# Backup and recovery

## What backup means

ZenTen Vault stores protected copies of the master and generated associated accounts at four independent default sources: two Nostr relays and two Brave Sync-compatible servers. A newly generated associated account is always saved on the device first, so it remains visible and usable even if every remote write fails.

Remote work is sequential rather than simultaneous. One unavailable service does not stop the app from trying later sources.

## What users must retain

Recovery requires both:

1. the master account private key used during setup; and
2. its passphrase.

The app cannot replace these with email, a device PIN, biometrics, or customer support. Losing either means this recovery method cannot recreate the vault.

## Setup protection level

The app attempts all four sources. Initial setup completes when the master is confirmed on at least:

- one eligible Nostr relay; and
- one eligible Brave Sync server.

If that minimum is unavailable, the app keeps the validated master securely on the device and offers retry without making the user paste it again. It does not falsely mark setup complete.

## Recovery expectation

On a new device, the user imports the same master key and passphrase. The app checks every source and automatically restores valid associated accounts that are absent locally. It completes all source checks/restoration before copying local accounts into confirmed remote gaps.

Recovery availability depends on network and service availability and whether at least one valid copy was previously created. It is not an unconditional guarantee.

## Understanding source status

Status appears per source in associated-account details and on a dedicated Master Account screen. There is no global Backup Status screen.

| Status | Meaning | Suggested action |
| --- | --- | --- |
| Backed up | The source acknowledged the write. The app may separately perform an internal read-back without presenting the backup as incomplete. | None. |
| Missing | A successful check did not find this account ID. | Retry/consolidate. |
| Checking / Backing up | Work is currently in progress. | Wait. |
| Backup failed | The latest write failed. | Check connectivity and retry. |
| Unknown | The source could not be checked conclusively. | Refresh; do not assume the copy is absent. |

Background consolidation runs hourly, subject to network availability, and avoids unnecessary rechecks when fresh and no writes are pending.

## Privacy expectations

Operational status records contain source/account identifiers, state, coarse error categories, and timestamps—not private keys, passphrases, backup payloads, sync codes, authentication tokens, or relay challenges. User-facing and support diagnostics must preserve this boundary.

## Current limitations

- Default sources cannot be removed or disabled.
- Before setup, users may add up to three custom Nostr relays. This optional screen does not support custom Brave sources, editing, disabling, removal, reordering, connectivity checks, or post-setup access.
- Custom relay quorum and trusted-recovery participation are off by default and can be enabled in the add form's advanced settings.
- The app does not currently provide account deletion or vault reset; remote deletion/retention is therefore not a user-facing flow.
- Remote backup cannot recover lost master material.
