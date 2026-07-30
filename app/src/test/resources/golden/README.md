# Golden Crypto Test Vectors (Phase 1)

These fixtures are the **parity gate** for the cryptographic core
(`app.zenten.vault.crypto`). Every primitive must reproduce these values
byte-for-byte before any downstream phase merges (see `implementation-plan.md`
§5 Phase 1 and §7 Testing Strategy).

**Status:** the Nostr half (NIP-06 keys, NIP-19 npub/nsec, NIP-44 v2) is owned by
the [Quartz](https://github.com/vitorpamplona/quartz) library and is already
proven against these vectors by `QuartzParityTest`. The SSH-side seed extraction
(`seedHex` from `sshKeyPem` + `passphrase`) is the remaining primitive to build
and gate.

## Files

### `crypto-vectors.json`

Deterministic outputs of the reference chain, captured from the same libraries
the React `../vault` app uses (`seedify-ts` + `nostr-tools`):

```
SSH key (+passphrase) -> raw 32-byte ed25519 seed
                      -> Seedify.toMnemonic(24)   (BIP39, no-prefix: entropy == seed)
mnemonic              -> NIP-06 m/44'/1237'/0'/0/0 (secp256k1) -> pubkey/privkey/npub/nsec
pubkey blob           -> SHA256 fingerprint (ssh-keygen -lf parity)
```

Fields per vector:

| Field              | Meaning                                                        |
| ------------------ | -------------------------------------------------------------- |
| `sshKeyPem`        | Input OpenSSH v1 private key (PEM)                             |
| `passphrase`       | Passphrase (null = unencrypted key)                           |

`keys.encryptedEd25519Pem` is a separate OpenSSH-generated `aes256-ctr` / bcrypt_pbkdf fixture (one KDF round), using the test-only passphrase `phase1-test-passphrase`. It encrypts the same Ed25519 material as the unencrypted vector and is exercised by `SshKeyCodecTest`.
| `seedHex`          | Raw 32-byte ed25519 seed extracted from the key (hex)         |
| `ed25519PubkeyHex` | Raw 32-byte ed25519 public key (hex)                          |
| `sshFingerprint`   | `SHA256:<base64>` — matches `ssh-keygen -E sha256 -lf`        |
| `mnemonic24`       | 24-word BIP39 mnemonic (no-prefix path; entropy == seed)      |
| `mnemonic12`       | 12-word BIP39 mnemonic (SHA256(prefixedSeed)[:16] path)       |
| `nostrNip06`       | NIP-06 secp256k1 keys: `pubkey`,`privkey` (hex), `npub`,`nsec`|

> Note: `seedify-ts` also exposes an ed25519-direct `deriveNostrKeysFromSeed`,
> but the **production vault path** (and thus this fixture) derives Nostr keys
> from the 24-word mnemonic via NIP-06 (secp256k1). Kotlin must match `nostrNip06`.

### `nip44.vectors.json`

The **official NIP-44 v2 test vectors** (source:
`github.com/paulmillr/nip44` → `nip44.vectors.json`). Covers
`get_conversation_key`, `get_message_keys`, `calc_padded_len`,
`encrypt_decrypt` (fixed nonce → deterministic ciphertext), and
`encrypt_decrypt_long_msg`. Use these for full ChaCha20-Poly1305 + HKDF parity.

The `nip44` block inside `crypto-vectors.json` is a small self-encryption
smoke vector (sender == recipient == master). Its `sampleCiphertext` uses a
**random nonce**, so assert `decrypt(ciphertext) == plaintext` and reproduce
`conversationKey` exactly — do not assert the ciphertext bytes.

## Regenerating

```bash
node tools/gen-golden-vectors.mjs   # rewrites crypto-vectors.json
```

Requires the sibling reference repos to be present and built:
`../vault` (node_modules installed) and `../seedify-ts` (`npm run build`).

To refresh the official NIP-44 vectors:

```bash
curl -fsSL https://raw.githubusercontent.com/paulmillr/nip44/main/nip44.vectors.json \
  -o app/src/test/resources/golden/nip44.vectors.json
```
