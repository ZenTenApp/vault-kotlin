# ZenTen Vault stakeholder guide

This guide is for client representatives, product owners, designers, delivery teams, and other stakeholders. It explains what the app is intended to do, how people move through key flows, and the expectations and limitations that should be agreed before release.

It is intentionally more accessible than the technical documentation, but it is not a marketing document: recovery and backup limitations are stated plainly.

## Read in this order

1. [Product overview](product-overview.md) — purpose, audience, and key concepts.
2. [App flowcharts](flowcharts.md) — visual maps of navigation, setup, account creation, backup, and recovery.
3. [User journeys](user-journeys.md) — first-time setup, account creation, viewing a key, backup checks, and automatic recovery.
4. [Screens and states](screens-and-states.md) — current screen map and important loading, error, and empty states.
5. [Backup and recovery](backup-and-recovery.md) — what users can expect and what they must retain.
6. [Scope and roadmap](scope-and-roadmap.md) — implemented, partial, planned, and excluded capabilities.

## Terminology used here

- **Master account:** the existing SSH account that the user supplies during setup. Its private key and passphrase are the root of recovery.
- **Associated account:** a new SSH account generated and managed by ZenTen Vault. Technical material may call this a *associated account*.
- **Backup destination:** a configured remote place where the app attempts to keep an encrypted backup copy.
- **Backup status:** the app's latest view of whether backup copies are available; it is not a promise that a remote service will always be reachable.

## Review approach

For every journey, confirm the user goal, the wording, the decisions the user must make, and the failure/recovery expectation. Mark proposals separately from approved release behavior.
