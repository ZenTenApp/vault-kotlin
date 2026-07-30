# Brave Sync Passwords — Kotlin Implementation Plan

## Goal

Add a Kotlin/Android implementation of the **password-only** portion of
[`../brave-sync-ts`](../brave-sync-ts), enabling ZenTen Vault to read, create,
update, and delete Brave Sync password entries.

The app already has the prerequisite identity flow:

- SSH key + passphrase → 24-word mnemonic (`Seedify`)
- 24-word mnemonic → 25-word Brave Sync code (`Seedify.toBraveSyncCode`)
- OkHttp, BouncyCastle, Coroutines, Hilt, and encrypted storage are available

The implementation will intentionally exclude every non-password Brave Sync
feature, including bookmarks, Authenticator.cc/2FA, CLI support, generic Sync
entity support, and seed-shard orchestration.

---

## 1. Kotlin Module Boundary

Create an isolated Brave Sync package, separate from the app's Nostr backup
implementation:

```text
app.zenten.vault.bravesync/
├── BraveSyncClient.kt              # High-level connection lifecycle
├── BraveSyncConfig.kt              # Endpoint and service-key constants
├── auth/
│   └── BraveSyncAuth.kt            # Mnemonic entropy → HKDF → Ed25519 → auth token
├── crypto/
│   └── NigoriCrypto.kt             # KDFs, AES-CBC, HMAC, Nigori key name
├── proto/                          # Reduced vendored protobuf schemas/generated types
├── transport/
│   └── BraveSyncTransport.kt       # OkHttp protobuf POST and gzip handling
├── password/
│   ├── PasswordModels.kt
│   ├── PasswordEntityCodec.kt      # Create/update/delete Sync entities
│   ├── PasswordUniqueTag.kt
│   └── PasswordRepository.kt       # Fetch/decrypt/map password entries
└── dns/                            # Deferred DNS fallback, if required
```

Expose only a small app-facing abstraction:

```kotlin
interface BravePasswordService {
    suspend fun connect(syncCode: String): Result<Unit>
    suspend fun listPasswords(): Result<List<PasswordEntry>>
    suspend fun createPassword(input: NewPasswordEntry): Result<PasswordEntry>
    suspend fun updatePassword(entry: PasswordEntry, update: PasswordUpdate): Result<Unit>
    suspend fun deletePassword(entry: PasswordEntry): Result<Unit>
}
```

UI and domain code must depend on this interface rather than protobuf,
transport, or Nigori implementation details.

---

## 2. Required TypeScript Features to Port

Port only the dependencies necessary for password synchronization.

| TypeScript source | Kotlin responsibility |
| --- | --- |
| `crypto/bip39.ts` | Validate 24/25 words and obtain the 32-byte entropy from the first 24 words. |
| `crypto/ed25519.ts` | Brave HKDF-SHA512 auth-key derivation, Ed25519 public key, and bearer-token generation. |
| `crypto/nigori.ts` | PBKDF2/scrypt, AES-128-CBC, PKCS#7, HMAC-SHA256, encryption/decryption, and key-name calculation. |
| `sync/client.ts` | Protobuf request/response transport, headers, gzip, get-updates, and commit requests. |
| `sync/nigori-init.ts` | Create a Nigori entity when a chain has not yet been initialized. |
| `password/read.ts` | Fetch password datatype entities, decrypt, and map entries. |
| `password/entity.ts` | Build Brave-compatible create/update/delete entities. |
| `password/unique-tag.ts` | Chromium-compatible escaping, unique tags, and client-tag hash. |

Do not port:

- bookmark support;
- Authenticator.cc/2FA support;
- CLI code;
- generic Sync entity support;
- seed-shard orchestrator;
- Node-specific DNS and HTTPS code.

---

## 3. Protocol Compatibility Requirements

The Kotlin implementation must match `brave-sync-ts` and Brave/Chromium wire
behavior exactly:

- Default server: `https://sync-v2.brave.com/v2`
- Password datatype ID: `45873`
- Nigori datatype ID: `47745`
- Use only the first 24 words of a 24/25-word Brave Sync code for crypto
  derivation.
- Derive the Sync authentication key with Brave's exact HKDF-SHA512 salt and
  `sync-auth-key` info string.
- Use a fresh timestamp-based bearer token for Sync requests.
- Support both PBKDF2 and scrypt Nigori configurations returned by the server.
- Use AES-128-CBC with manual PKCS#7 padding and HMAC-SHA256 over ciphertext
  only, matching Nigori.
- Preserve Chromium's exact password unique-tag escaping and SHA-1
  client-tag-hash construction.
- Write both `encrypted` and `encrypted_notes_backup` fields so Brave displays
  password notes correctly.
- Retain server-provided entity ID and version for updates and deletes.

---

## 4. Minimal Protobuf Support

`brave-sync-ts` generates the whole Chromium Sync schema. Kotlin should vendor
only the reduced schema required by password synchronization and compile it
with the protobuf Gradle plugin.

Required message families:

- `ClientToServerMessage` / `ClientToServerResponse`
- `GetUpdatesMessage`, `CommitMessage`, `DataTypeProgressMarker`
- `SyncEntity`
- `EntitySpecifics` with the `password` and `nigori` alternatives
- `PasswordSpecifics`, `PasswordSpecificsData`, and password notes
- `NigoriSpecifics`, `NigoriKey`, `NigoriKeyBag`
- `EncryptedData`

The schema must be copied or reduced from:

```text
../brave-sync-ts/schema/protobuf/sync_pb/
```

Before committing generated Kotlin code, verify all field numbers and defaults
against the TypeScript schema. Hand-written protobuf wire encoding is out of
scope because it creates unnecessary compatibility risk.

---

## 5. Crypto and Serialization Parity Gate

No live Sync implementation should proceed until deterministic compatibility
tests pass against vectors generated with `brave-sync-ts`.

Add tests for:

1. Valid 24- and 25-word code parsing and first-24 extraction.
2. Brave auth-key derivation, public key, and client ID.
3. Bearer-token format using an injected fixed timestamp.
4. PBKDF2 Nigori encryption and MAC keys.
5. Scrypt Nigori encryption and MAC keys.
6. Nigori key-name computation.
7. Nigori encrypt/decrypt fixtures with a fixed IV in test-only code.
8. Password `escapePath`, unique tag, and client-tag hash.
9. Serialized `PasswordSpecificsData` bytes.
10. Serialized create/update/delete entities, excluding deliberately random
    values unless they are injected.

Inject clocks, UUID generation, and secure random IV generation behind small
interfaces so tests are deterministic while production remains secure.

---

## 6. Connection Lifecycle

`BraveSyncClient.connect(syncCode)` will:

1. Validate the supplied code and extract its first 24 words.
2. Convert the BIP39 mnemonic to its 32-byte entropy.
3. Derive Brave's Ed25519 authentication keypair via HKDF-SHA512.
4. Compute the hexadecimal client ID and timestamped bearer token.
5. Fetch the remote Nigori settings entity.
6. If no Nigori entity exists, initialize the chain with the compatible PBKDF2
   Nigori entity.
7. Derive the 16-byte encryption key and 16-byte MAC key from the remote
   Nigori configuration.
8. Keep authentication and encryption material in memory only for the active
   unlocked session.

Do not persist the Sync code, derived keys, auth token, or decrypted password
values outside the existing encrypted storage/cache policy.

---

## 7. Password Operations

### Read/list

- Request all entities for datatype `45873` with an empty progress token.
- Ignore deleted entities and non-password entities.
- Base64 decode and Nigori decrypt `PasswordSpecifics.encrypted`.
- Decode `PasswordSpecificsData` and map it to an app `PasswordEntry` model.
- Continue reading after individual decryption failures and return their count
  alongside successfully decrypted entries.

### Create

- Validate an HTTP(S) URL.
- Normalize its `origin` and `signonRealm` exactly as the TypeScript reference.
- Build a manually-added password entity (`type = 3`).
- Encrypt password specifics and optional default note.
- Commit it and return the locally generated entity ID; later refresh from Sync
  if server-assigned metadata is needed.

### Update

- Use the existing server entity ID and version.
- Preserve URL and username identity fields required to reproduce the original
  `client_tag_hash`.
- Re-encrypt the current password payload and commit the replacement entity.

### Delete

- Send a `deleted = true` tombstone with the existing entity ID/version.
- Include minimal encrypted password specifics because the server requires
  specifics even for deletion commits.

### Notes

Initially support one unnamed/default note only. This is the note form Brave's
password manager displays. Named/multiple notes can be deferred.

### Conflicts

When Sync returns a conflict/version error, refresh the remote data and expose
a retryable failure. Do not silently overwrite a newer remote entry.

---

## 8. Android Transport

Implement a `BraveSyncTransport` with OkHttp:

- POST binary protobuf messages to `/command/`.
- Add `client=Chromium` and base64 public-key `client_id` query parameters.
- Set Brave's required authorization, service key, user agent, content type,
  cache-control, and gzip headers.
- Accept raw or gzip-compressed protobuf responses.
- Apply sensible request timeouts and coroutine cancellation.

Start with platform DNS. The TypeScript library's DNS fallback exists because
some networks block `sync-v2.brave.com` through DNS interception.

If real Android-device testing confirms that failure mode, add an optional
fallback behind the transport layer:

- resolve through DNS-over-HTTPS or a direct public resolver;
- connect to the resolved IP while keeping the original hostname for TLS SNI
  and HTTP `Host` behavior;
- never expose this complexity to the password service or UI.

---

## 9. App Integration

The master account can derive its Brave Sync code on demand:

```kotlin
val syncCode = Seedify.toBraveSyncCode(masterSeed)
bravePasswordService.connect(syncCode)
```

Integration tasks:

1. Add a Hilt module that provides the password service and transport.
2. Tie connection state to an already-unlocked master-account session.
3. Add a password ViewModel and UI only after protocol interoperability is
   proven.
4. Keep password management independent of the Nostr SSH-key backup services.
5. Avoid logging passwords, Sync codes, private keys, derived keys, auth
   tokens, ciphertext plaintexts, or raw protobuf payloads.
6. Use `FLAG_SECURE` and guarded reveal/copy flows on screens that expose
   password secrets.

The initial domain-level integration should be API-only. UI work follows once
Android ↔ Brave interoperability is confirmed.

---

## 10. Testing and Delivery Milestones

### Milestone 1 — Build and crypto parity

- Add protobuf Gradle setup and reduced schemas.
- Implement auth, Nigori crypto, and password identity helpers.
- Add and pass deterministic TypeScript parity vectors.

### Milestone 2 — Transport and protocol fixtures

- Implement OkHttp request/response handling and gzip support.
- Use a fixture/mock server to verify headers, query parameters, protobuf
  payloads, response decoding, and error handling.

### Milestone 3 — Password read/write implementation

- Implement Nigori discovery/initialization.
- Implement list, create, update, and delete.
- Unit test entity construction, decryption failures, updates, tombstones, and
  conflict mapping.

### Milestone 4 — Live Brave interoperability

Verify against a dedicated non-production test Sync code:

1. Android-created password appears in Brave.
2. A Brave-created password decrypts in Android.
3. Android updates appear in Brave.
4. Android deletes remove/tombstone the Brave entry.
5. Default notes remain visible in Brave.

### Milestone 5 — Vault integration

- Add Hilt wiring and unlocked-session lifecycle.
- Add password ViewModel and Compose screens.
- Add loading, error, empty, refresh, and retry states.

### Milestone 6 — Hardening

- Review secret handling and logging.
- Verify release R8/ProGuard configuration for protobuf and crypto.
- Test on physical Android devices and supported ABIs.
- Add DNS fallback only if real-device evidence requires it.

---

## Explicitly Out of Scope

- Bookmarks
- Authenticator.cc or other 2FA data
- Generic Brave Sync datatype support
- CLI support
- Seed-shard orchestration
- Android Autofill Service
- Background password synchronization
- Advanced multi-device conflict-resolution UI
- DNS bypass unless required by real Android tests

---

## Repository Hygiene

The current working tree already contains broad uncommitted package-renaming
changes from `com.zenten.vault` to `app.zenten.vault`. Brave Sync work must
remain isolated from those unrelated changes and should not fold them into
Brave Sync commits.
