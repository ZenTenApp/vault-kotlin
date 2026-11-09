# Golden Crypto Test Vectors

These fixtures are the **parity gate** for the cryptographic core
(`app.zenten.vault.crypto`). Every deterministic production-path value must be
reproduced exactly; see
[Test coverage areas](../../../../../docs/technical/build-and-test.md#test-coverage-areas).

**Status:** both halves are implemented and gated. The Nostr half (NIP-06 keys,
NIP-19 npub/nsec, NIP-44 v2) is owned by the
[Quartz](https://github.com/vitorpamplona/quartz) library and is proven against
these vectors by `QuartzParityTest`. The SSH side — decrypting a
passphrase-protected OpenSSH key and extracting the raw 32-byte Ed25519 seed
(`seedHex`) — is implemented by `SshKeyCodec.parse` and gated by
`SshKeyCodecTest`, which decrypts the passphrase-protected
`keys.encryptedEd25519Pem` fixture (it wraps the same Ed25519 material as the
unencrypted vector) and asserts the parsed seed equals the vector's `seedHex`.

## Files

### `crypto-vectors.json`

Deterministic outputs of the reference chain, captured from the same libraries
the React Native (`../vault`) app uses (`seedify-ts` + `nostr-tools`):

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
| `seedHex`          | Raw 32-byte ed25519 seed extracted from the key (hex)         |
| `ed25519PubkeyHex` | Raw 32-byte ed25519 public key (hex)                          |
| `sshFingerprint`   | `SHA256:<base64>` — matches `ssh-keygen -E sha256 -lf`        |
| `mnemonic24`       | 24-word BIP39 mnemonic (no-prefix path; entropy == seed)      |
| `mnemonic12`       | 12-word BIP39 mnemonic (SHA256(prefixedSeed)[:16] path)       |
| `nostrNip06`       | NIP-06 secp256k1 keys: `pubkey`,`privkey` (hex), `npub`,`nsec`|

`keys.encryptedEd25519Pem` is a separate OpenSSH-generated `aes256-ctr` / bcrypt_pbkdf fixture at **128 KDF rounds**, the floor `SshKeyCodec` accepts, using the test-only passphrase `phase1-test-passphrase`. It is the same Ed25519 material as the unencrypted vector, re-encrypted with `ssh-keygen -p -a 128`, so the seed and fingerprint assertions still match; a weaker fixture could no longer be parsed at all. It is exercised by `SshKeyCodecTest`.

No Kotlin code consumes `mnemonic12` or `passphrase`: `SshKeyCodec.parse` accepts
only passphrase-protected keys, so this `passphrase: null` vector is reference
material, and only the 24-word path is a product contract.

> Note: `seedify-ts` also exposes an ed25519-direct `deriveNostrKeysFromSeed`,
> but the **production vault path** (and thus this fixture) derives Nostr keys
> from the 24-word mnemonic via NIP-06 (secp256k1). Kotlin must match `nostrNip06`.

### `nip44.vectors.json`

The **official NIP-44 v2 test vectors** (source:
`github.com/paulmillr/nip44` → `nip44.vectors.json`). Covers
`get_conversation_key`, `get_message_keys`, `calc_padded_len`,
`encrypt_decrypt` (fixed nonce → deterministic ciphertext), and
`encrypt_decrypt_long_msg`. Use these for full NIP-44 v2 parity: secp256k1 ECDH
followed by HKDF-SHA256 (extract with salt `nip44-v2`, expand to the ChaCha20
key, ChaCha20 nonce, and HMAC key), ChaCha20 (RFC 8439) encryption, and a
32-byte HMAC-SHA256 MAC over the nonce and ciphertext. NIP-44 v2 does not use
AES or Poly1305. `QuartzParityTest` currently asserts the `get_conversation_key`
and `encrypt_decrypt` blocks; the `get_message_keys`, `calc_padded_len`, and
`encrypt_decrypt_long_msg` blocks are available but not yet asserted directly.

The `nip44` block inside `crypto-vectors.json` is a small self-encryption
smoke vector with sender == recipient (the shape Vault Key backup uses). Its
`sampleCiphertext` uses a **random nonce**, so assert
`decrypt(ciphertext) == plaintext` and reproduce `conversationKey` exactly — do
not assert the ciphertext bytes.

## Regenerating

```bash
node tools/gen-golden-vectors.mjs   # rewrites crypto-vectors.json
```

Requires the sibling reference repos to be present and built:
`../vault` (node_modules installed) and `../seedify-ts` (`npm run build`).

> The generator's `ENCRYPTED_ED25519` constant is kept identical to the checked-in
> `keys.encryptedEd25519Pem`, so regenerating preserves the `phase1-test-passphrase`
> fixture that `SshKeyCodecTest` decrypts. Regenerating it with `ssh-keygen` at a
> different `-a` value would produce a key `SshKeyCodec` rejects below 128 rounds.

To refresh the official NIP-44 vectors:

```bash
curl -fsSL https://raw.githubusercontent.com/paulmillr/nip44/main/nip44.vectors.json \
  -o app/src/test/resources/golden/nip44.vectors.json
```
