# Feature documentation

This directory describes each feature currently available in the ZenTen Vault Android proof of concept. It is the feature-level reference for product, design, engineering, QA, and stakeholder review.

## Features

1. [Master account setup](master-account-setup.md)
2. [Associated account management](associated-account-management.md)
3. [Sensitive key access](sensitive-key-access.md)
4. [Encrypted backup](encrypted-backup.md) — detailed write contract, target behavior, and retries.
5. [Backup status and reconciliation](backup-status-and-reconciliation.md) — detailed discovery, coverage, and repair algorithm.
6. [Automatic account recovery](automatic-account-recovery.md) — detailed validation and automatic-restore behavior.

## Documentation conventions

Each feature document identifies:

- the user outcome;
- the currently implemented behavior;
- important failure and security expectations;
- current limitations; and
- the primary implementation locations.

“Implemented” means source code is present in this repository. It is not a production-readiness or security-audit claim.
