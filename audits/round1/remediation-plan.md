# Audit remediation plan — documentation drift backlog

- **Created:** 2026-11-03
- **Source:** consolidated from the three production-readiness audits in this directory
  (`production-readiness-review.md`, `production-readiness-review-2026-11-02-gpt-6.1-sol.md`,
  `production-readiness-review-2026-11-03-opus-5.5.md`)
- **Reviewed commit:** `f38cd51`
- **Scope:** the 25 distinct doc-vs-code discrepancies. This is the working checklist; resolve one
  item at a time.

## Legend

| Tag | Meaning |
| --- | --- |
| **EDIT** | The doc is stale and the code is correct. Update the doc. |
| **CODE** | The doc states the intended contract and the code violates it. Fix the code; the doc stays. |
| **DECIDE** | A product/security policy decision is needed before either side is written. |
| **HOUSE** | Comment, checklist, or other non-behavioral hygiene. |
| **BLOCKED** | Depends on another fix (release build/signing) landing first. |

Status: ✅ done · ⬜ pending · 🔄 in progress

Audit shorthand: **DS** = DeepSeek v4-flash, **GPT** = gpt-6.1-sol, **OP** = Opus 5.5.

## Ordering rationale

1. Security guarantees the docs overstate (highest risk of a wrong security decision).
2. Backup/recovery claims that are not true of the code.
3. Stale behavioral and UI descriptions.
4. Inconsistencies, comments, and release-process hygiene.

Within an item, prefer the resolution that preserves the documented security contract. Do not weaken
a doc to match a bug unless that weaker behavior is explicitly accepted.

## Recorded decisions

| # | Decision | Date |
| --- | --- | --- |
| D1 | The 30-second lock is a **foreground re-entry threshold**, not a timer. Trigger is background-only (no foreground-idle lock). A consolidation job that started while unlocked keeps its own passphrase snapshot, runs to completion, and zeroes it in `finally`. Queued-but-unstarted work fails closed and waits for the next unlock. | 2026-11-03 |
| D2 | Automatic profile publication is **intentionally unattended**: it signs kind-0 and kind-10050 with the account's stored Nostr key and may run while the UI is locked. Disclosure/opt-in remains open under item #4. | 2026-11-03 |
| D3 | The strong-biometric policy is kept. API 26–27 and 30+ use `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` in one prompt; API 28–29 uses a `BIOMETRIC_STRONG`-only prompt with the keyguard device-credential confirmation as the fallback. Class 2 biometrics are never accepted. | 2026-11-03 |
| D4 | Profile publication stays **mandatory and automatic**, and the **Create New Account** screen carries **no publication disclosure**. The creation UI stays minimal; the public nature of the account name is documented in the security model, feature docs, and stakeholder docs instead. This is an accepted risk, not a claim that disclosure is unnecessary. | 2026-11-03 |
| D5 | Same-signature unattended KeyHive access is an **accepted requirement**. Every app with the trusted certificate may read the encrypted OpenSSH container and public Nostr key without authentication. Protection is the encryption itself, plus signing-key custody and sibling-app review. Review topic 8 (separate auth-bound domain) is closed as not planned. | 2026-11-03 |
| D6 | Recovery wording is stated as a **material matrix**, not a categorical promise. Two interchangeable material sets restore the Vault Key — the private key, or the 24 Secret Combinations plus an existing remote copy — and the **passphrase is always required**. A forgotten passphrase has no reset path; a key held by neither material set cannot be recreated. "Recover a lost Vault Key private key" is therefore *implemented, conditional*, while "recover a forgotten passphrase" stays out of scope. | 2026-11-03 |
| D7 | Vault Key writes are read back before a source counts toward quorum. An acknowledgement alone is necessary but not sufficient; an acknowledged-but-absent or unverifiable read-back fails the source and queues a `VAULT_KEY_QUORUM` retry. This keeps the documented read-back contract rather than narrowing it for the root-of-recovery record. | 2026-11-03 |
| D8 | Nigori compatibility is a **restricted contract**: one derived key set per chain (fixed PBKDF2 salt or a chain salt for scrypt), `keyName` written but never used to select keys, and no keybag-rotation support. Rotated/foreign-key records surface as undecryptable and invalid, never as absence. The protocol doc states this scope and the work a future implementation would need. | 2026-11-03 |

---

## Wave 1 — Security guarantees the docs overstate

| # | Doc(s) | Drift | Resolve | Found | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | `security-model.md` Current safeguards / Non-promises; `device-authentication.md`; `product-status.md`; `architecture.md`; `flowcharts.md`; `screens-and-states.md` | Docs implied the passphrase is dropped at 30 s. Code evaluates the boundary only on foreground return and leaves in-flight worker snapshots untouched. | **EDIT** — done per D1 | DS, OP, GPT | ✅ |
| 2 | `product-status.md` device-auth row; `security-model.md`; `device-authentication.md`; `architecture.md`; `flowcharts.md` | `product-status` said background backup "remain[s] unattended"; consolidation actually requires the session. `ProfilePublicationWorker` signs with the stored key while locked. | **EDIT** — done per D2 | OP, GPT | ✅ |
| 3 | `security-model.md:27`; `device-authentication.md` Android compatibility | Claims strong-biometric-or-credential on every API. Pre-API-30 no-`CryptoObject` prompt resolves to `BIOMETRIC_WEAK \| DEVICE_CREDENTIAL`. | **CODE** — done per D3: explicit authenticator set per API; strong-only prompt + keyguard credential fallback on API 28–29 | GPT (H01) | ✅ |
| 4 | `security-model.md` trust boundaries; privacy/Data Safety docs | Kind-0 name + kind-10050 relay list published to 7 public relays; no security-model row and no in-flow disclosure. | **EDIT** — done per D4: publication documented, in-flow disclosure declined | OP, GPT (B3/B12) | ✅ |
| 5 | `security-model.md:55` (review topic 8) | Same-signature KeyHive exposure is listed as an open question, never recorded as an accepted decision. | **EDIT** — done per D5: decision recorded, topic 8 closed | DS | ✅ |
| 6 | `vault-key-setup.md:14–18`; `encrypted-backup.md:16–17`; `architecture.md` Core lifecycle | Docs require Nostr+Brave quorum before setup completes; generated setup completes unconditionally after combination verification. | **CODE** (audit B04) — done | GPT | ✅ |
| 7 | `product-overview.md:37`; `scope-and-roadmap.md:30`; `backup-and-recovery.md:45 vs :56`; `user-journeys.md` Important limitation | Categorical "cannot recover a lost Vault Key"; the same doc says Recover VaultKey restores it with Secret Combinations + passphrase. | **DECIDE** wording → **EDIT** to a recovery-material matrix — done per D6 | GPT | ✅ |

## Wave 2 — Backup/protocol claims that are not true of the code

| # | Doc(s) | Drift | Resolve | Found | Status |
| --- | --- | --- | --- | --- | --- |
| 8 | `brave-sync-backup-protocol.md` Verify + failure table | Read-back presented as applying to acknowledged writes generally; Vault Key writes have no read-back. | **CODE** — done per D7: `verifyVaultKeyWrite` gates quorum | GPT | ✅ |
| 9 | `vault-key-setup.md:16`; `encrypted-backup.md:17`; `product-status.md:9` | Promise stored-key retry without re-pasting; initial import failure never sets `canRetryStoredVaultKey`, restart routes to Get Started. | **CODE** (audit B10) — done: durable pending-import flag, retry-only screen, launch resume | GPT | ✅ |
| 10 | `brave-sync-backup-protocol.md:84` | "Presence checks do not decrypt or compare payloads"; `PasswordReader` decrypts the outer Nigori payload during index. | **EDIT** — done: outer Nigori decrypt vs deferred inner OpenSSH validation | GPT | ✅ |
| 11 | `brave-sync-backup-protocol.md:17,19,152,158` | Key-name/keybag rotation and random per-chain salt implied; init uses a fixed PBKDF2 salt and one derived key set, ignoring `keyName`. | **DECIDE** → restricted contract per D8, then **EDIT** — done | GPT | ✅ |
| 12 | `brave-sync-backup-protocol.md:226` | "Credentials and sessions never cross server boundaries"; the same mnemonic derives the same auth identity at every compatible endpoint. | **EDIT** — done: separate transport objects, not independent identities | GPT | ✅ |

## Wave 3 — Stale behavioral/UI descriptions

| # | Doc(s) | Drift | Resolve | Found | Status |
| --- | --- | --- | --- | --- | --- |
| 13 | `architecture.md` App-entry safety gate | Attributes startup consolidation/precondition checking to `VaultApplication`; the class explicitly has no start-time consolidation. | **EDIT** — done: `VaultApplication` excluded from the policy explicitly | DS, GPT | ✅ |
| 14 | `architecture.md:48` | "Four fixed defaults"; `BackupSources.kt` defines five. | **EDIT** — done: five, named as two relays + three Brave endpoints | GPT | ✅ |
| 15 | `product-status.md:8,19,23` | References `ui/Vault Key/`, `ui/sources/`, a pre-setup source list/add UI, and "at most three" relays — none exist. | **EDIT** — done | DS, OP, GPT | ✅ |
| 16 | `security-model.md:39`; `ssh-key-seed-derivation.md` | Says only initial creation uses `vkbckp` and later Vault Key backup uses the original-seed identity; the factory uses `vkbckp` for every Vault Key run. `vault-key-setup.md` is already correct. | **EDIT** — done: split by record kind; stale `SetupService.kt` comment fixed too | OP, GPT | ✅ |
| 17 | `screens-and-states.md:108` | Generated VaultKey "never placed … in storage, discarded when the wizard leaves"; it is committed to secure storage before backup/export. | **EDIT** — done: passphrase stays in memory, key is committed before backup | GPT | ✅ |
| 18 | `sensitive-key-access.md`; `scope-and-roadmap.md:19,26` | Describe a "Show private key" reveal dialog and call export "Planned / reveal-copy only"; `AccountDetailScreen` offers passphrase-gated Export to Notes and Export to Clipboard. | **EDIT** — done: reveal language removed entirely, export flow documented | GPT | ✅ |
| 19 | `user-journeys.md:32` + account-inspection expectations | Says generated setup "cannot complete because the post-verification step is not implemented" (it exists) and that Account Details never exports private material (it does). | **EDIT** — done; the "not implemented" claim was already gone, inspection expectations corrected | GPT | ✅ |

## Wave 4 — Inconsistencies and hygiene

| # | Doc(s) | Drift | Resolve | Found | Status |
| --- | --- | --- | --- | --- | --- |
| 20 | `associated-account-management.md:11 vs :32` | Same file says 8-character minimum in one step and 3 in another; code enforces 3–128. | **EDIT** — done: step 3 now says 3 to 128 | GPT | ✅ |
| 21 | `app/src/test/resources/golden/README.md:10,53` | Says SSH seed extraction is unbuilt (it is built) and calls NIP-44 vectors ChaCha20-Poly1305 (they are not). | **EDIT** — done: both halves gated; NIP-44 described as ChaCha20 + HMAC-SHA256 | GPT | ✅ |
| 22 | `flowcharts.md:294` | Unqualified "the Vault Key is never restored from a remote copy"; the Recover VaultKey flow does exactly that. | **EDIT** — already resolved by #7 (2026-11-03); verified, no remaining unqualified claim | GPT | ✅ |
| 23 | `di/NetworkModule.kt:14` | Comment says "PoC relay set" in a production-track build. | **HOUSE** — done: comment states the fixed relay set and missing configuration UI | OP | ✅ |
| 24 | `code-review-checklist.md` | Missing `KeystoreCryptoService.kt`, `VaultKeySession.kt`, `ProfileService.kt`, `ProfilePublicationWorker.kt`, `VaultKeyRecoveryService.kt`. | **HOUSE** — closed as not needed: the untracked, gitignored checklist was deleted by owner decision rather than extended | OP | ✅ |
| 25 | `build-and-test.md` | Does not state that the release variant is unbuildable/untested; CI section implies debug-only coverage. | **BLOCKED** — R8 half fixed; signing still absent. Docs now state the post-R8 status and the missing release gate | DS | ✅ |

### Code items outside the 25-doc backlog

These are fix-the-code findings from the audit reports that are not doc-drift rows.

| # | Finding | Resolve | Status |
| --- | --- | --- | --- |
| B01 | Concurrent secure-storage writes could lose an account from the registry (GPT, Critical). | **CODE** — removed the separate account registry rather than adding a mutex. Each account is one self-describing encrypted record under `accounts_<createdAt>`, so every mutation is a single atomic preference edit and no read-modify-write of an index remains to lose. Listing is derived by prefix scan, ordered by ascending account ID; records that fail to decrypt/parse or whose authenticated `createdAt` disagrees with their key are skipped and left on disk. No migration code by owner decision (unreleased app). | ✅ 2026-11-04 |

---

## Execution log

### #1 — 30-second lock semantics ✅ (2026-11-03)

Changed six files, no code changes:

- `docs/technical/security-model.md` — safeguards, passphrase bullet, non-promises
- `docs/features/device-authentication.md` — behavior step 4, current limitations
- `docs/technical/product-status.md` — device-auth row
- `docs/technical/architecture.md` — device-authentication gate section
- `docs/stakeholder/flowcharts.md` — lock paragraph
- `docs/stakeholder/screens-and-states.md` — Vault lock section

Recorded contract: background-only trigger; boundary evaluated on foreground return; no timer and no
foreground-idle lock; in-flight consolidation keeps and zeroes its own snapshot; unstarted work fails
closed and waits for the next unlock.

### #2 — unattended background work ✅ (2026-11-03)

Documented publication as intentionally unattended (D2), no code changes:

- `docs/technical/product-status.md` — split consolidation (session-gated) from KeyHive/publication
- `docs/technical/security-model.md` — publication safeguard, review-topic premise, non-promise
- `docs/features/device-authentication.md` — "does not protect against" and limitations
- `docs/technical/architecture.md` — gate section
- `docs/stakeholder/flowcharts.md` — lock paragraph

### #3 — pre-API-30 strong-biometric claim ✅ (2026-11-03)

Decision D3: keep the strong-biometric contract and fix the prompt rather than documenting a
weaker one. Verified against AndroidX Biometric 1.1.0 sources: the combined
`BIOMETRIC_STRONG | DEVICE_CREDENTIAL` set is valid on API 26–27 and 30+ but rejected on API 28–29;
`BIOMETRIC_STRONG` alone is valid on every supported level and forces Class 3 via a fake
`CryptoObject` on API 23–29. Code changes:

- `ui/auth/AppUnlockCoordinator.kt` — explicit combined set on API 26–27 and 30+; strong-only prompt
  with a "Use device PIN" negative button on API 28–29; per-API capability check; credential
  fallback on prompt errors that cannot complete.
- `MainActivity.kt` — launches `KeyguardManager.createConfirmDeviceCredentialIntent` through an
  `ActivityResultLauncher` when the coordinator requests it.
- `DeviceLockPolicyTest.kt` — prompt-kind matrix, fallback-error set, and strong-availability
  mapping.

Docs: `device-authentication.md` Android compatibility, `architecture.md` gate description,
`security-model.md` safeguard clause.

Manual verification remains part of the B14 device gate: API 28 and 29 with strong+PIN, PIN-only,
and weak-biometric-only+credential.

### #4 — profile publication disclosure ✅ (2026-11-03)

Decision D4: publication remains mandatory and automatic, and the creation screen gains no
disclosure. Documentation-only change:

- `docs/technical/security-model.md` — new trust-boundary row, a safeguard describing the exact
  published fields and relay set, and an explicit non-promise that the profile is public
- `docs/stakeholder/backup-and-recovery.md` — privacy expectation covering publication
- `docs/features/associated-account-management.md` — creation step and failure expectations
- `docs/technical/product-status.md` — associated-account generation row
- `docs/stakeholder/screens-and-states.md` — no change required (no UI disclosure added)

B12's UX objection is recorded, not resolved: a user can still mistake the account name for a
private vault label. Item #4's Data Safety text is descriptive; the Play Data Safety form still
depends on gate 4 of the release gates.

### #5 — same-signature KeyHive exposure ✅ (2026-11-03)

Decision D5: accept unattended same-signature access as a product requirement and record it, rather
than building an authentication-bound domain. Documentation-only change:

- `docs/technical/key-hive-provider.md` — new "Accepted decision (D5)" section covering what a
  sibling app receives, what protects it, what falls outside the boundary, and the signing-custody
  consequence
- `docs/technical/security-model.md` — review topic 8 closed as not planned; the non-promise now
  states the decision, the narrow field set, and that reads need no authentication

### #6 — generated setup bypasses the Nostr+Brave quorum ✅ (2026-11-03)

Resolved as **CODE** (audit B04), keeping the documented contract rather than weakening the docs.

- `domain/SetupService.kt` — `persistGeneratedVaultKeyAndBackup` returns a `VaultKeySetupState`
  and persists the quorum outcome instead of swallowing it; new `completeGeneratedVaultKeySetup`
  refuses completion without a recorded quorum; new `retryGeneratedVaultKeyBackup` re-attempts
  every source and records the fresh outcome. Quorum failure text is now one shared helper.
- `data/kv/AppStorageService.kt` — new durable `vaultKeyQuorumReached` flag, cleared by
  `clearSetupComplete()` so a restarted setup cannot inherit a previous backup claim.
- `ui/onboarding/createvaultkey/` — the final **Secret Combinations** verification step attempts
  completion, and when quorum is missing replaces the picker with an explanation plus
  **Retry VaultKey backup**.
- `domain/VaultKeyRecoveryService.kt` — recovery sets the flag, since the key copy was just read
  back from a source.
- Tests: `SetupServiceTest` (generated backup records quorum; completion refused/allowed;
  retry records a negative result), `CreateVaultKeyViewModelTest` (blocked vs. completing),
  `VaultKeyRecoveryServiceTest`, `AppStorageServiceTest`.
- Docs: `vault-key-setup.md`, `encrypted-backup.md`, `backup-status-and-reconciliation.md`,
  `architecture.md`, `flowcharts.md`, `screens-and-states.md`, `user-journeys.md`,
  `product-status.md`.

### #7 — lost-Vault-Key recovery wording ✅ (2026-11-03)

Decision D6: neither claim was deleted. Both are true, so the docs now separate *material* from
*passphrase* and state which combination restores what. No code changes; verified against
`VaultKeyRecoveryService.recover`, `SetupService.performSetup`, `Seedify.toVaultKeyBackupWordIndices`,
and `SecureStorageService` (the passphrase is never persisted).

- `docs/stakeholder/backup-and-recovery.md` — **What users must retain** rewritten as a
  four-row matrix (key+passphrase, combinations+passphrase, combinations only, key only), plus the
  "private key is not strictly required / passphrase always is" summary; the "cannot recover lost
  material" limitation and the recovery-expectation paragraph were narrowed to match.
- `docs/stakeholder/product-overview.md` — boundary paragraph distinguishes the passphrase
  (no reset) from the private key (restorable with combinations + passphrase).
- `docs/stakeholder/scope-and-roadmap.md` — the single out-of-scope row split into
  *forgotten passphrase* (out of scope) and *lost private key* (implemented, conditional).
- `docs/stakeholder/user-journeys.md` — journey 5 limitation scoped to associated accounts;
  step 2 now names the Secret Combinations path.
- `docs/technical/security-model.md` — non-promise narrowed to material lost outright.
- `docs/stakeholder/flowcharts.md`, `docs/features/automatic-account-recovery.md`,
  `docs/features/backup-status-and-reconciliation.md` — "never restored" scoped to consolidation,
  naming **Recover VaultKey** as the only read-back path.

### #8 — Vault Key write read-back ✅ (2026-11-03)

Decision D7: keep the documented read-back contract and verify the root-of-recovery record, rather
than narrowing the doc.

- `domain/AccountConsolidationEngine.kt` — new `verifyVaultKeyWrite` re-indexes the source after an
  acknowledged write and requires `BackupAccountId.VaultKey` to be visible. A mismatch marks
  `REMOTE_REJECTED` (`readBackMismatch()`), queues a `VAULT_KEY_QUORUM` retry, and does not count the
  source toward quorum; a failed re-index marks the source `UNKNOWN` and likewise queues a retry.
- `AccountConsolidationEngineTest` — call-sequence expectations updated (index → write → re-index);
  new cases for acknowledged-but-absent, visible-after-write, and read-back index failure.

### #9 — durable imported-setup retry ✅ (2026-11-03)

Resolved as **CODE** (audit B10), keeping the documented retry-without-reimport contract.

- `data/kv/AppStorageService.kt` — durable `importedVaultKeySetupPending` flag, set only by the
  import path and cleared by both setup-terminal methods.
- `domain/SetupService.kt` — `performSetup` records the flag immediately after the local commit and
  reports post-persistence failures as `VaultKeyPersistedException` (parse/derivation failures are
  distinguishable); `hasPendingImportedVaultKeyBackup` exposes the launch-resume state.
- `ui/onboarding/OnboardingViewModel.kt`, `SshSetupScreen.kt` — retry eligibility restored from
  durable state on process recreation; the screen renders a retry-only state (no paste/passphrase
  steps, no Back arrow) once the key is committed.
- `ui/root/RootScreen.kt` — incomplete setup with the pending flag and no create-step marker routes
  to the stored-key retry instead of Get Started. Create-flow markers still take precedence.
- Tests: `SetupServiceTest`, `AppStorageServiceTest`, new `OnboardingViewModelTest` and
  `RootViewModelTest`.
- Docs: `vault-key-setup.md`, `encrypted-backup.md`, `product-status.md`, `flowcharts.md`,
  `screens-and-states.md`.

### #10–#12 — Brave Sync protocol doc ✅ (2026-11-03)

Documentation-only change to `docs/technical/brave-sync-backup-protocol.md`, verified against
`PasswordReader`, `NigoriCrypto`, `NigoriInitializer`, and `BraveSyncAuth`:

- **#10** — presence checks are split into the outer Nigori decryption performed on every index and
  the inner SSH validation deferred to restoration.
- **#11** — decision D8 recorded as a "Compatibility scope and known limitations" section: one key
  set per chain, fixed PBKDF2 salt (scrypt salt only when the chain specifies scrypt), `keyName`
  written but never used for selection, no keybag rotation; foreign-key records surface as invalid,
  not absent.
- **#12** — endpoint boundary reworded: transport objects and in-memory sessions are per endpoint,
  but the derived identity is the same value at every compatible endpoint.

Verification: `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-build-cache
--rerun-tasks` — 225 tests, 0 failures, 0 errors.

### #13–#19 — stale behavioral/UI descriptions ✅ (2026-11-03)

Wave 3 resolved as documentation-only except for two comment/label corrections found during
verification. Every claim was re-checked against the code before it was written.

- **#13** — `architecture.md` App-entry safety gate: removed the false claim that `VaultApplication`
  runs startup consolidation under the precondition. `VaultApplication` is now explicitly outside
  that policy (legacy-state clearing and the WorkManager factory only); the doc points at the
  post-unlock enqueue instead.
- **#14** — `architecture.md` Source-oriented model: "Four fixed defaults" → five, named as two
  Nostr relays and three Brave Sync servers so the count is checkable against `BackupSources.kt`.
- **#15** — `product-status.md`: dropped the nonexistent pre-setup source list/add UI and the
  `ui/sources/` path, removed the "at most three pre-setup relays" sentence, and corrected
  `ui/Vault Key/` to `ui/vaultkey/VaultKeyDetailScreen.kt`.
  `backup-status-and-reconciliation.md` had the same wrong path in its UI reference.
- **#16** — `security-model.md` and `ssh-key-seed-derivation.md` now split by record kind: every
  Vault Key record (`kind` 37801) operation — create flow, imported setup, retries, later
  consolidation — uses the `vkbckp` identity; associated-account records (`kind` 37802) use the
  stored original-seed identity. The related bullet claiming a private-key disclosure dialog also
  went with #18.
- **#17** — `screens-and-states.md`: the generated VaultKey is committed to keystore-backed
  storage immediately after passphrase verification, before backup/export; only the passphrase and
  the non-secret step marker stay in memory/DataStore respectively. The old "changing the
  passphrase discards the generated key" claim was removed: the passphrase steps are unreachable
  after generation because the wizard blocks Back, and no caller removes the stored key.
- **#18** — decision: document the existing flow and **remove plaintext-reveal language
  entirely** (neither Planned nor Out of scope — plaintext is simply never rendered).
  `sensitive-key-access.md` rewritten around the public identifiers plus the two passphrase-gated
  encrypted exports; `scope-and-roadmap.md` now records passphrase-gated export as Implemented and
  the export/share workflow as Partial.
- **#19** — the "cannot complete because the post-verification step is not implemented" sentence
  was already gone. The account-inspection expectations were the live drift: they now state that
  display and tap-to-copy values stay public-only while the two voluntary exports release only the
  passphrase-encrypted key. Journey 3 lists the exports and journey 2's step 4 no longer names a
  **Cancel** button the screen does not have.

Code corrections found while verifying (outside any item's doc scope):

- `domain/SetupService.kt` — the comment above `persistGeneratedVaultKeyAndBackup` claimed only the
  create flow used the `vkbckp` identity. The run context derives it for every Vault Key run;
  reworded to say why the identity is still passed explicitly.
- `ui/vaultkey/VaultKeyDetailScreen.kt` — the retry button rendered `Retry vaultKey setup` while
  every document cited **Retry Vault Key setup**; the label now matches.

Cross-document sweep: no remaining `ui/Vault Key`, `ui/sources`, "four fixed defaults", "at most
three relays", "Show private key", or "My Accounts" references (the code titles the screen
**Accounts**).

### #20–#25 — inconsistencies and hygiene ✅ (2026-11-03)

- **#20** — `docs/features/associated-account-management.md` step 3: account-name minimum
  corrected from 8 to 3, matching `AccountService.MIN_ACCOUNT_NAME_LENGTH`, the UI hint in
  `NewAccountScreen.kt`, and the failure-expectations bullet. Whole-`docs/` sweep found no other
  disagreeing account-name bound; passphrase-length statements (12-character minimum) were left
  alone as a separate concept.
- **#21** — `app/src/test/resources/golden/README.md` rewritten against the code:
  - The status block no longer calls SSH seed extraction unbuilt. Both halves are gated:
    `QuartzParityTest` for the Nostr side, `SshKeyCodecTest` for `parse(...).seed` against
    `seedHex`.
  - The NIP-44 section now names the real v2 primitives — secp256k1 ECDH, HKDF-SHA256 with salt
    `nip44-v2`, ChaCha20, and a 32-byte HMAC-SHA256 MAC — instead of ChaCha20-Poly1305. Verified
    against the NIP-44 spec and the Quartz 1.08.0 `Nip44v2.kt` sources.
  - Also fixed while verifying: a table split by a mis-placed paragraph, the dangling
    `implementation-plan.md` reference, "React" → "React Native" for `../vault`, an overstated
    claim about which NIP-44 blocks `QuartzParityTest` asserts, and a regeneration caution (the
    generator still embeds the older, unknown-passphrase `encryptedEd25519Pem` fixture).
- **#22** — no edit required. Wave 1 item #7 already scoped every such sentence to consolidation;
  a full-docs sweep found no unqualified "never restored from a remote copy" claim, and the
  `flowcharts.md` caption, diagram, and `VaultKeyRecoveryService` behavior agree.
- **#23** — `di/NetworkModule.kt` comment reworded from "PoC relay set" to describe the fixed,
  build-independent relay set and the absent configuration UI.
- **#24** — closed as **not needed** by owner decision. `code-review-checklist.md` was untracked
  and gitignored; it was deleted rather than extended.
- **#25** — the R8 half of the blocker was fixed:
  - `app/proguard-rules.pro` — added the five `-dontwarn` rules that Apache MINA and slf4j require
    (`javax.management.MBeanException`, `javax.management.ReflectionException`, the two `net.i2p`
    EdDSA types, and `org.slf4j.impl.StaticLoggerBinder`), each with a comment explaining why the
    reference is an optional path this app never executes.
  - Verified: `./gradlew :app:assembleRelease` now **succeeds** (previously failed at
    `minifyReleaseWithR8`) and `./gradlew :app:lintRelease` succeeds.
  - `docs/technical/build-and-test.md` — new "Release build status" section records that the
    variant now compiles and minifies, that the output is `app-release-unsigned.apk`, and that
    **two gaps remain**: no `signingConfig`, and no on-device smoke test of the minified build.
    The CI section now states plainly that `assembleRelease`/`lintRelease` are not workflow steps,
    and the pre-PR checklist adds them for dependency, proguard, JNI, and reflection changes.
  - **Signing stays open**, so #25 is recorded as the R8 fix plus accurate documentation rather
    than a complete release-gate closure.

Verification: `:app:testDebugUnitTest` (225 tests, 0 failures), `:app:assembleRelease`, and
`:app:lintRelease` all pass.

### Next up

Wave 4 is complete. The remaining production-readiness gaps are the audit's own release gates —
notably **release signing/key custody** (item #25's open half), the B14 on-device biometric
matrix, and an on-device smoke test of the minified release APK.

## Notes

- Audit line numbers may drift as edits land; anchor on the quoted text or section heading.
- Wave 2 code items **8** and **9** are resolved; their docs now describe the enforced behavior
  rather than the previous intent.
- Item **18** was decided on 2026-11-03: document the existing export flow and remove plaintext
  reveal language rather than add a reveal dialog. Item **25** was resolved by fixing the R8
  failure first and only then documenting the remaining signing and on-device gaps — the docs do
  not bless the current release build as shippable.
- Item **24** was closed by deleting the gitignored, untracked checklist rather than extending it.
- These edits are currently uncommitted.
