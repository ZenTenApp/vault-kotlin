# SSH key generation and recovery-seed derivation

This document describes the current implementation for two distinct flows:

1. importing a **master** OpenSSH Ed25519 private key and deriving its 24-word recovery mnemonic; and
2. generating a fresh OpenSSH Ed25519 key for an **associated account**.

They both use Ed25519 key material, but only the master-account onboarding flow currently derives and stores a mnemonic.

## Master account: OpenSSH key to 24-word mnemonic

```text
Pasted passphrase-protected OpenSSH private key + passphrase
  -> normalize input to canonical Base64
  -> parse (and, when necessary, decrypt) the OpenSSH-v1 container
  -> extract the raw 32-byte Ed25519 private seed
  -> use that seed unchanged as 256-bit BIP-39 entropy
  -> produce a valid 24-word BIP-39 mnemonic
  -> derive the NIP-06 Nostr recovery identity from the mnemonic
```

`ui/onboarding/OnboardingViewModel.kt` performs the application-level flow:

```kotlin
val key = SshKeyCodec.normalizeToSingleLineBase64(keyInput)
val parsed = SshKeyCodec.parse(key, passphrase.toCharArray())
val mnemonic = Seedify.toMnemonic(parsed.seed)
val nostr = NostrKeyDerivation.derive(mnemonic)
```

The resulting master account retains the normalized private key, passphrase, SSH public-key line, mnemonic, and derived Nostr keys before `SetupService` persists it and makes the required master backup.

### Accepted input and OpenSSH-v1 parsing

`crypto/SshKeyCodec.kt` accepts a PEM-wrapped key, raw Base64, or the supported `{"OPENSSH PRIVATE KEY":"..."}` wrapper. It Base64-decodes the input and verifies the OpenSSH-v1 magic prefix:

```text
openssh-key-v1\0
```

The parser reads the cipher name, KDF name and options, key count, outer public-key blob, and private-key blob. It permits one key only and accepts only passphrase-protected keys with `ciphername = aes256-ctr` and `kdfname = bcrypt`. Unencrypted (`ciphername = none`) OpenSSH private keys are rejected.

The bcrypt options provide a salt and round count. The parser derives 48 bytes with `bcrypt_pbkdf`: the first 32 bytes are the AES-256 key and the remaining 16 bytes are the AES-CTR IV. It decrypts the private blob before parsing it.

The decrypted private blob is checked for matching OpenSSH check integers, the `ssh-ed25519` algorithm identifier, matching inner and outer public keys, valid sequential OpenSSH padding, and the expected private-key layout:

```text
64-byte private field = 32-byte Ed25519 seed || 32-byte Ed25519 public key
```

The seed is therefore extracted directly from the first half of that field:

```kotlin
privateAndPublic.copyOfRange(0, 32)
```

It is not calculated by hashing the SSH key or by deriving it from the public key.

### Why the result is 24 words

`crypto/Seedify.kt` defines the production convention as:

```text
raw 32-byte Ed25519 seed = BIP-39 entropy
```

`Seedify.toMnemonic` passes those 32 bytes directly to the BIP-39 encoder:

```kotlin
fun toMnemonic(seed: ByteArray): String {
    require(seed.size == 32)
    return Bip39Mnemonics.toMnemonics(seed).joinToString(" ")
}
```

The 32-byte seed supplies 256 bits of entropy. BIP-39 adds an 8-bit checksum, yielding 264 bits; each word encodes 11 bits:

```text
(256 entropy bits + 8 checksum bits) / 11 bits per word = 24 words
```

No prefix, hash, or extra passphrase is applied before mnemonic encoding. The mnemonic is subsequently used as the input to NIP-06 Nostr derivation (default path `m/44'/1237'/0'/0/0`), which uses secp256k1. This Nostr identity is separate from the SSH Ed25519 key pair.

## Associated account: generating a new SSH key

`domain/AccountService.kt` creates an associated account as follows:

1. Allocate a unique, microsecond-style `createdAt` identifier.
2. Call `SshKeyCodec.generateEncrypted(createdAt, masterPassphrase)`.
3. Normalize and store the generated OpenSSH private key as single-line Base64.
4. Persist locally, then start backup coordination through `SetupService.addAccount`.

`SshKeyCodec.generateEncrypted` creates a new Ed25519 key with `SecureRandom` through Bouncy Castle:

```kotlin
val private = Ed25519PrivateKeyParameters(random)
val seed = private.encoded
val public = private.generatePublicKey().encoded
```

It serializes the generated material into an OpenSSH-v1 container encrypted with the retained master-account passphrase (`aes256-ctr` and `bcrypt_pbkdf`) using a work factor of 128 rounds. Its private blob includes random duplicate check integers, `ssh-ed25519`, the public key, `seed || public`, a comment containing `createdAt`, and standard OpenSSH sequential padding. The generated public-key line also uses `createdAt` as its comment. Parsing uses the round count stored in each container, so keys generated previously with a different work factor remain readable.

### Important distinction

A generated associated key has the same Ed25519 `seed || public` representation, so it *could* be parsed and passed through `Seedify.toMnemonic` later. However, the current associated-account creation path does not derive or persist a mnemonic or Nostr identity for it.

```text
Imported master key:
  parse key -> extract seed -> derive/store mnemonic -> derive Nostr identity

Generated associated key:
  generate seed/key pair -> serialize/store OpenSSH key -> back up key
```

The mnemonic is thus the master account's recovery identity, not a standard field of each associated account.

## Verification

`app/src/test/java/app/zenten/vault/crypto/SshKeyCodecTest.kt` verifies that the implementation:

- extracts the expected seed, public key, fingerprint, and 24-word mnemonic from a known OpenSSH fixture;
- decrypts the supported bcrypt/AES-CTR fixture and rejects missing or incorrect passphrases;
- normalizes PEM, raw Base64, and supported JSON-wrapped input consistently; and
- rejects unencrypted OpenSSH private keys and creates a passphrase-encrypted associated key whose comment is the allocated creation ID.

Compatibility vectors are in `app/src/test/resources/golden/crypto-vectors.json`; their chain is documented in `app/src/test/resources/golden/README.md`.

## Primary implementation locations

- Master onboarding: `app/src/main/java/app/zenten/vault/ui/onboarding/OnboardingViewModel.kt`
- OpenSSH parsing and generation: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- BIP-39 mnemonic conversion: `app/src/main/java/app/zenten/vault/crypto/Seedify.kt`
- Nostr derivation: `app/src/main/java/app/zenten/vault/crypto/NostrKeyDerivation.kt`
- Associated-account lifecycle: `app/src/main/java/app/zenten/vault/domain/AccountService.kt`
- Master setup and backup handoff: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
