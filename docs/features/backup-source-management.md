# Backup source management

## Current behavior

ZenTen Vault currently uses its built-in backup sources only. The app exposes no screen, navigation route, or onboarding action for inspecting, adding, or otherwise managing custom backup sources.

## Retained capability

The source-oriented domain architecture remains in place for a future management UI. `BackupSourceRepository` continues to persist custom configurations, normalize and reject duplicate endpoints, and support custom Nostr and Brave Sync sources. The backup and recovery engines continue to process configured custom sources when present in persisted storage.

## Limitations

- Custom backup sources cannot be added or managed through the current app UI.
- A future UI must define its own source limits and management policy; no three-relay limit exists in the domain repository.

## Primary implementation locations

- Source policy/persistence: `domain/BackupSources.kt`, `domain/BackupSourceRepository.kt`
- Source processing: `domain/BackupSourceAdapters.kt`, backup and recovery engines
