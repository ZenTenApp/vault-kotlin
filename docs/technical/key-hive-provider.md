# KeyHive Associated-Account Provider

Vault exposes a signature-protected Android `ContentProvider` for trusted sibling applications. It returns only associated accounts, never the master account.

## Contract

- Authority: `app.zenten.vault.keys`
- Permission: `app.zenten.permission.VAULT_ACCESS` with `signature` protection
- List URI: `content://app.zenten.vault.keys/accounts`
- Item URI: `content://app.zenten.vault.keys/accounts/{createdAt}`
- Cursor columns: `format` and `data`
- Transport format: `encrypted_v1`

The list decrypts to canonical associated-account ID strings. An item decrypts to `{"createdAt":"1720000000000123","zentenKey":"..."}`.

The provider is read-only and runs in Vault's default process. It reads `SecureStorageService.getAccounts()`, whose registry contains associated accounts only. It never reads the master account.

## Security

Responses are AES-GCM encrypted with the KeyHive key derived by PBKDF2 from the installed application's signing-certificate fingerprint. Android's manifest permission blocks callers not signed with the trusted certificate before provider code executes. Consequently, every app signed with that certificate can read all shared associated SSH private keys. Passphrases, mnemonics, public keys, and Nostr key material are not serialized.

This boundary does not defend against an unlocked/rooted device or a compromised trusted sibling application.
