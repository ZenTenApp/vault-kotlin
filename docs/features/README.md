# Feature documentation

This directory describes each feature currently available in the ZenTen Vault Android proof of concept. It is the feature-level reference for product, design, engineering, QA, and stakeholder review.

## Features

1. [Device authentication](device-authentication.md)
2. [Vault Key setup](vault-key-setup.md)
3. [Associated account management](associated-account-management.md)
4. [Sensitive key access](sensitive-key-access.md)
5. [Encrypted backup](encrypted-backup.md) — detailed write contract, target behavior, and retries.
6. [Backup status and reconciliation](backup-status-and-reconciliation.md) — detailed discovery, coverage, and repair algorithm.
7. [Automatic account recovery](automatic-account-recovery.md) — detailed validation and automatic-restore behavior.
8. [Backup source management](backup-source-management.md) — currently hidden UI; retained custom-source domain capability.

## Documentation conventions

Each feature document identifies:

- the user outcome;
- the currently implemented behavior;
- important failure and security expectations;
- current limitations; and
- the primary implementation locations.

“Implemented” means source code is present in this repository. It is not a production-readiness or security-audit claim.
