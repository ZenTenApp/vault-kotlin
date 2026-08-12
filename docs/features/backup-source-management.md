# Backup source management

## User outcome

Before importing a master account, a user can inspect ZenTen Vault's configured backup sources and optionally add compatible Nostr relays.

## Current behavior

The **Get started** screen keeps **Import SSH Key** as its primary action and provides **Manage backup sources** as an optional secondary action. Source review is not required to continue setup.

The **Backup sources** screen lists sources in processing order. The four built-in sources appear first with their complete endpoints and are labeled as managed by ZenTen Vault. They cannot be edited, disabled, removed, or reordered.

A user can add up to three custom Nostr relays. The add form accepts:

- an optional display name, falling back to the normalized endpoint hostname;
- a read-only **Nostr relay** source type; and
- a secure WebSocket (`wss://`) endpoint.

Endpoints cannot contain credentials, a query, or a fragment. Normalized duplicate endpoints are rejected. Successful creation returns to the updated source list; errors appear in a snackbar.

## Advanced policy

Advanced settings are collapsed by default. New relays are enabled, do not count toward setup quorum, and are not used for account recovery unless selected.

- **Count toward setup quorum** allows the relay to satisfy the Nostr side of master setup quorum. An eligible Brave Sync source must still succeed.
- **Use for account recovery** enables discovery and trust together, allowing valid associated-account records from that relay to be restored locally.

## Limitations

- Source management is available only before setup; a future Settings entry is not implemented.
- Only custom Nostr relays can be added through the UI. Built-in Brave Sync sources remain visible, while the domain's custom Brave capability is not exposed.
- Custom sources cannot currently be edited, disabled, removed, or reordered.
- The screen does not test connectivity, show source health, or trigger network work itself.
- The three-relay maximum is enforced by this UI flow, not as a general repository invariant.

## Primary implementation locations

- UI and state: `app/src/main/java/app/zenten/vault/ui/sources/`
- Entry/navigation: `ui/onboarding/GetStartedScreen.kt`, `ui/VaultNavGraph.kt`
- Source policy/persistence: `domain/BackupSources.kt`, `domain/BackupSourceRepository.kt`
