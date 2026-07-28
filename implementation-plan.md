# ZenTen Vault — Android (Kotlin) Proof-of-Concept Plan

This document is the actionable engineering plan for a **proof-of-concept (PoC)**
Android app built from the existing specifications:

- `architecture-overview.md` — system architecture & invariants
- `feature-specification.md` — feature-by-feature behavior (F-001…F-021)
- `ui-ux-flows.md` — screens, navigation, components

The goal of the PoC is to prove the **core value chain**: import an SSH key,
derive the key hierarchy, back it up to Nostr relays, and fully recover from
that backup. Anything not required to demonstrate that chain is deferred.

---

## 0. PoC Scope — What's In / What's Out

### In scope (PoC)

- SSH key import + passphrase validation (master account).
- Deterministic derivation: SSH + passphrase → BIP39 (24 words) → Nostr keys (NIP-06).
- Sub-account creation (fresh, unencrypted SSH key pairs).
- **Single-source backup**: Nostr relays only (custom kinds `37801` and `37802`).
- Account recovery from Nostr relays.
- Encrypted-at-rest storage of accounts.
- Minimal UI: onboarding, accounts list, account detail, new account.

### Out of scope (deferred past PoC)

- ❌ **Brave Sync** (second backup source, 25-word code, password entries) — F-005, F-010, F-014.
- ❌ **Locked accounts** and the unlock flow — F-015 (import failures are simply
reported/skipped in the PoC, no locked state).
- ❌ **Device-time validation gate** — F-019.
- ❌ **Offline gate / connectivity screen** — F-020 (best-effort; ops just fail if offline).
- ❌ **Kind 10002 / 10050** relay-metadata events.
- ❌ **Config locking** & pre-setup relay editing UI — relays are a hardcoded/default list.
- ❌ **Backup-status health screen** — F-016 (a simple per-account "backed up?" flag is enough).
- ❌ **Master account display/detail** — the master is a hidden signing and recovery identity, not a user-visible account.
- ❌ QR scan / file import — **paste only** for the PoC.

> The master-account **passphrase stays in** — it is the gate that validates the
> SSH key and derives the mnemonic (invariants 2 & 7). It is not a "custom
> password" feature; it is intrinsic to the derivation.

---

## 1. Objectives & Success Criteria

- Reproduce the deterministic key hierarchy: SSH key + passphrase → BIP39 → Nostr
(NIP-06) — see `architecture-overview.md §4`.
- Single-source redundant backup to Nostr relays using the custom backup event contract.
- Account secrets stored encrypted at rest (Android Keystore-backed).
- **Definition of done for the PoC**: import master → create sub-account → back
both up to relays → wipe local storage → fully recover from relays. The
relevant invariants below hold.

---

## 2. Technology Stack (Android)


| Concern          | Choice                                                          |
| ---------------- | --------------------------------------------------------------- |
| Language         | Kotlin 2.2.x                                                    |
| Min / Target SDK | minSdk 26, compileSdk/targetSdk 36                              |
| Build            | AGP 8.11.x, Gradle 8.13, JDK 17 (host unit tests on JDK 21)     |
| Nostr protocol   | **Quartz** (`com.vitorpamplona.quartz`) — NIP-06/19/44 + events |
| UI               | Jetpack Compose + Material 3                                    |
| Navigation       | Navigation-Compose (single-activity)                            |
| DI               | Hilt                                                            |
| Async            | Kotlin Coroutines + Flow                                        |
| Background jobs  | WorkManager (backup retry)                                      |
| Secure storage   | Jetpack Security `EncryptedSharedPreferences` (Keystore-backed) |
| KV storage       | Jetpack DataStore (Preferences)                                 |
| Networking       | OkHttp (WebSocket for Nostr relays)                             |
| JSON             | kotlinx.serialization                                           |
| Testing          | JUnit5, Turbine, MockK, Compose UI test, Robolectric            |


### Cryptography library mapping

The crypto chain splits cleanly into two halves, handled differently:

- **Non-standard, ZenTen-specific (hand-rolled on BouncyCastle):** the SSH-key
  side — OpenSSH-v1 parse, bcrypt_pbkdf, AES-CTR, raw 32-byte Ed25519 seed
  extraction. No library exposes the *raw seed*, so this stays hand-rolled.
- **Standard Nostr (delegated to Quartz):** NIP-06 key derivation, NIP-19
  bech32 (npub/nsec), NIP-44 v2 encryption, and event build/sign/verify.
  Quartz is the battle-tested Kotlin impl behind Amethyst and is **verified
  byte-for-byte** against our golden vectors + the official NIP-44 vectors by
  `QuartzParityTest` (the Phase-1 gate). This replaces the earlier plan to
  hand-roll NIP-44 and vendor a bech32 encoder.

**SSH-side provider: BouncyCastle** (`org.bouncycastle:bcprov-jdk18on`, **1.79+**).
It supplies Ed25519, bcrypt_pbkdf, AES-CTR and OpenSSH-v1 parsing from a single
dependency and works down to minSdk 26.

> We do **not** rely on the JDK-native `EdDSA` provider: Ed25519 only ships in
> the Android platform provider on API 33+, which is above our minSdk 26.


| Spec requirement                                | Android/Kotlin approach                                                           |
| ----------------------------------------------- | --------------------------------------------------------------------------------- |
| Ed25519 sign/verify + keygen                    | BouncyCastle `Ed25519` primitives (`Ed25519PrivateKeyParameters`)                 |
| OpenSSH v1 container parse/emit                 | BouncyCastle `OpenSSHPrivateKeyUtil` — cross-check with Apache MINA `sshd-common` |
| bcrypt_pbkdf (OpenSSH KDF, **1 round for PoC**) | BouncyCastle `OpenBSDBCrypt` / bcrypt_pbkdf primitive; test vs real OpenSSH keys  |
| AES-256-CTR (SSH key decrypt)                   | JCE (`Cipher` "AES/CTR/NoPadding")                                                |
| **Raw 32-byte Ed25519 seed extraction**         | Custom OpenSSH decrypt path (see note below) using BC primitives                  |
| SHA-256 fingerprint                             | `MessageDigest`                                                                   |
| BIP39 (24 words, seed==entropy)                 | Quartz `Nip06.privateKeyFromMnemonic` (BIP39 mnemonic→seed internally)            |
| BIP32 HD derivation                             | Quartz NIP-06 (`m/44'/1237'/account'/0/0`), secp256k1 bundled                     |
| secp256k1 / NIP-06                              | Quartz `Nip06` + `KeyPair` (secp256k1 JNI, transitive)                            |
| NIP-44 v2 (ECDH + ChaCha20-Poly1305)            | Quartz `Nip44.v2` — verified vs official vectors, not hand-rolled                 |
| bech32 (npub/nsec)                              | Quartz `ByteArray.toNpub()` / `.toNsec()` (NIP-19)                                |


> ⚠️ **Encrypted-key seed extraction (the crux).** F-003 needs the *raw 32-byte
> Ed25519 seed* from a passphrase-protected OpenSSH key to feed BIP39 — not a
> signing `PrivateKey`. Most libraries hand back an opaque key object and hide
> the seed. Historically BouncyCastle did **not** decrypt passphrase-protected
> OpenSSH keys (issue bcgit/bc-java#1733; added ~1.79). Plan: implement the
> decrypt path ourselves using BC primitives — `bcrypt_pbkdf` (1 round, PoC) →
> AES-256-CTR → parse the inner blob to pull the 32-byte seed — and use
> BouncyCastle/MINA sshd's OpenSSH parser as a **reference/cross-check**, not
> necessarily the production path.

> ⚠️ **Crypto is the highest-risk area.** Every primitive must have a test vector
> proving byte-for-byte parity with the reference (TypeScript) implementation
> before any UI work depends on it.

---

## 3. Module / Package Structure

Single Gradle module (`:app`), organized by clean-ish layering.

```
com.zenten.vault
├── crypto/            # ssh, bip39, nip06, nip44, fingerprint
│   ├── SshKeyCodec
│   ├── Seedify           # SSH+pass → 24-word mnemonic
│   ├── NostrKeyDerivation# NIP-06
│   └── Nip44
├── data/
│   ├── secure/           # SecureStorageService (EncryptedSharedPreferences)
│   ├── kv/               # AppStorageService (DataStore)
│   ├── model/            # Account, AppServerConfig, Backup*, ...
│   └── repo/             # AccountRepository, ConfigRepository
├── network/
│   └── nostr/            # NostrRelayService (WebSocket), event build/sign/verify
├── domain/               # services (business logic)
│   ├── SetupService
│   ├── BackupService     # relay backup
│   ├── BackupSyncService
│   └── RecoveryService
├── background/           # WorkManager worker + scheduling
├── ui/
│   ├── theme/
│   ├── components/       # Btn, AccountCard, PrivateDataModal, ...
│   ├── onboarding/       # welcome + ssh + progress
│   ├── accounts/         # list + detail
│   └── newaccount/
├── state/                # KeysViewModel (KeysProvider analog)
└── di/                   # Hilt modules
```

State mapping from spec's React contexts → Android:


| Spec (React)    | Android                                            |
| --------------- | -------------------------------------------------- |
| `KeysProvider`  | `KeysViewModel` (app-scoped) + `AccountRepository` |
| `ToastProvider` | Compose `SnackbarHost` + event channel             |


(`BackupStatusProvider`, `NetworkProvider`, `DeviceTimeProvider` are out of scope for the PoC.)

---

## 4. Data Model

Kotlin `@Serializable` data classes (simplified for the PoC — no locked accounts,
no Brave fields, no config lock):

```kotlin
data class Account(
    val createdAt: Long,          // microsecond timestamp = canonical ID + label
    val zenTenKey: String,        // single-line base64 OpenSSH private key
    val zenTenPass: String?,      // master only; null for sub-accounts
    val pubkey: String?,          // ssh public key
    val mnemonic: String?,        // master only; DERIVED, re-derivable
    val nostr: NostrKeyPair?,     // master only
)

data class NostrKeyPair(val pubkey: String, val privkey: String)

data class AppServerConfig(
    val relays: List<String>,     // hardcoded default list for the PoC
)
```

Storage keys (subset of `feature-specification.md Appendix C`, Brave/locked keys dropped):
`master_account`, `accounts_<createdAt>`, `accounts_registry`,
`setup_complete`, `app_server_config`, `failed_backup_account_ids`.

---

## 5. Implementation Phases

Each phase ends with tests green and a demoable milestone.

### Phase 0 — Project Bootstrap

- Gradle Kotlin DSL, version catalog, Compose + Hilt + coroutines setup.
- CI (build + unit tests), ktlint/detekt, base theme (Material 3).
- Skeleton navigation graph with placeholder screens for every route.
- **Milestone**: app launches to a placeholder welcome screen.

### Phase 1 — Cryptographic Core (highest priority / highest risk)

Implements F-002, F-003, F-004, F-007 primitives.

- `SshKeyCodec`: parse/validate OpenSSH v1 (magic header, ssh-ed25519, 32-byte
pubkey), bcrypt_pbkdf + AES-256-CTR decrypt, **extract the raw 32-byte Ed25519
seed** (for BIP39), generate unencrypted sub-account keys with `createdAt`
comment, SHA-256 fingerprint. Built on BouncyCastle primitives; cross-checked
against BC `OpenSSHPrivateKeyUtil` / Apache MINA `sshd-common`.
- `Seedify`: SSH+passphrase → 24-word BIP39 mnemonic (passphrase validation gate).
- `NostrKeyDerivation`: thin wrapper over Quartz `Nip06`/`KeyPair` — NIP-06
`m/44'/1237'/0'/0/0`, npub/nsec via NIP-19 `toNpub()`/`toNsec()`.
- `Nip44`: thin wrapper over Quartz `Nip44.v2` — self-encryption encrypt/decrypt.
- **Milestone**: golden test vectors match the reference implementation exactly.
  Already proven for the Nostr half by `QuartzParityTest` (NIP-06 npub/nsec/hex
  vs `nostr-tools` golden; NIP-44 conversation-key + full fixed-nonce ciphertext
  vs the 35 + 10 official `paulmillr/nip44` vectors). Remaining gate work is the
  SSH-side seed extraction.
- **Gate**: no downstream work merges until parity tests pass.

### Phase 2 — Storage Layer

Implements F-008 (subset).

- `SecureStorageService` on EncryptedSharedPreferences: master/sub CRUD +
registry management (dedupe, ordering, silently skip missing).
- `AppStorageService` on DataStore for non-sensitive KV.
- Round-trip and corruption tests.
- **Milestone**: accounts persist and reload across process death.

### Phase 3 — Networking

Implements F-009 (transport).

- `NostrRelayService`: OkHttp WebSocket, event build + Schnorr sign + verify,
publish quorum (min 1 ack, ≤3 retries, 8s timeout), REQ/EVENT/EOSE handling,
generic fetch by author and kind.
- Hardcoded default relay list (no editing UI in the PoC).
- **Milestone**: publish and read back a `37802` sub-account event against a test relay.

### Phase 4 — Domain Services

Implements F-001, F-006, F-009, F-011, F-012, F-013.

- `SetupService.performSetup()` with **fatal relay-backup + rollback** (invariant 3),
then mark setup complete + sub-account discovery. (No config lock, no 10002/10050,
no Brave.)
- `SetupService.addAccount()` — sub-account creation + enqueue relay backup.
- `BackupService` — master backups use kind `37801` and exact `d` tag `"masterkey"`;
sub-account backups use kind `37802` and `d = createdAt` (decimal microseconds).
The NIP-44 plaintext is exactly the single-line base64 OpenSSH private key.
- `BackupSyncService` — discover pending restores + backfill relay gaps,
5-min cooldown, single-flight, never deletes locally. (Relays only.)
- `RecoveryService` — relay recovery; on import failure, **skip + report** (no locked state).
- **Milestone**: create master + sub-account, back up, wipe, fully recover.

### Phase 5 — Background Jobs

Implements F-011.

- WorkManager worker for relay backup retry.
- Persist/re-attempt `failed_backup_account_ids` on startup (`runStartupRetries`).
- **Milestone**: failed relay backups retry automatically on next launch.

### Phase 6 — UI: App Shell & Onboarding

Implements F-001, F-002 (paste only).

- `LoadingScreen`, `MainAppShell`, `CustomToast`, app init routing per
`ui-ux-flows.md §2` (setup-complete routing). No device-time / offline gates.
- Welcome screen (no pre-login settings modal).
- SSH key entry (**paste only**), passphrase entry, animated multi-step progress
screen, fatal-error retry.
- **Milestone**: full first-run onboarding creates + backs up master account.

### Phase 7 — UI: Accounts, Detail, New Account

Implements F-006, F-017 (subset).

- Accounts list: header, **sub-account-only** cards, pending-restore section, FAB,
empty state, optimistic updates. The master identity is never displayed. (No locked section.)
- Account detail: sub-account fingerprint/pubkey and simple backup flag,
`PrivateDataModal` (confirm + copy), local-only deletion. Full reset is available
as an app-level action, not through a master-account detail screen.
- New-account flow (`AccountCreationShell`, confirm → progress → back).
- Pending-restore dialog (`RestoreAccountDialog`).
- **Milestone**: view, create, reveal (guarded), delete, restore, and reset flows work.

### Phase 8 — Hardening & Demo

- Acceptance-test pass against the PoC invariants (§6).
- Basic security review, R8 rules, demo build.
- **Milestone**: demoable PoC APK.

---

## 6. Invariant → Enforcement Checklist (PoC subset)

From `architecture-overview.md §12`; Brave/locked invariants (8, 10) are deferred.

- 1. Master SSH key is user-provided (never generated).
- 1. Passphrase validated by successful SSH decrypt.
- 1. Relay backup failure during setup is fatal → rollback.
- 1. `createdAt` (µs) is canonical account ID everywhere.
- 1. Master backup identifier = literal lowercase `"masterkey"`; its event kind is `37801`.
- 1. NIP-44 self-encryption (sender = recipient = master).
- 1. Mnemonic always re-derivable from key+pass.
- 1. Sub-account backups signed by master Nostr key.
- 1. Sub-account SSH comment = `createdAt`.
- 1. Sub-account label = `createdAt`.
- 1. Sync cooldown ≥ 5 minutes.

*Deferred:* 8 (locked accounts), 10 (Brave Sync code).

---

## 7. Testing Strategy

- **Crypto parity (Phase 1)**: golden vectors vs reference impl — the gate for everything.
- **Unit**: services, repositories, event build/validate, registry dedupe.
- **Integration**: relay publish/fetch (local/mock relay), full backup → wipe →
recover round trip.
- **Instrumented**: EncryptedSharedPreferences persistence across process death,
WorkManager retry behavior.
- **UI**: Compose tests for onboarding, guarded private-key reveal, empty/error states.
- **Acceptance**: one scripted E2E per PoC invariant in §6.

---

## 8. Security Considerations

- SSH private keys encrypted at rest via Keystore-backed storage; avoid logging secrets.
- Nostr private key stored only inside the encrypted secure-store blob.
- No autofill on secret fields; require explicit confirmation to reveal private keys.
- Screenshot/`FLAG_SECURE` on screens that render secrets.
- R8/ProGuard keep rules for crypto/serialization; ship no debug logging in release.

---

## 9. Risks & Open Questions


| Risk / Question                                        | Mitigation                                                                                                                                                                |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Raw Ed25519 seed extraction from encrypted OpenSSH key | Custom decrypt path (bcrypt_pbkdf → AES-CTR → parse) on BC primitives; libraries expose opaque keys, not the seed. Cross-check vs BC `OpenSSHPrivateKeyUtil` / MINA sshd. |
| `bcrypt_pbkdf` + OpenSSH KDF parity                    | Vendor & unit-test against known OpenSSH keys first; requires BouncyCastle 1.79+ for encrypted-key support.                                                               |
| NIP-44 v2 correctness                                  | **Resolved** — delegated to Quartz `Nip44.v2`, verified byte-for-byte vs the official `paulmillr/nip44` vectors (conv-key + fixed-nonce ciphertext) in `QuartzParityTest`. |
| NIP-06 parity vs `nostr-tools` reference               | **Resolved** — Quartz `Nip06` reproduces the golden npub/nsec/hex exactly (`QuartzParityTest`).                                                                            |
| secp256k1 native bindings on all ABIs                  | Bundled JNI via Quartz (transitive `fr.acinq.secp256k1`); host unit tests use `secp256k1-kmp-jni-jvm`; verify arm64/x86_64 on device.                                     |
| Quartz ships JVM 21 bytecode (host tests)              | **Resolved** — run unit tests on an auto-provisioned JDK 21 toolchain; app itself stays on JVM 17. Quartz 1.08.0 pinned (newest needing only stable compileSdk 36).       |
| Microsecond timestamp precision on Android             | Emulate µs = `epochMs*1000 + random3digits` per `architecture-overview.md §5`.                                                                                            |


---

## 10. Suggested Timeline (indicative, PoC)


| Phase | Focus           | Est.       |
| ----- | --------------- | ---------- |
| 0     | Bootstrap       | 2–3 d      |
| 1     | Crypto core     | 1.5–2.5 wk |
| 2     | Storage         | 2–4 d      |
| 3     | Networking      | 1–1.5 wk   |
| 4     | Domain services | 1–1.5 wk   |
| 5     | Background jobs | 2–3 d      |
| 6–7   | UI              | 1.5–2 wk   |
| 8     | Hardening/demo  | 3–5 d      |


Critical path is **Phase 1 (crypto)** → everything else depends on parity.

---

## 11. Immediate Next Steps

1. ~~Scaffold the Gradle project (Phase 0)~~ — done.
2. ~~Collect reference test vectors~~ — done (`app/src/test/resources/golden/`).
3. ~~Settle the Nostr library question~~ — done: Quartz adopted, parity proven by
  `QuartzParityTest`. Toolchain migrated (AGP 8.11 / Gradle 8.13 / Kotlin 2.2.21 /
  compileSdk 36; unit tests on JDK 21).
4. Implement `SshKeyCodec` (the crux: raw 32-byte seed extraction) + `Seedify`,
  and add an **encrypted** SSH-key golden vector with a known passphrase at
  1-round bcrypt_pbkdf. This is the last open parity item for Phase 1.
5. Wrap Quartz behind thin `NostrKeyDerivation` / `Nip44` adapters so the rest
  of the app depends on our interfaces, not Quartz types directly.

