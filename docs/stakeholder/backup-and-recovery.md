# Backup and recovery

## What backup means in ZenTen Vault

After setup, ZenTen Vault attempts to keep protected copies of the master account material and each generated associated account at more than one configured backup destination. The purpose is to reduce dependence on one device or one remote service.

The app saves a newly generated associated account locally before it starts remote backup work. This means a user can see and use the account right away, while backup completion may still be in progress.

## What a user must retain

For recovery on another device, the user must retain both:

1. the private key for the master account used during setup; and
2. that account's passphrase.

The app does not replace these items with a username, email address, device PIN, biometric check, or customer-support process.

> If the user loses the master account private key or its passphrase, ZenTen Vault cannot use this recovery method to recreate them.

## Recovery expectation

With the same master account private key and passphrase, a user can establish the same vault identity on a new device. The app then looks for protected backup copies of associated accounts and automatically restores every valid account it discovers.

Recovery can be affected by network availability, backup-destination availability, configuration, and whether a backup copy was successfully created before the original device was lost. It must therefore be described as an attempt to discover and automatically restore available accounts, not as an unconditional guarantee.

## Understanding backup status

| Status | What it means | Suggested user action |
| --- | --- | --- |
| Healthy / present | The app has confirmed expected backup coverage. | No action needed; check periodically. |
| Partial | At least one backup copy is known, but another expected destination is not confirmed. | Review the affected destination and retry. |
| Missing | The app has not confirmed a backup copy at any expected destination. | Check connection/configuration and retry promptly. |
| Unknown | The app has no sufficiently recent information to make a conclusion. | Refresh status; do not assume this is a failure. |
| Repair failed | An attempt to restore missing coverage did not succeed. | Retry after resolving connection or destination issues. |

The proof-of-concept screen currently presents per-destination markers. The final product should pair them with clear text, last-checked time, and accessible descriptions.

## Current app limitation

The current app does not provide account deletion or vault-reset actions. Remote-backup retention and remote-deletion behavior are therefore not user-facing flows in the current release.

## Stakeholder decisions to confirm

- Which backup destinations are shown by name in user-facing material.
- The final wording for healthy, partial, missing, and unknown coverage.
- How long the app should retry and how users are notified of a problem.
- The support guidance shown when a user has lost master account recovery material.
