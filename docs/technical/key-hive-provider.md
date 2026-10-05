# KeyHive Associated-Account Provider

Vault exposes a signature-protected Android `ContentProvider` for trusted sibling applications. It returns only associated accounts, never the Vault Key.

## Contract

- Authority: `app.zenten.vault.keys`
- Permission: `app.zenten.permission.VAULT_ACCESS` with `signature` protection
- List URI: `content://app.zenten.vault.keys/accounts`
- Item URI: `content://app.zenten.vault.keys/accounts/{createdAt}`
- Cursor columns: `format` and `data`
- Transport format: `json_v1`

The list data is a JSON array of canonical associated-account ID strings. An item is `{"createdAt":"1720000000000123","zentenKey":"..."}`.

The provider is read-only and runs in Vault's default process. It reads `SecureStorageService.getAccounts()`, whose registry contains associated accounts only. It never reads the Vault Key.

## Security

Responses contain plaintext JSON over Binder. Android's manifest permission is the IPC confidentiality and authorization boundary: it blocks callers not signed with the trusted certificate before provider code executes. Application-layer encryption derived from the public signing certificate was removed because it did not add secrecy. Every app signed with that certificate can read all shared associated SSH private keys. The keys remain passphrase-protected OpenSSH containers; passphrases, mnemonics, public keys, and Nostr key material are not serialized.

This boundary does not defend against an unlocked/rooted device or a compromised trusted sibling application.
