# KeyHive associated-account provider

This document explains the KeyHive integration layer: what it is, how the provider exposes associated accounts to trusted sibling applications, and how Android process lifecycle affects those requests.

## What KeyHive is

KeyHive is the integration layer that lets the Vault app share associated-account data with *other* trusted applications installed on the same device. It is implemented as an Android **Content Provider**, not a service or an in-process API.

Primary source locations:

- `app/src/main/java/app/zenten/vault/keyhive/AssociatedAccountsProvider.kt`
- `app/src/main/java/app/zenten/vault/keyhive/SharedAssociatedAccount.kt`
- Manifest declaration in `app/src/main/AndroidManifest.xml`

## How it works

KeyHive reuses standard Android inter-process communication (IPC) with strict access controls.

- **Content Provider:** `AssociatedAccountsProvider` extends Android's `ContentProvider`. It exposes the authority `app.zenten.vault.keys`.
- **Signature-level permission:** The provider is protected by the read permission `app.zenten.permission.VAULT_ACCESS`, declared with `android:readPermission`. Only applications signed with the same developer certificate as the Vault app can hold this permission. It is not open to arbitrary apps on the device.
- **Read-only:** The provider blocks writes. `insert` returns `null`, and `update`/`delete` return `0`. Only `query` is supported.
- **Internal data access:** On creation the provider resolves its dependency through Hilt (`EntryPointAccessors.fromApplication(...)`) to obtain the app's `SecureStorageService`, then reads encrypted account records from secure storage when a query arrives.

Manifest declaration:

```xml
<provider
    android:name=".keyhive.AssociatedAccountsProvider"
    android:authorities="app.zenten.vault.keys"
    android:exported="true"
    android:grantUriPermissions="false"
    android:readPermission="app.zenten.permission.VAULT_ACCESS" />
```

## How sibling apps get the accounts

Sibling apps query the provider through Android's `ContentResolver`:

1. **Request:** The sibling app calls `ContentResolver.query(...)` against the provider authority.
2. **Endpoints:** The provider matches two URIs with `UriMatcher`:
   - `content://app.zenten.vault.keys/accounts` — returns a JSON array of account creation timestamps.
   - `content://app.zenten.vault.keys/accounts/{id}` — returns one account's details as JSON. The ID must be a positive integer that round-trips exactly as its string form.
3. **Format:** Results are returned as a `MatrixCursor` with the columns `format` and `data`. The format identifier is `json_v1`, and the JSON payload is in the `data` column.
4. **Payload shape:** The JSON is a `SharedAssociatedAccount` object containing only:
   - `createdAt` — the account creation timestamp.
   - `zentenKey` — the passphrase-encrypted OpenSSH private key (the same key material used in manual export).
   - `nostrPubkey` — the account's public Nostr identity.

`SharedAssociatedAccount` is deliberately the only record shape exposed to sibling apps. It excludes the passphrase (which is no longer a stored field on `Account`) and the Nostr private key. Only the encrypted key material and public identity cross the boundary; sibling apps must obtain the passphrase from the user.

Queries with a `selection`, `selectionArgs`, or `sortOrder` are rejected (return `null`), as are malformed IDs and unmatched URIs. Any exception during a query is caught and surfaced as `null` rather than leaking internal state.

## Process and lifecycle behavior

The Vault app does **not** need to be in the foreground for a sibling app to read accounts, but its process must be alive to serve the request.

When a sibling app queries the provider, Android routes the call across process boundaries:

1. The sibling app calls `ContentResolver.query(...)`.
2. Android resolves the registered authority (`app.zenten.vault.keys`), recorded at install time.
3. Android forwards the request to the Vault app's process over Binder IPC.
4. The provider's `query` runs on a **Binder thread** (a framework-managed background thread), not the main UI thread, and reads from `SecureStorageService`.

Key consequences:

- **Background but alive:** If the Vault process is still resident, the request is served normally.
- **Process killed:** If the OS has killed the Vault process, Android may or may not restart it to answer the query. Historically the framework would start the provider's process, run `onCreate`, serve the query, and possibly tear it down again. On modern Android, and especially with OEM background-launch restrictions (for example vendor "connect and launch" settings), this is not guaranteed. When the process is not restarted, the query returns `null` or empty results.
- **No foreground requirement:** The user does not need to have the Vault app open. The requirement is process residency, not visibility.

Because the provider contract is best-effort with respect to process state, sibling apps must treat a `null` or empty result as a normal outcome and should recover gracefully, for example by prompting the user to open the Vault app to bring its process back up before retrying.

The device-authentication gate applies to interactive UI only. The signature-protected KeyHive provider retains its own contract and is not gated behind `AppUnlockCoordinator`; see [Architecture overview](architecture.md#device-authentication-gate).

## Security boundaries

- Access is limited to same-signature apps through `app.zenten.permission.VAULT_ACCESS`; `grantUriPermissions` is disabled so the permission cannot be delegated per-URI.
- The provider is read-only.
- Only `SharedAssociatedAccount` is serialized: encrypted private key plus public Nostr key. The vault passphrase and Nostr private key are never exposed.
- The provider runs regardless of the interactive unlock gate, so its permission boundary, not the UI lock, is the security control for sibling access.

## Accepted decision (D5)

Same-signature unattended access is an **accepted product requirement**, not an oversight:

- Every application signed with the Vault release certificate is inside the KeyHive trust boundary. It may read each account's encrypted OpenSSH container and public Nostr key at any time, with no device authentication and no Vault Key passphrase.
- The protection is the encrypted container itself: a sibling app still needs the user's Vault Key passphrase to use the private key, and it never receives the passphrase or the Nostr private key from the provider.
- A compromised trusted sibling app, a leaked or stolen signing key, or a hostile app added under the same certificate reads this data. That is outside the protection boundary by design rather than by accident.
- Review topic 8 in the [security model](security-model.md#required-production-review-topics) — a separate authentication-bound Keystore domain for interactive private-key disclosure — is **closed as not planned** while KeyHive access must remain unattended.
- Consequence for release process: production signing-key custody and sibling-app review are security controls for this boundary, not merely release hygiene. Adding an app to the trusted certificate set is a security change.

## Key references

- `app/src/main/java/app/zenten/vault/keyhive/AssociatedAccountsProvider.kt`
- `app/src/main/java/app/zenten/vault/keyhive/SharedAssociatedAccount.kt`
- `app/src/main/java/app/zenten/vault/di/KeyHiveProviderEntryPoint.kt`
- `app/src/main/AndroidManifest.xml`
