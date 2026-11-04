# Audit remediation plan — round 2, critical and high severity

- **Created:** 2026-11-04
- **Source:** the three production-readiness audits in `audits/round1/`
  (`production-readiness-review.md` = **DS**, DeepSeek v4-1-flash;
  `production-readiness-review-2026-11-02-gpt-6.1-sol.md` = **GPT**;
  `production-readiness-review-2026-11-03-opus-5.5.md` = **OP**)
- **Baseline commit:** `8f8ab8b` (branch `fix/production-readiness-audit-remediation`)
- **Scope:** every Critical and High finding across the three reports, deduplicated by underlying
  defect. Medium/Low findings are out of scope for this plan.

Round 1 (`audits/round1/remediation-plan.md`) covered documentation drift plus the code items that
surfaced alongside it. This round is the code-side backlog: the credential-integrity, backup-truth,
hostile-input, privacy and release-engineering defects that the audits flagged as shipping blockers.

## Legend

| Tag | Meaning |
| --- | --- |
| **CODE** | A defect in the implementation. Fix the code and regression-test it. |
| **DECIDE** | A product, privacy, or security policy decision must be recorded before code is written. |
| **PROCESS** | Release/ops engineering (signing, CI, acceptance evidence) rather than app code. |
| **HOUSE** | Configuration, comments, or dependency hygiene with a security consequence. |

Status: ✅ done · ⬜ pending · 🔄 in progress · 🟡 decided, code follow-up open

## Status summary

| # | Item | Severity | Status |
| --- | --- | --- | --- |
| C1 | Concurrent secure-storage writes could lose an account | Critical | ✅ fixed `8f8ab8b` |
| C2 | OpenSSH parse accepts a seed that does not match its public key | Critical | ✅ fixed |
| H1 | No release signing configuration or key custody | High | ⬜ |
| H2 | Release variant never built, linted, or instrumented in CI | High | ⬜ |
| H3 | Release build fails under R8 | High | ✅ fixed `a1f6d3a` |
| H4 | Vault Key recovery is not bound to the supplied recovery words | High | ✅ fixed |
| H5 | Imported Vault Key passphrases bypass the strength policy | High | ✅ decided (D9) |
| H6 | bcrypt work factor settable to 1 for keys that leave the device | High | ⬜ |
| H7 | Vault Key quorum trusted write acks without read-back | High | ✅ fixed `4954799` |
| H8 | Generated setup completed without backup quorum | High | ✅ fixed `27e16c1` |
| H9 | Imported-setup retry was not durable across restart | High | ✅ fixed `4954799` |
| H10 | Unreadable records are indistinguishable from absent ones | High | ⬜ |
| H11 | Workers return success after source failures; no retry loop | High | ⬜ |
| H12 | Brave responses fail open on absent/missing protocol fields | High | ⬜ |
| H13 | Remote inventories treated as complete without proof of completion | High | ⬜ |
| H14 | Hostile input has no CPU, memory, depth, or volume limits | High | ⬜ |
| H15 | Expensive cryptography runs on the main thread in entry paths | High | ⬜ |
| H16 | Default source is Brave's production server, presented as Chrome | High | ⬜ |
| H17 | Profile publication is unattended, undisclosed, and retries unboundedly | High | 🟡 (D2, D4) |
| H18 | Local cache exposes raw Nostr private keys to any same-UID code | High | 🟡 (D5) |
| H19 | Session lifetime exceeds the documented 30-second lock | High | ✅ decided (D1) |
| H20 | Pre-API-30 prompt admitted weak biometrics | High | ✅ fixed `957a168` |
| H21 | No account deletion, vault reset, or remote deletion path | High | ⬜ |
| H22 | Dependency review used declared, not resolved, versions | High | ⬜ |
| H23 | Device, interop, and support acceptance evidence is missing | High | ⬜ |
| H24 | Clipboard export is not marked sensitive and never expires | High | ⬜ |

## Ordering rationale

1. **Credential integrity and recovery roots** (C2, H4, H10) — a wrong key or a silent wipe is the
   worst possible failure for a credential vault.
2. **Backup truth** (H11, H12, H13) — the app must never claim a backup that does not exist.
3. **Hostile input and responsiveness** (H14, H15) — cheap to fix, prevents hangs during recovery.
4. **Key-strength policy** (H5, H6) — small, self-contained changes to settings and import.
5. **Privacy and product decisions** (H16, H17, H18, H21, H24) — need a recorded decision first.
6. **Release engineering** (H1, H2, H22, H23) — everything above must ship in a signed, tested APK.

## Carried-forward decisions

These were recorded in round 1 and constrain this round. Do not reopen them without a new decision.

| # | Decision |
| --- | --- |
| D1 | The 30-second lock is a **foreground re-entry threshold**, not a secret-expiry timer. Closes H19. |
| D2 | Profile publication is **intentionally unattended** and may sign with stored keys while locked. |
| D4 | Publication stays **mandatory and automatic**; the creation screen carries **no** disclosure. Accepted risk. |
| D5 | Same-signature unattended KeyHive access is an **accepted requirement**; reads need no authentication. |
| D6 | Recovery is a **material matrix**: private key, or 24 combinations plus an existing remote copy; the passphrase is always required. |
| D7 | **Vault Key writes are read back** before a source counts toward quorum. Closes H7. |
| D8 | Nigori compatibility is a **restricted contract**: one key set per chain, no keybag rotation. |
| D9 | Passphrase strength is **not enforced outside in-app creation**. **Paste VaultKey** and **Recover VaultKey** accept any passphrase and show no warning: an imported passphrase is a property of an existing key the user did not choose, and refusing it would leave a user with a valid key unable to onboard or recover. Accepted business decision, recorded 2026-11-04. Closes H5 with no code change. |

---

## Critical

### C1 — Concurrent secure-storage writes could lose an account ✅

**Found by:** GPT (B01). **Resolved:** 2026-11-04, commit `8f8ab8b`.

Two writers could each write a valid account record while only one ID survived in the separate
`accounts_registry` index, orphaning the loser with no way to list, back up, or export it.

**Resolution:** the registry was removed rather than mutex-guarded. Each account is a single
self-describing encrypted record under `accounts_<createdAt>`, so every mutation is one atomic
preference edit. Enumeration scans the key prefix, orders by ascending account ID, and skips (without
deleting) records that fail to decrypt or whose authenticated `createdAt` disagrees with their key.
See `data/secure/SecureStorageService.kt`.

### C2 — OpenSSH parse accepts a seed that does not match its public key ✅

**Found by:** GPT (B03, reproduced with a synthetic key). **Tag:** CODE.

`crypto/SshKeyCodec.kt`, `parsePrivateBlob`: validation checked the duplicated check integers, the
two public-key copies, the 64-byte private field, and the padding — but never re-derived the Ed25519
public key from the 32-byte seed. A seed could therefore disagree with the advertised public key and
still parse successfully.

Two things are derived from the seed and presented as the same identity:

- the SSH public key shown and exported to the user (taken from the container header); and
- the recovery identity (`Seedify.toMnemonic(seed)` → NIP-06 Nostr key → `vkbckp` backup address).

AES-CTR gives no authentication over the SSH container, so a corrupt or crafted blob can pass a
"valid key, correct passphrase" check while those two identities diverge. The consequence is an
exported credential that no server accepts, or recovery under an identity the user never had.

**Resolution:** after extracting the seed, `parsePrivateBlob` re-derives its Ed25519 public key with
`Ed25519PrivateKeyParameters(seed, 0).generatePublicKey()` and requires it to equal the stored
public-key copies before returning success. `readEncryptedContainer` additionally requires the
encrypted private section to be a whole number of 16-byte AES blocks. `SshKeyCodecTest` gained
three regression tests built by XOR-flipping bytes of the golden key's *ciphertext* (AES-CTR is a
stream cipher, so a ciphertext bit flip is the same plaintext bit flip, needing no re-encryption):
a flipped seed byte, a flipped second check integer plus broken padding, and a length-widened
misaligned private section. Removing the `publicKeyOf(seed)` check makes the first test fail, which
confirms it is the seed/public binding — not an earlier structural check — that rejects the input.

Note on scope: the "mutate the public fields" variant in the original fix wording is already
rejected before reaching the new check — flipping the outer public blob makes `parsePrivateBlob`
fail with "OpenSSH public-key mismatch", and flipping only the inner copy breaks the
`privateAndPublic` layout check. The residual gap was specifically the seed, which no prior check
covered, so the seed test is the one that isolates the fix.

---

## High — credential integrity and recovery

### H4 — Vault Key recovery is not bound to the supplied recovery words

**Found by:** GPT (B09, reproduced). **Tag:** CODE. **Resolved:** 2026-11-04 (uncommitted).

`domain/VaultKeyRecoveryService.kt`: `normalizeAndParse` accepts the first candidate that decrypts.
It never re-derives that candidate's `vkbckp` mnemonic and compares it with the user's 24 words, so
successful decryption is treated as proof of ownership. Three related gaps:

1. Only the **first** NIP-44-decryptable relay payload, and the first matching Brave password, is
   tried per source — a corrupt first candidate masks a valid later one.
2. `enabledSources` filters on `restoreEnabled` but **ignores `trustedForRestore`**, so an untrusted
   custom source can supply the root of recovery.
3. Every recoverable copy is reduced to a single value before validation, so "decrypts" and
   "belongs to these words" are never separated.

**Fix:** derive `Seedify.toMnemonic(parsed.seed)` for each candidate and require it to equal the
supplied mnemonic before anything is persisted. Iterate all candidates newest-first through full
validation. Apply an explicit restore-trust policy. Report incompatible copies distinctly from
absence and keep checking the remaining sources. Add wrong-root/same-passphrase, corrupt-first, and
untrusted-source tests.

**Implemented:** `normalizeAndParse` now re-derives the candidate's `vkbckp` mnemonic
(`Seedify.toVaultKeyBackupWordIndices`) and throws a distinct `VaultKeyMismatchException` unless it
equals the supplied words — the backup mnemonic, not the raw seed mnemonic. `Lookup` carries every
decryptable copy per source instead of one; relay events sort by `createdAt` and Brave entries by
`version`, both newest-first. `enabledSources` now also requires `trustedForRestore`. A mismatch is
skipped and the scan continues; if no copy matches and at least one decrypted, recovery reports "found
but does not belong" rather than "not found". Tests: wrong-root/same-passphrase rejected and nothing
persisted, corrupt newest copy does not mask an older valid one, misbound newest copy does not mask an
older valid one, untrusted source never queried. Docs updated in
`docs/features/vault-key-setup.md` and `docs/technical/ssh-key-seed-derivation.md`.

### H5 — Imported Vault Key passphrases bypass the strength policy ✅

**Found by:** DS (High), OP (B4), GPT (medium). **Tag:** DECIDE. **Resolved:** 2026-11-04, D9.

`VaultKeyPassphrasePolicy` (minimum length 12, zxcvbn ≥ 3) is enforced only in
`ui/onboarding/createvaultkey/`. `OnboardingViewModel.performSetup` and `VaultKeyRecoveryService`
accept any passphrase, and both concern keys that are published to public relays where the
passphrase is the only remaining protection.

**Resolution (D9):** accepted as-is. No blocking, warning, or acknowledgement is added on
**Paste VaultKey** or **Recover VaultKey**. The passphrase on an imported key is a property of an
existing artifact the user did not choose; gating it would leave a user holding a valid key unable
to onboard, and the app has no in-app remedy (re-encrypting the container is an out-of-band
operation). Recovery cannot be gated at all, because the typed passphrase is the only thing that
opens the remote copy. This is a recorded business decision, not a deferred fix: the current
behaviour — enforcement on in-app creation only, documented in `docs/features/vault-key-setup.md`
(“Current limitations”) and `docs/technical/security-model.md` — is the intended behaviour.
No code or doc change was needed. If the weakness is to be removed rather than accepted later, the
remedy is re-encryption under a newly chosen, policy-checked passphrase, tracked separately from H5.

### H6 — bcrypt work factor settable to 1

**Found by:** OP (B4), GPT (medium). **Tag:** CODE + DECIDE.

`SshKeyCodec.SUPPORTED_BCRYPT_PBKDF_ROUNDS` starts at `1 shl 0 = 1` and `ui/settings/SettingsScreen.kt`
renders every value, so a user can create keys whose encrypted form leaves the device (Notes export,
clipboard, sibling-app KeyHive reads) protected by a single bcrypt round. The default of 128 is
unaffected.

**Fix:** establish a calibrated production floor (at least 64, ideally removing the setting for
release builds or gating it behind a debug flag). Record the floor as a decision so the doc and the
settings menu agree. Lowering the floor later is a compatibility question — old keys keep their
stored rounds.

### H10 — Unreadable records are indistinguishable from absent ones

**Found by:** DS (High), OP (H2), GPT (B02). **Tag:** CODE.

`KeystoreCryptoService.decrypt` returns `null` for a missing key, a malformed envelope, and a failed
authentication alike. `SecureStorageService.read` maps that `null` to "no record", and
`readAccounts` drops such records with `mapNotNull`. A lost Keystore key or a tampered record
therefore looks exactly like a fresh install: `getVaultKey() == null` routes the user to onboarding
instead of to recovery, and a vault with accounts can present as empty.

The audit also flagged that `SecureStorageService` commits with `apply()` and never inspects the
result, so a failed write is indistinguishable from a successful one.

**Fix:** introduce a three-state read result (absent / unreadable / valid) and surface "unreadable"
distinctly in the launch gate with a path into **Recover VaultKey**. Bind the ciphertext to the
record ID with AAD so a record cannot be relocated between slots. Use a checked commit where the
record is the root of recovery. Add tests for undecryptable records, Keystore key loss, and
disk-full/commit failure.

---

## High — backup truthfulness

### H11 — Workers report success after source failures; nothing retries them

**Found by:** DS (High), GPT (B08). **Tag:** CODE.

`background/BackupWorkers.kt`: `engine.consolidate(...)`, `consolidateAccount(...)` and
`consolidateVaultKey(...)` return a `ConsolidationResult`/failure set that the workers discard, so a
run with every source failing still returns `Result.success()`. The scheduler enqueues one-time work
only; persistence of a pending pair does not itself enqueue a retry, and targeted index failures do
not even create one. A transient outage can therefore leave backups incomplete until a later manual
action, while the recorded completion timestamp suppresses the next freshness check.

**Fix:** return `Result.retry()` (or enqueue bounded unique retry work) when the engine records
transient failures during a valid session; preserve pending intent across process death and requeue
on unlock. Do not let a fresh-but-incomplete cycle count as healthy. Add WorkManager tests for source
failure, connectivity loss, lock, process restart, and retry exhaustion.

### H12 — Brave responses fail open

**Found by:** GPT (B05, reproduced against a loopback fixture). **Tag:** CODE.

The read-back half of B05 is fixed (D7, commit `4954799`). What remains is response validation:

- `bravesync/proto/SyncMessages.kt`, `Response.from`: an absent `error_code` defaults to `0`
  (success). Chromium's protocol declares the absent default as `UNKNOWN`.
- `SyncMessages.parseCommit` accepts a commit with **zero** entry results, and
  `BravePasswordService.commit` iterates a nullable list that may be empty — so a response carrying
  no per-entry result is accepted.
- `BravePasswordService.list` accepts an absent updates body as an empty index via
  `PasswordReader.read`.

A loopback server returning HTTP 200 with an empty body was accepted as a successful index, Nigori
init, and commit. That is a false backup claim built from a malformed response.

**Fix:** require the expected response branch, an explicit acceptable status, and exactly the
expected number of successful entry results. Reject absent or ambiguous responses. Add
empty/truncated/wrong-branch/missing-entry tests.

### H13 — Remote inventories treated as complete without proof

**Found by:** GPT (B06, reproduced with an injected socket). **Tag:** CODE.

- `network/nostr/NostrRelayService.kt`: `fetchAll` results are used without requiring a successful
  EOSE. Quartz treats `onClosed`, `onCannotConnect`, and a timeout as terminal and returns whatever
  it collected, so an explicit `restricted:` refusal surfaces as a successful empty index.
- `BravePasswordService.list` performs a single `GET_UPDATES` with an empty progress token and
  ignores `changes_remaining` / `new_progress_marker`, so only the first server batch is ever seen.

Either way an incomplete snapshot is read as absence, which breaks the "unknown is not missing"
invariant the consolidation engine is built on: repair can publish unnecessarily, and a replacement
device can fail to discover records past the first batch.

**Fix:** expose typed EOSE/completion, rejection, disconnect, and timeout outcomes and treat an
incomplete read as UNKNOWN even when some events arrived. Implement Brave pagination to completion
with an explicit total budget. Test rejected subscriptions, partial streams, timeouts mid-stream,
multiple batches, and repeated markers.

---

## High — hostile input and responsiveness

### H14 — No CPU, memory, depth, or volume limits on hostile input

**Found by:** GPT (B07, reproduced metadata acceptance), DS (medium), OP (medium). **Tag:** CODE.

| Surface | Gap |
| --- | --- |
| `crypto/SshKeyCodec.readEncryptedContainer` | Accepts any `rounds > 0`; a synthetic container with `2,147,483,647` passed metadata validation. `parse` then runs bcrypt synchronously. |
| `bravesync/transport/BraveSyncTransport.post` | Buffers the whole body, then expands gzip with unbounded `readBytes()`. |
| `bravesync/proto/ProtoWire` | No field-count or group-recursion cap, so a crafted payload can exhaust the stack. |
| `network/nostr/NostrRelayService` + Quartz | No event-count or payload-volume limit; Quartz uses an unbounded incoming channel. |

A malicious pasted key, hostile relay, or crafted response can hang validation or crash the app —
and when it happens during recovery it can repeatedly prevent recovery.

**Fix:** cap the accepted bcrypt work factor on parse with an explicit compatibility error, bound
compressed and decompressed sizes, cap protobuf fields and group depth, cap event count and payload
volume. Add small-budget hostile-input tests; do not rely on network read timeouts to stop CPU or
decompression work.

### H15 — Expensive cryptography on the main thread

**Found by:** GPT (B11). **Tag:** CODE.

The generation path correctly dispatches off-main, but these synchronous calls run on the calling
thread: `OnboardingViewModel.performSetup` (SSH bcrypt + NIP-06 derivation inside
`viewModelScope`), `NewAccountViewModel.verifyPassphrase` → `AccountService.verifyVaultKeyPassphrase`,
`SecretCombinationsViewModel`, and `VaultKeyDetailScreen` passphrase verification. With a legitimate
high-round key — or a hostile one permitted by H14 — this freezes the UI and blocks timely
lock/error rendering.

**Fix:** make verification and derivation `suspend` APIs that dispatch to a bounded background
dispatcher, show a cancellable progress state, and never run KDF work in a click callback. Test
responsiveness with a high-round key on a low-end device.

---

## High — privacy and product decisions

### H16 — Default Brave source is Brave's production server, presented as Chrome

**Found by:** OP (B2). **Tag:** DECIDE → CODE.

`domain/BackupSources.kt` registers `default-brave-official` at `https://sync-v2.brave.com/v2` as a
non-removable, quorum-counting source, and `bravesync/transport/BraveSyncTransport.kt` sends
`User-Agent: Chrome MAC 144.1.86.142 (zenten-vault)` with Brave's service key. This is a terms-of-service
and legal risk that Brave can terminate unilaterally; if blocked, users silently lose one recovery
path.

**Decision needed:** obtain Brave's permission, or drop the official endpoint from the defaults. Then
either way send an honest User-Agent and document the dependency in
`docs/technical/brave-sync-backup-protocol.md`. Record as D10.

### H17 — Profile publication: unattended, undisclosed, retries unboundedly 🟡

**Found by:** OP (B3), GPT (B12), DS (medium). **Tag:** DECIDE (done) → CODE.

Decisions D2 and D4 accept unattended publication and decline an in-flow disclosure; the privacy
consequences are documented in `security-model.md`. Two code gaps remain, and both are the kind that
turn an accepted risk into an unbounded one:

1. `ProfileService.publishAssociatedAccountProfile` ends with `check(failures.isEmpty())` across
   seven relays, and `ProfilePublicationWorker` returns `Result.retry()` on any exception with no
   attempt cap. One permanently unavailable relay means repeated republication with fresh
   timestamps, indefinitely.
2. There is no per-relay completion tracking, so partial success is invisible to the user.

**Fix:** use a quorum rather than unanimity, track per-relay completion, classify permanent versus
transient failures, cap attempts, and expose the outcome without blocking account use. The Play Data
Safety answer still depends on release gate 4 (H23).

### H18 — Local cache exposes raw Nostr private keys to same-UID code 🟡

**Found by:** GPT (H03), DS (High, as the device-auth finding). **Tag:** DECIDE → CODE.

`data/model/Account.kt` persists a raw Nostr private key as hex, protected by Keystore AES-GCM but
**not** by the Vault Key passphrase and **not** bound to user authentication. The device-auth gate is
therefore a UX boundary, not a cryptographic one: any same-UID code that can use the Keystore
wrapper, or a forensic image with the Keystore unlocked, reads Nostr signing and
backup-decryption keys without the passphrase. D5 accepts this for the *KeyHive* surface (which sees
only the encrypted SSH container and public identity); it does not by itself approve the broader
exposure.

**Decision needed:** formally approve the threat model for the broader exposure, or minimize
resident secret-bearing caches / derive Nostr secrets inside an unlocked session. Do not claim
StrongBox — `KeystoreCryptoService` never checks or requests a hardware security level. Record as
D11.

### H21 — No account deletion, vault reset, or remote deletion

**Found by:** OP (H3), GPT (medium), DS (medium). **Tag:** DECIDE → CODE.

`BravePasswordService.delete` exists but is never called; there is no local delete/reset UI. Two
consequences: Play/GDPR deletion expectations are unmet, and incident response is impossible — a
compromised relay or key cannot be rotated or withdrawn.

**Fix:** add local account deletion and a vault reset that also clears session/cache/work state
(`SecureStorageService.clearAll()` currently leaves the Vault Key `StateFlow` stale and does not
clear WorkManager state), plus a remote-withdrawal path where the transport allows it. Document the
honest limit: relay records are replaceable events and uninstall cannot revoke `authorized_keys`
already installed on a server.

### H24 — Clipboard export is not marked sensitive and never expires

**Found by:** OP (H4), GPT (medium). **Tag:** CODE.

`ui/accounts/AccountDetailScreen.kt` copies the encrypted PEM with `ClipData.newPlainText` and no
`ClipDescription.EXTRA_IS_SENSITIVE`; nothing clears it afterwards. The payload is encrypted, but it
is offline-attackable secret-bearing material — and it is exactly the material whose protection H6
can weaken.

**Fix:** mark the clip sensitive, and clear it after a bounded interval when the app regains focus.
State the OS and keyboard-clipboard limits honestly in `sensitive-key-access.md`.

---

## High — release engineering and supply chain

### H1 — No release signing configuration or key custody

**Found by:** all three. **Tag:** PROCESS.

`app/build.gradle.kts` defines `buildTypes.release` with minify/shrink and **no `signingConfig`**; no
keystore or properties file is tracked, and CI has no signing step. The output today is
`app-release-unsigned.apk`. Signature identity is also the KeyHive trust boundary, which makes
certificate custody and any future key rotation particularly consequential.

**Fix:** add a signing config fed from CI secrets (or Play App Signing), document key custody and
rotation, and write an incident plan for a compromised signing key.

### H2 — Release variant never built, linted, or instrumented in CI

**Found by:** all three. **Tag:** PROCESS.

`.github/workflows/ci.yml` runs `testDebugUnitTest`, `assembleDebug`, `lintDebug` only. Release
assembly, release lint, and the two instrumented tests (`KeystoreCryptoServiceInstrumentedTest`,
`SecureStorageServiceInstrumentedTest`) never run — which is how the R8 failure in H3 shipped
silently. R8 keep-rule correctness for Hilt, WorkManager, Quartz reflection and JNI remains unproven
even now that the build succeeds.

**Fix:** add `assembleRelease` and `lintRelease` to CI, run the instrumented tests on an emulator
job, and smoke-test the minified APK with a create → backup → recover run before release.

### H3 — Release build fails under R8 ✅

**Found by:** DS, OP, GPT (B13/B1). **Resolved:** commit `a1f6d3a`.

Missing-class errors from Apache MINA's optional `javax.management`, `net.i2p` EdDSA, and slf4j
binding references. Fixed with five narrowly justified `-dontwarn` rules in `app/proguard-rules.pro`
(the referenced classes are on paths this app never executes). `:app:assembleRelease` and
`:app:lintRelease` now succeed. The R8 *runtime* correctness question is tracked under H2.

### H22 — Dependency review used declared, not resolved, versions

**Found by:** GPT (H04). **Tag:** HOUSE.

The catalog declares OkHttp 4.12.0, but `releaseRuntimeClasspath` resolves **5.3.2** through Quartz;
kotlin-stdlib resolves to 2.3.20 against 2.2.21 build plugins; the host test classpath carries
coroutines 1.10.2 and serialization 1.10.0. BouncyCastle 1.79 is several releases behind. Any
advisory review based on `libs.versions.toml` is reviewing the wrong code.

**Fix:** generate an SBOM from the resolved release classpath, record reachable/unreachable advisory
decisions against it, and track the BouncyCastle upgrade. The example CVEs the audit triaged (GOST
`G3413CTRBlockCipher`, FrodoKEM) are not on this app's call paths — that conclusion belongs in the
SBOM record, not in someone's memory.

### H23 — Device, interop, and support acceptance evidence is missing

**Found by:** all three (release gates 1, 2, 5). **Tag:** PROCESS.

No independent security assessment, no end-to-end interoperability run against the real default
destinations, no API 26–36 device matrix, and no tested support runbook for lost recovery material.
This is explicitly an open gate rather than an assertion that the paths are broken — but none of it
can be closed by code review.

**Fix:** assign owners and attach dated evidence per gate. The B14 device matrix (biometric strength
classes, PIN-only, weak-only + credential, OEM background restrictions, Doze, process death,
device-to-device transfer) and a clean-device restore drill are the minimum.

---

## Execution log

Newest first. Each entry records what changed, what was verified, and which docs moved.

### C2 — seed/public-key binding in OpenSSH parse

- `crypto/SshKeyCodec.kt`: `parsePrivateBlob` now re-derives the Ed25519 public key from the
  extracted seed and requires it to match the stored public-key copies; `readEncryptedContainer`
  now rejects a private section that is not a whole number of AES blocks.
- `SshKeyCodecTest`: three new hostile-input tests (flipped seed byte, flipped second check integer,
  broken padding, misaligned private section) built from ciphertext-level mutations of the golden key.
- **Verified:** all 17 tests in `SshKeyCodecTest` pass. Negative control run with the new `require`
  removed — `rejects_an_openssh_key_whose_seed_does_not_match_its_public_key` fails, confirming the
  test exercises the fix rather than an earlier structural check.
- **Docs moved:** `docs/technical/ssh-key-seed-derivation.md` (new "Seed/public-key binding" section,
  block-alignment note, expanded verification list).

**Note on user-visible behavior:** a seed/public mismatch does not reuse the "incorrect passphrase"
message, because it is detected *after* the check integers prove the passphrase correct. It raises
its own `IllegalArgumentException("OpenSSH private key does not match its public key")`, which
`OnboardingViewModel.performSetup` renders through `error.message` on the paste step and which fails
before anything is persisted (so it correctly does not offer the stored-key retry). The wording is
technical, matching the existing `"OpenSSH public-key mismatch"` message; softening it is a UX
change, not part of this fix.

## Notes

- Line numbers in the audit reports have drifted; anchor on the quoted symbol or section heading.
- Items C1, H3, H5, H7, H8, H9, H19, H20 are already closed. They stay in the table so the backlog
  stays auditable and so no later change silently re-opens them. H5 is closed by decision (D9), not
  by code: do not add an import warning later without a new decision.
- H17 and H18 are recorded as decided (D2/D4, D5) in their *policy* half; only their code half is
  open. Do not re-litigate the decisions while fixing the code.
- Any fix that changes user-visible behavior, a security property, or a documented contract must
  update the matching document under `docs/` in the same change, per `AGENTS.md`.
