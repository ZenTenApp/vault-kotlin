# SSH key generation and recovery-seed derivation

This document describes the current implementation for two distinct flows:

1. importing a **Vault Key** OpenSSH Ed25519 private key and deriving its 24-word recovery mnemonic; and
2. generating a fresh OpenSSH Ed25519 key for an **associated account**.

Both flows derive a mnemonic from the SSH seed when they need the account's Nostr identity. Neither the mnemonic nor any key derived from it is persisted: `NostrKeyDerivation.fromAccountSeed` and `fromVaultKeyBackupSeed` rebuild the identity on demand from the encrypted container plus the passphrase.

## Vault Key: OpenSSH key to 24-word mnemonic

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
// Parsing proves the passphrase before anything is persisted.
SshKeyCodec.parse(key, passphrase.toCharArray())
val account = Account(createdAt = createdAt, zenTenKey = key)
```

The resulting `Account` holds only the normalized private key; the identity used to sign and encrypt its relay records is derived later from that key's seed by `DefaultBackupSourceFactory`, so nothing derived is persisted. The passphrase is moved to the in-memory session and is never persisted. The SSH public key is not stored either: it is derived on demand from the key container's unencrypted header.

### Accepted input and OpenSSH-v1 parsing

`crypto/SshKeyCodec.kt` accepts a PEM-wrapped key, raw Base64, or the supported `{"OPENSSH PRIVATE KEY":"..."}` wrapper. When OpenSSH `BEGIN`/`END` markers are present, only the text between them is decoded, so the public metadata lines appended by self-describing exports (`npub`, creation ID, display name, `Created on ...` date) are ignored on paste. It Base64-decodes the extracted body and verifies the OpenSSH-v1 magic prefix:

```text
openssh-key-v1\0
```

The parser reads the cipher name, KDF name and options, key count, outer public-key blob, and private-key blob. It permits one key only and accepts only passphrase-protected keys with `ciphername = aes256-ctr` and `kdfname = bcrypt`. Unencrypted (`ciphername = none`) OpenSSH private keys are rejected. The declared bcrypt work factor must also be inside the accepted band of 128–8192; the check runs while only metadata has been read, so a key outside it is rejected before the KDF runs. Because the outer public-key blob is unencrypted, `SshKeyCodec.publicKeyFromEncryptedPrivateKey` and `fingerprintFromEncryptedPrivateKey` extract the public key and SHA-256 fingerprint without the passphrase — the same public-key wire blob OpenSSH fingerprints. Both still read through the same container reader, so an out-of-band key is not reported on Account Details either.

The bcrypt options provide a salt and round count. The parser derives 48 bytes with `bcrypt_pbkdf`: the first 32 bytes are the AES-256 key and the remaining 16 bytes are the AES-CTR IV. It decrypts the private blob before parsing it.

The encrypted private section must also be a whole number of 16-byte AES blocks; a truncated or spliced container is rejected rather than decrypted into shifted garbage.

The decrypted private blob is checked for matching OpenSSH check integers, the `ssh-ed25519` algorithm identifier, matching inner and outer public keys, valid sequential OpenSSH padding, and the expected private-key layout:

```text
64-byte private field = 32-byte Ed25519 seed || 32-byte Ed25519 public key
```

The seed is therefore extracted directly from the first half of that field:

```kotlin
privateAndPublic.copyOfRange(0, 32)
```

It is not calculated by hashing the SSH key or by deriving it from the public key.

### Seed/public-key binding

All of the checks above are structural: they confirm the container is well formed and internally consistent, but none of them proves the seed and the public key describe the *same* key. The seed and the public key are two views of one identity, and this app exposes them as such — the SSH public key shown and exported to the user comes from the container's public-key field, while the recovery words come from the seed (`Seedify.toMnemonic(seed)` → NIP-06 Nostr key → `vkbckp` backup address). AES-CTR provides no authentication over the container, so a corrupt or crafted blob could otherwise pass a "valid key, correct passphrase" check while those two identities diverge, yielding an exported key no server accepts or a recovery under an identity the user never had.

After extracting the seed, the parser therefore re-derives its Ed25519 public key and requires it to equal every stored public-key copy before reporting success:

```kotlin
require(publicKeyOf(seed).contentEquals(public)) {
    "OpenSSH private key does not match its public key"
}
```

Generation uses the same construction in reverse — `Ed25519PrivateKeyParameters(random)` yields both the seed and its public key — so a generated key always satisfies this invariant.

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
2. Call `SshKeyCodec.generateEncrypted(createdAt, vaultKeyPassphrase)`.
3. Normalize and store the generated OpenSSH private key as single-line Base64.
4. Persist locally, then start backup coordination through `SetupService.addAccount`.

`SshKeyCodec.generateEncrypted` creates a new Ed25519 key with `SecureRandom` through Bouncy Castle:

```kotlin
val private = Ed25519PrivateKeyParameters(random)
val seed = private.encoded
val public = private.generatePublicKey().encoded
```

It serializes the generated material into an OpenSSH-v1 container encrypted with the vault-key passphrase (`aes256-ctr` and `bcrypt_pbkdf`), which is held only in memory for the session. The Settings screen stores a non-secret work factor selected from `128`, `256`, `512`, `1024`, `2048`, `4096`, or `8192`; the default is 128. The selected value applies to newly generated Vault Keys and associated accounts only. Its private blob includes random duplicate check integers, `ssh-ed25519`, the public key, `seed || public`, a comment containing `createdAt`, and standard OpenSSH sequential padding, with `createdAt` as its comment. The outer public-key blob is written unencrypted, so the public key and its SHA-256 fingerprint are derived on demand without the passphrase rather than stored.

The work factor is bounded to `128`–`8192` on every path, and the bounds are enforced in two places:

- **Generation.** `generateEncrypted` accepts only the powers of two in `SshKeyCodec.SUPPORTED_BCRYPT_PBKDF_ROUNDS` (128, 256, 512, 1024, 2048, 4096, 8192). Each `bcrypt_pbkdf` round costs a full Blowfish key schedule — roughly 8 ms on a desktop CPU — so 128 is about one second and 8192 is over a minute; the floor keeps generated keys expensive enough that an exported container is not cheap to attack offline, and the ceiling keeps the top of the menu from being a multi-minute unlock.
- **Storage.** `AppStorageService.setSshKdfRounds` rejects anything outside the offered list. `sshKdfRounds` additionally clamps an unrecognised stored value back to the default, so a setting persisted before the floor existed (for example `64`) reads as `128` rather than crashing.
- **Every read of a container.** `SshKeyCodec.readEncryptedContainer` requires the declared work factor to be within `128`–`8192` inclusive, checking both ends while only metadata has been read. Every intake and use path goes through that reader, so the band applies to **Paste VaultKey**, **Recover VaultKey**, associated-account restore from relay or Brave Sync, the subsequent unlock, Account Details, and export alike. Unlike generation, parsing is a range check rather than a menu lookup: a key declaring any count in the band, such as `200`, is accepted.
  - The floor is a strength policy. A container that leaves the device is protected only by its passphrase under this KDF, so a weaker declared factor is cheap to attack offline.
  - The ceiling is both a policy limit and a hostile-input control. The declared value is attacker-controlled metadata trusted before any passphrase is checked, and `bcrypt_pbkdf` cost grows linearly with it, so a container declaring `2,147,483,647` rounds would otherwise block every unlock and recovery attempt inside the KDF.
  - Parsing is no longer more permissive than generation below the floor. It used to accept weaker foreign keys because `ssh-keygen` defaults to 16 rounds; that compatibility carve-out was removed because Vault is pre-release and holds no older keys, so every container it can encounter was either generated inside the band or is rejected at intake (D12, revised 2026-11-09).

The practical consequence for import: an out-of-band key is refused at the **Paste VaultKey** step, before the passphrase is requested, and a recovery or restore attempt skips that copy and continues with the remaining candidates. The paste step reports a generic unsupported-key message; when every recovered copy is out of band, **Recover VaultKey** surfaces the parser's message, which names the accepted band but contains no key material. A key already stored locally with an out-of-band count would be refused on its next read; that is acceptable only while Vault is pre-release and no such keys exist in the field.

### Important distinction

A generated associated key has the same Ed25519 `seed || public` representation, so it *could* be parsed and passed through `Seedify.toMnemonic` later. Associated-account creation does not derive anything but the key container; its Nostr identity is rebuilt on demand whenever a backup record needs to be signed.

```text
Imported vault key:
  parse key -> extract seed -> store the encrypted container only

Generated associated key:
  generate seed/key pair -> serialize/store OpenSSH key -> back up key
  (the Nostr identity is derived from the stored seed at backup time, never stored)

Vault Key backup identity (relays, kind 37801):
  decrypt stored seed -> vkbckp mnemonic -> derive NIP-06 identity -> use for the run, discard
```

A mnemonic is an intermediate representation of an account's SSH seed, not a persisted account field. The generated-Vault Key flow also derives Seedify's deterministic Vault Key backup (`vkbckp`) associated-account seed as `SHA-256(SHA-256(UTF-8("vkbckp")) || originalVaultKeySeed)` and encodes it as a 24-word BIP-39 mnemonic. `vkbckp` is the "Vault Key BacKup" acronym; it is an arbitrary but fixed sub-account label whose exact spelling is part of the derivation, so changing it would produce different words, a different Brave Sync code, and a different relay identity for new Vault Keys. Those 24 words serve two purposes: Brave Sync appends its daily 25th word, while NIP-06 derives the Nostr identity used to back up the original VaultKey on relays. The app presents the 24 words as recovery indices but does not persist the mnemonic or indices separately. The stored Vault Key is only its encrypted container: its original-seed Nostr identity, used for associated-account records (`kind` 37802), is re-derived per engine run, and every Vault Key record (`kind` 37801) operation, including later consolidation, uses the `vkbckp`-derived identity instead. **Recover VaultKey** reverses the same two derivations from the 24 words the user saved: it rebuilds the NIP-06 Vault Key backup identity to read the `vaultkey` record from relays and rebuilds the sync code (including the date-derived 25th word) to read the Brave Sync record, then tries every copy those sources hold, newest first. A copy is accepted only when the passphrase decrypts it **and** its `vkbckp` backup mnemonic re-derives to the exact 24 words the user entered; decryption alone proves the passphrase, not ownership. Copies that decrypt but re-derive to other words are skipped rather than persisted, and a misbound copy is reported distinctly from an absent one.

## Verification

`app/src/test/java/app/zenten/vault/crypto/SshKeyCodecTest.kt` verifies that the implementation:

- extracts the expected seed, public key, fingerprint, and 24-word mnemonic from a known OpenSSH fixture encrypted at the 128-round floor;
- decrypts the supported bcrypt/AES-CTR fixture and rejects missing or incorrect passphrases;
- rejects a container declaring a work factor below `128` or above `8192` on every metadata-only path and on full parsing, accepts both ends of the band as positive controls, and accepts a non-menu value such as `200` inside it;
- normalizes PEM, raw Base64, and supported JSON-wrapped input consistently, and ignores public metadata appended after a PEM export;
- rejects a container whose seed does not match its advertised public key, mutated check integers or padding, and a private section that is not a whole number of AES blocks — each variant keeps every structural check intact, so only the seed/public-key binding or the alignment check catches it; and
- rejects unencrypted OpenSSH private keys and creates a passphrase-encrypted associated key whose comment is the allocated creation ID.

Compatibility vectors are in `app/src/test/resources/golden/crypto-vectors.json`; their chain is documented in `app/src/test/resources/golden/README.md`.

## Primary implementation locations

- Vault Key onboarding: `app/src/main/java/app/zenten/vault/ui/onboarding/OnboardingViewModel.kt`
- OpenSSH parsing and generation: `app/src/main/java/app/zenten/vault/crypto/SshKeyCodec.kt`
- BIP-39 mnemonic conversion: `app/src/main/java/app/zenten/vault/crypto/Seedify.kt`
- Nostr derivation: `app/src/main/java/app/zenten/vault/crypto/NostrKeyDerivation.kt`
- Associated-account lifecycle: `app/src/main/java/app/zenten/vault/domain/AccountService.kt`
- Vault Key setup and backup handoff: `app/src/main/java/app/zenten/vault/domain/SetupService.kt`
