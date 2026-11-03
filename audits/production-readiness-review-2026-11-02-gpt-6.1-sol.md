# ZenTen Vault — Production-readiness review

- **Review date:** 2026-11-02
- **Model used:** `gpt-6.1-sol`
- **Repository:** `vault-kotlin`
- **Reviewed commit:** `f38cd514798c2336c68a3712cd86fa8baa19d14c`
- **Application:** `app.zenten.vault`, version `0.0.1-dev.1`, versionCode `1`, minSdk `26`, compile/target SDK `36` (`app/build.gradle.kts:11–19`).
- **Review standard:** the five production release gates in `docs/stakeholder/scope-and-roadmap.md:36–40`, not a prototype standard.
- **Review mode:** source/configuration review, host builds/tests/scanners, and temporary synthetic contract probes. No production-service writes, credential changes, commits, pushes, destructive device commands, or source modifications were performed. This report is the sole requested repository addition from this review; the pre-existing audit report was not used as evidence or overwritten.

## 1. Verdict

# DO NOT SHIP

The host test suite passes, but the production release build fails, and synthetic probes reproduce defects in account persistence, SSH-key validation, recovery identity validation, and backup success reporting. The repository also lacks evidence that its required real-service, device, release-operations, and support acceptance gates have closed. These are substantive credential-integrity/recovery and release-engineering issues in a production-track application, not objections to its product direction.

**Confidence:** high in the reproduced findings and build results; medium-to-high in source-traced lifecycle/protocol findings; no claim of complete security certification, exhaustive vulnerability coverage, or successful live interoperability.

### Evidence terminology

- **Read:** traced actual implementation and relevant documentation/test code.
- **Built/tested:** executed Gradle or scanners; cached results are identified separately from fresh execution.
- **Probed:** exercised compiled application classes with synthetic data outside the repository. Some probes replace platform persistence or remote transports with controlled doubles; those boundaries are stated explicitly.
- **Unverified:** requires a real device, supported remote deployment, external administration, or additional assessment not completed here.

Paths below are repository-relative. For long Kotlin paths, references such as `domain/SetupService.kt` mean `app/src/main/java/app/zenten/vault/domain/SetupService.kt`. Test paths beginning with `domain/`, `crypto/`, etc. refer to `app/src/test/java/app/zenten/vault/` when explicitly called tests. Dependency-source references identify the resolved library and symbol rather than pretending that library code is tracked in this repository.

## 2. Release-gate scorecard

| Required gate | Result | Evidence and remaining acceptance condition |
| --- | --- | --- |
| **1. Independent security assessment of cryptography, storage, backups, and user-facing safety controls** | **Partially met** | This review supplies independent source/host evidence, including reproduced defects. AES-GCM, NIP-44 and deterministic vectors are present, but blockers B01–B11 remain. Keystore/device extraction, Android lifecycle enforcement, native release execution, and adversarial end-to-end recovery remain unverified. An AI-assisted repository review is not a substitute for completed specialist assessment and remediation retest. Sources: `docs/technical/security-model.md:46–55`; implementations cited below. |
| **2. End-to-end interoperability testing with real supported backup destinations** | **Not met** | Five defaults exist in `domain/BackupSources.kt:30–92`; no real destination was used for this review. `docs/technical/product-status.md:17–18,24` explicitly leaves interoperability outstanding. Existing tests verify components and orchestration with doubles, not the complete default infrastructure, real Brave keybags/pagination, or replacement-device recovery. Close with opt-in tests against every supported endpoint, including failures and an independent clean-device restore. |
| **3. Approved setup, recovery, error, and offline UX—including accessible content** | **Partially met** | UI safeguards and policy tests exist. Generated setup bypasses the documented quorum invariant (B04), stored-key setup retry is not durably routed (B10), and mandatory public profile publication is not disclosed in the account-name entry screen (B12). Offline navigation is globally blocked in `MainActivity.kt:135–151` / `ui/availability/AppAccessGuard.kt:45–64`. `docs/stakeholder/screens-and-states.md:162–164` still requests approval. No device/TalkBack acceptance was run. |
| **4. Release signing, update, telemetry, privacy, support, and incident-response processes** | **Not met** | Release assembly fails; release configuration has no signing assignment (`app/build.gradle.kts:24–35`). CI only builds/tests/lints debug (`.github/workflows/ci.yml:55–62`). No tracked operational runbooks/privacy policy/release attestation process was located. Branch protection is documented as not enabled (`docs/technical/build-and-test.md:45`). External signing or organizational controls could exist, but were not evidenced. |
| **5. Clear user documentation and tested support procedures for lost vault-key recovery material** | **Partially met** | Extensive docs exist, but several materially contradict current recovery, key export, source UI, and persistence behavior (section 7). No tested support procedure was located. In particular, statements that a lost Vault Key cannot be recovered conflict with the implemented Secret Combinations + passphrase recovery path. Close with corrected documentation and tested lost-device/lost-key/lost-passphrase support scenarios. |

**No gate can be marked Met from the available evidence.** An expected open gate is not automatically a hidden defect; the code-level violations and understated limitations below are the additional findings.

## 3. Release blockers — must fix before production use

### B01 — [Severity: Critical] Concurrent secure-storage writes can lose an account from the registry

**Classification:** design/implementation flaw; credential availability/data-loss risk. **Confidence:** high; reproduced.

**Evidence:** `data/secure/SecureStorageService.kt:48–51` writes an account and then performs `registry() + id` and `saveRegistry()` without a shared transaction or lock. Registry read/write is at `:83–92`; each account value is a separate preference edit. Account creation persists through `domain/SetupService.kt:78–86`, outside the engine mutex, while recovery persists through `domain/AccountConsolidationEngine.kt:345–364`. The engine mutex therefore does not serialize every writer to secure storage.

**Verification:** a temporary Kotlin probe used the real `SecureStorageService`, mocked encryption, and a thread-safe SharedPreferences double with a barrier forcing both writers to read the same registry snapshot. Both account records were written, but only **one of two** accounts remained visible through `getAccounts()`. This establishes the lost-update bug, not an observed Android disk crash.

**Impact:** a newly generated or restored key can become an orphaned value. Listing, KeyHive enumeration, and consolidation use the registry, so an orphan may never receive remote repair or normal export. A newly created key with no other usable copy can become effectively lost to the user.

**Concrete fix:** make registry and record persistence one serialized, durable transaction for every mutation. Use a process-wide storage mutex plus one checked atomic write, or a transaction-capable encrypted record store. Add concurrent-create/restore/remove tests and crash/restart fault injection. Provide conservative orphan detection/recovery rather than silently discarding unregistered values.

### B02 — [Severity: High] Critical local persistence is acknowledged before durable success; unreadable storage becomes “missing”

**Classification:** storage reliability and recovery-hardening flaw. **Confidence:** high in source behavior; crash/restore consequences not device-tested.

**Evidence:** `data/secure/SecureStorageService.kt:35–38,48–51,70–75,83–92` uses AndroidX `preferences.edit` with default asynchronous `apply()` and no persistence result. `crypto/KeystoreCryptoService.kt:46–62` collapses missing keys, malformed formats and failed authentication into `null`. The registry similarly defaults to an empty list. `ui/auth/VaultUnlockGateViewModel.kt:50–59` treats an unreadable Vault Key as onboarding. `domain/SetupService.kt:89–93` commits secure state and DataStore setup state separately. `ui/newaccount/NewAccountViewModel.kt:33–36` persists the pending mandatory-export marker only after the domain save and work scheduling have returned.

**Impact:** disk-full/write failures, interrupted commits, corrupted ciphertext, or device-bound key loss can masquerade as an empty/new vault. Users can be routed to creation/import without a clear storage recovery diagnosis. The mandatory-export marker can also be absent after an interruption between the account commit and marker update. These conditions must not be represented as successful durable custody of a credential.

**Concrete fix:** return explicit persistence outcomes and acknowledge the irreversible commit only after durable storage succeeds. Make account/registry/export-progress transitions recoverable as one logical transaction. Distinguish absent, corrupt, unsupported-version and Keystore-unavailable states; quarantine ciphertext and offer a documented restore path instead of silently treating it as nonexistent. Test disk failure, process interruption and key loss.

### B03 — [Severity: Critical] OpenSSH validation accepts a private seed that does not match its advertised public key

**Classification:** cryptographic validation flaw; key-integrity/lockout risk. **Confidence:** high; reproduced.

**Evidence:** `crypto/SshKeyCodec.kt:149–160` checks duplicate check integers, matching public-key copies, private-field length and padding, but never recomputes the Ed25519 public key from the extracted seed. The stored identity is derived from that seed (`domain/BackupSourceAdapters.kt:250–257`; `ui/onboarding/OnboardingViewModel.kt:35–44`), while displayed SSH public keys come from the unencrypted container header (`crypto/SshKeyCodec.kt:88–94`).

**Verification:** a synthetic encrypted key was generated at one bcrypt round, its decrypted seed was modified without changing any public-key copy, and the blob was re-encrypted with the synthetic passphrase. `SshKeyCodec.parse` accepted it; independently deriving the Ed25519 public key from the returned seed proved it did not match the parser’s returned public key. No real key was used or printed.

**Impact:** corrupted or malicious key material can pass “valid key/passphrase” checks while SSH public-key presentation and derived recovery identity disagree. AES-CTR provides no authentication of the SSH container; duplicate OpenSSH check integers are not a substitute for seed/public-key consistency. This can result in unusable exported credentials or recovery under an unintended identity. No end-to-end remote exploit or private-key extraction was demonstrated.

**Concrete fix:** derive the Ed25519 public key from the extracted 32-byte seed and compare it with every stored public-key copy before returning success. Enforce complete structural constraints, including valid cipher-block alignment and padding policy. Add mutations of seed, public fields, check integers, padding, and lengths to regression tests, and verify generated/accepted keys with an independent OpenSSH implementation.

### B04 — [Severity: High] Generated Vault Key onboarding marks setup complete without the required backup quorum

**Classification:** domain invariant violation. **Confidence:** high; directly traced.

**Evidence:** `domain/SetupService.kt:44–65` persists the generated key, records `BACKUP_INTRO`, ignores source quorum and suppresses backup failures. Later `ui/onboarding/createvaultkey/CreateVaultKeyViewModel.kt:328–340` calls `appStorage.setSetupComplete()` unconditionally after combination verification. Account creation checks that boolean (`domain/SetupService.kt:78–81`) rather than independently established quorum. In contrast, imported-key setup explicitly checks quorum (`domain/SetupService.kt:21–36`).

**Verification:** read both entry paths and tests. `domain/SetupServiceTest.kt`, symbol `generated_vault_key_is_persisted_but_remote_backup_is_skipped_when_prerequisites_block`, establishes that the create path can intentionally skip all remote backup. No final generated-setup quorum check exists at the completion point. This is a source-traced finding, not a manually executed wizard.

**Impact:** the app can allow associated-account creation while holding zero successful remote Vault Key backups, despite the documented invariant requiring one eligible Nostr and one eligible Brave source before setup completes. Saving the local export and mnemonic does not make a remote backup exist.

**Concrete fix:** retain wizard completion separately from remote-quorum readiness. Let the user safely finish local export/transcription, but do not expose “setup protected”/account creation until verified quorum exists. Persist missing coverage and provide an unavoidable, clear retry/remediation state. Test generated onboarding with all sources failing, only one source type succeeding, and process death between stages.

### B05 — [Severity: High] Brave responses fail open; Vault Key quorum trusts acknowledgements without read-back

**Classification:** protocol validation and backup-integrity flaw. **Confidence:** high; reproduced.

**Evidence:**

- `bravesync/proto/SyncMessages.kt:80–86` defaults an absent `error_code` to success (`0`).
- `bravesync/BravePasswordService.kt:20–24` accepts an absent updates body as an empty list via `PasswordReader.read` (`bravesync/password/PasswordReader.kt:13–18`).
- `bravesync/BravePasswordService.kt:96–101` iterates nullable commit results; missing commit results or zero entry responses do not fail.
- `domain/AccountConsolidationEngine.kt:140–167` accepts a Vault Key write acknowledgement and returns success without read-back. Associated-account writes do have read-back (`:236–258,423–460`).

**Verification:** a loopback HTTP fixture returned HTTP 200 with an empty body for every request. The actual `BravePasswordService` accepted the empty index, Nigori initialization commit, and password commit. Separately, a probe using the real consolidation engine with sources that always index empty but acknowledge writes returned `quorumReached = true`.

**Impact:** malformed/incomplete successful HTTP responses can produce a false backup claim. A source may acknowledge a Vault Key it cannot subsequently serve, yet satisfy initial quorum. This is especially dangerous because the Vault Key is the root of the recovery promise.

**Concrete fix:** require the expected response branch, explicit acceptable protocol status, and exactly the expected number of successful commit entry results. Reject absent/ambiguous responses. Read back the Vault Key after a write and verify the intended authenticated record before counting it toward quorum. Add empty/truncated/wrong-branch/missing-entry tests. Chromium’s protocol declares the absent response error-code default as UNKNOWN, not SUCCESS: [sync.proto, ClientToServerResponse](https://raw.githubusercontent.com/chromium/chromium/main/components/sync/protocol/sync.proto).

### B06 — [Severity: High] Remote inventories are treated as complete without proving completion

**Classification:** protocol/reconciliation design flaw. **Confidence:** high for explicit Nostr rejection and omitted Brave continuation; real-service frequency unverified.

**Evidence:**

- `network/nostr/NostrRelayService.kt:144–185` calls Quartz `fetchAll` but does not require successful EOSE. `RelayOperationMonitor.onIncomingMessage` at `:272–288` records only auth-related CLOSED reasons; a generic `restricted:` CLOSED is not represented as an index failure.
- Resolved **Quartz 1.08.0**, `NostrClientFetchAllExt.kt`, symbol `INostrClient.fetchAll`, treats `onClosed`, `onCannotConnect` and `onEose` as terminal notifications and returns collected events. Its timeout can also return a partial collection. Vault’s outer timeout is not proof that the inner collection completed successfully.
- `bravesync/proto/SyncMessages.kt:52–60,111–112` sends an empty progress token and only parses returned entities. It ignores `changes_remaining` and `new_progress_marker`; `BravePasswordService.list` performs a single GET_UPDATES.

**Verification:** after an attempted raw host websocket probe was inconclusive because of Android logging/platform behavior, a deterministic socket double was injected through the existing Quartz client factory. It delivered an explicit `CLOSED` with `restricted: synthetic refusal`. The actual Quartz + Vault fetch path returned a **successful empty index**. The successful result concerns the protocol callback path, not a real TLS socket. Brave continuation absence was verified by reading code and comparing [Chromium GetUpdatesResponse](https://raw.githubusercontent.com/chromium/chromium/main/components/sync/protocol/sync.proto), which defines continuation markers and changes remaining.

**Impact:** rejection or incomplete snapshots can be interpreted as absence, breaking “unknown is not missing.” Repair can publish unnecessarily, and a replacement device can fail to discover records beyond the first server batch. Identifier-level idempotency cannot compensate for an incomplete inventory.

**Concrete fix:** expose typed EOSE/completion, rejection, disconnect and timeout outcomes from Nostr collection; treat incomplete reads as UNKNOWN even if some events arrived. Implement Brave pagination/progress handling until completion, with an explicit total budget and safe failure outcome. Test rejected subscriptions, partial streams, timeout after one event, multiple batches, repeated markers and interruption.

### B07 — [Severity: High] Hostile input has insufficient CPU, memory and parser-depth limits

**Classification:** denial-of-service hardening flaw. **Confidence:** high in limits absent; exhaustion not intentionally executed.

**Evidence:**

- `crypto/SshKeyCodec.kt:184–188` accepts any positive round count after conversion to Int. Parsing invokes BCrypt synchronously (`:137–143`). The generation allowlist is not an import limit.
- `bravesync/transport/BraveSyncTransport.kt:34–43` buffers the whole HTTP body, then expands gzip with unbounded `readBytes()`.
- `bravesync/proto/ProtoWire.kt:58–62,87–99` has no field-count or group-recursion cap.
- `network/nostr/NostrRelayService.kt:150–181` has no event-count/payload-volume limit. The resolved Quartz `BasicOkHttpWebSocket.kt` uses `Channel.UNLIMITED` for incoming messages; its fetch collector retains events and IDs without a size cap.

**Verification:** a generated synthetic SSH container’s rounds field was changed to **2,147,483,647**. Metadata validation accepted it. The expensive KDF was deliberately not run. Response accumulation/decompression and recursion findings are source-read, not an executed OOM/stack-exhaustion attack.

**Impact:** a malicious pasted key, hostile service, malformed protobuf or compressed response can consume CPU/memory, hang validation or crash the vault. When hostile material is encountered during recovery this can repeatedly prevent recovery. Crypto running on the main thread magnifies the input-work-factor issue (B11).

**Concrete fix:** enforce independently reviewed input work-factor/salt/length limits before KDF work, with compatibility handling for legitimately expensive imported keys. Bound compressed and decompressed sizes, events, fields, nesting and total operation time. Use bounded queues and explicit cancellation. Add small-budget hostile-input tests and fuzzing; do not rely on network read timeouts to stop CPU or decompression work.

### B08 — [Severity: High] Backup workers finish successfully after recoverable source failures and do not autonomously retry them

**Classification:** reliability/retry contract flaw. **Confidence:** high; source-traced.

**Evidence:** `background/BackupWorkers.kt:60–78` ignores `ConsolidationResult.failures`; `:118–126` ignores failed source IDs/quorum from the Vault Key engine result. Both return `Result.success()` when the engine recorded source-level failures but did not throw. The scheduler at `:144–204` defines one-time work only. Exponential WorkManager backoff is configured, but it applies only when the worker actually returns `retry()`. Pending pairs are persisted (`domain/AccountConsolidationEngine.kt:503–510`) but persistence itself does not enqueue a retry. Targeted index failures intentionally do not even add a pending pair (`:208–213`).

**Impact:** a temporary outage can leave backups incomplete indefinitely until a later unlocked launch/manual action. “Pending” is a record of work, not a functioning retry loop. A full cycle may also record a completion timestamp after failures (`:296`), causing freshness to suppress another check when no pending entry was created.

**Concrete fix:** define the product’s session-aware retry guarantee explicitly and implement it. Return retry or enqueue bounded unique retry work for transient failures while a valid session exists; preserve pending intent across process death and requeue on unlock. Prevent fresh-but-incomplete cycles from being treated as healthy. Include permanent versus transient failures, retry-age/attempt status, and user-visible remediation. Add WorkManager tests for source failure, lost connectivity, lock, process restart, Doze and retry exhaustion.

### B09 — [Severity: High] Vault Key recovery accepts a decryptable key without binding it to the supplied recovery words

**Classification:** recovery identity validation flaw. **Confidence:** high; reproduced.

**Evidence:** `domain/VaultKeyRecoveryService.kt:67–87` accepts the first candidate that `normalizeAndParse` can decrypt and immediately persists it/marks setup complete. `normalizeAndParse` at `:145–152` never derives the candidate’s `vkbckp` mnemonic and compares it with the supplied mnemonic. Per source it selects only the first NIP-44-decryptable relay payload (`:99–104`) or first matching Brave password (`:134–138`), before OpenSSH validation, so another valid candidate from the same source is not tried. Restore source filtering at `:154–157` also omits `trustedForRestore`.

**Verification:** a temporary probe generated two different synthetic Vault Keys with the same synthetic passphrase. It signed/encrypted the second key under the first key’s recovery identity, then called the actual recovery service with the first key’s recovery indices. Recovery succeeded and stored the second key. Gateway/storage were controlled doubles; derivation, event construction, NIP-44 and SSH parsing were real.

**Impact:** successful decryption is not proof that the recovered root key belongs to the user’s supplied recovery address. Corrupt/misbound data can open the wrong vault and leave the intended associated accounts undiscoverable. The probe does not demonstrate that an arbitrary relay operator can forge NIP-44 or signatures without recovery material; the absent consistency check is a defense-in-depth requirement at this root-of-trust boundary.

**Concrete fix:** re-derive the candidate’s backup mnemonic/identity and compare it with the user input before persistence. Iterate all candidates newest-first through full validation, not just one candidate per endpoint. Apply an explicit restore-trust policy. Report incompatible/corrupt copies distinctly from absence and continue checking other copies. Add wrong-root/same-passphrase, duplicate-corrupt-first and untrusted-source tests.

### B10 — [Severity: High] Imported setup cannot reliably resume the stored-key backup retry after failure/restart

**Classification:** setup-flow reliability flaw. **Confidence:** high in source; navigation not device-exercised.

**Evidence:** `domain/SetupService.kt:21–38` retains the key on quorum failure. But `ui/onboarding/OnboardingViewModel.kt:53–55` returns a generic state without setting `canRetryStoredVaultKey = true`. That property is only set when a retry has already failed (`:59–65`). `ui/onboarding/SshSetupScreen.kt`, final actions block, shows the retry button only when this flag is true. `ui/root/RootScreen.kt:36–56` routes incomplete setup without a create-flow marker to Get Started; it does not check for a retained imported Vault Key and offer direct backup retry.

**Impact:** the documented retry-without-reimport path is not reachable in the normal initial failure state and is not durable across restart. A user who relied on secure local retention can be sent back to key import/generation, risking accidental replacement of an already committed identity.

**Concrete fix:** persist a clear imported-setup-pending state and route it to a stored-key retry screen. Set retry eligibility on the initial post-persistence failure and prevent generating/replacing a different root without an explicit recovery/reset decision. Test failed import setup, return to foreground and process recreation without the original clipboard content.

### B11 — [Severity: High] Expensive cryptography still runs on the Android main thread in sensitive entry paths

**Classification:** likely real-use ANR/responsiveness failure. **Confidence:** high in dispatch trace; no device ANR timed here.

**Evidence:** `ui/onboarding/OnboardingViewModel.kt:33–50` runs SSH BCrypt and NIP-06 derivation directly inside `viewModelScope.launch` without a dispatcher switch; its in-process setup path also reaches `DefaultBackupSourceFactory.createRunContext` (`domain/BackupSourceAdapters.kt:34–40`). `ui/newaccount/NewAccountViewModel.kt:23–26` calls synchronous `AccountService.verifyVaultKeyPassphrase`; that function performs SSH parse at `domain/AccountService.kt:35–40`. `ui/secretcombinations/SecretCombinationsViewModel.kt:67–76` does the same on the default ViewModel scope. Voluntary export calls synchronous verification from the dialog click (`ui/accounts/ExportPassphraseDialog.kt`, confirm button; `ui/accounts/AccountDetailViewModel.kt:71–73`).

**Impact:** legitimate high-round keys, or maliciously large rounds accepted by B07, can freeze the UI, prevent timely lock/error rendering and trigger ANRs. The generation path correctly moving its own crypto off-main does not fix these other paths.

**Concrete fix:** make verification/derivation suspend APIs that dispatch CPU work to a bounded background dispatcher. Show a cancellable or safely recoverable progress state, cap accepted work, and ensure callbacks never execute KDF work directly. Test responsiveness, lifecycle interruption and high-cost keys on low-end supported devices.

### B12 — [Severity: High] Account names and creation identifiers are automatically published publicly without an in-flow disclosure

**Classification:** privacy/user-safety design gap. **Confidence:** high in code; user research/legal conclusions unverified.

**Evidence:** `ui/newaccount/NewAccountScreen.kt:75–85` labels input simply “Account Name” with length guidance; it does not state that the name becomes public. After mandatory export confirmation, `ui/accounts/LocalAccountBackupViewModel.kt:37–45` queues publication. `domain/ProfileService.kt:50–79,89–99` signs kind 0 metadata containing `display_name` from `account.accountName` and the creation ID as `name`, plus a DM-relay list, and sends it to seven public relays (`:123–130`). The worker does not require an unlocked passphrase session (`background/ProfilePublicationWorker.kt:34–46`).

**Impact:** a user may enter a service, project, employer or environment label believing it is a private vault label. This information and a correlation identifier then leave the app publicly and can be replicated beyond recall. This is materially different from encrypted backup. The behavior is documented for developers but not adequately explained at the input point.

**Concrete fix:** disclose public publication before collecting the label and obtain an explicit product-approved choice; preferably separate a private local label from an optional public display name. Document immutable/replicated metadata and deletion limits in the privacy policy/Data safety assessment. Add acceptance tests confirming that no public metadata is sent before the approved user decision.

### B13 — [Severity: High] Production release artifact cannot be built; signing/update/provenance controls are not established

**Classification:** release-hardening and release-process blocker. **Confidence:** high for build/configuration; external organizational arrangements unverified.

**Evidence:** `./gradlew :app:assembleRelease` failed at `:app:minifyReleaseWithR8`. R8 reports missing `javax.management.MBeanException`, `javax.management.ReflectionException`, `net.i2p.crypto.eddsa.EdDSAPrivateKey`, `net.i2p.crypto.eddsa.EdDSAPublicKey`, and `org.slf4j.impl.StaticLoggerBinder`. It also emits repeated Kotlin metadata compatibility warnings. `app/proguard-rules.pro:1–17` does not resolve these errors. `app/build.gradle.kts:24–35` enables shrinking/minification but defines no release signing assignment. `.github/workflows/ci.yml:55–62` never tests release assembly or release runtime.

**Impact:** there is no validated shippable artifact from this checkout, and debug test success cannot establish that R8 preserves native bindings, serializers, DI or provider behavior. Signature identity is also the KeyHive trust boundary, making certificate custody and update/key-rotation planning particularly consequential.

**Concrete fix:** align the full resolved Kotlin/AGP/R8 dependency/toolchain combination; narrowly resolve missing optional classes or remove unused dependency surfaces rather than blindly adding blanket `dontwarn`. Build and test minified release artifacts in CI. Establish protected signing, update/rollback, sibling-app signing policy, SBOM, artifact checksums/attestations and clean-build provenance. Require those checks through enforced repository controls.

### B14 — [Severity: High] Required real-service/device and operational acceptance evidence is missing

**Classification:** explicitly open release gates, not an allegation that every untested path is defective. **Confidence:** high that the repository evidence does not close the gates; external work unverified.

**Evidence:** `docs/technical/product-status.md:14,17–18,20,23–24` reserves device, interoperability and UX validation; `docs/stakeholder/scope-and-roadmap.md:36–40` requires it. Only two instrumented test files exist, covering Keystore/storage basics, and `.github/workflows/ci.yml` does not run them. `docs/technical/build-and-test.md:45` states branch protection is not enabled. No tracked incident/support/privacy runbooks or acceptance records were located in the documentation inventory.

**Impact:** credential custody and replacement-device recovery remain promises without a demonstrated release acceptance trail. A clean SAST run cannot substitute for actual operational recovery.

**Concrete fix:** assign owners and attach evidence for all five gates: live supported endpoints, API 26–36 device matrix, negative recovery/lifecycle scenarios, accessibility/offline approval, privacy/retention declarations, support drills, compromised-relay and compromised-root response, and signed-release update tests. Obtain specialist remediation review before public deployment.

## 4. High-risk but not independently release-blocking findings

These items are not proof of a remote secret compromise. They still require an explicit security/product decision; the overall release remains blocked by section 3.

### H01 — [Severity: High] Pre-API-30 prompt policy can admit weak biometrics despite the strong-only claim

**Classification:** authentication policy mismatch. **Confidence:** high from resolved dependency source; no biometric hardware test.

**Evidence:** `ui/auth/AppUnlockCoordinator.kt:155–166` sets only deprecated `setDeviceCredentialAllowed(true)` below API 30 and authenticates without a CryptoObject. Resolved AndroidX Biometric **1.1.0**, `AuthenticatorUtils.getConsolidatedAuthenticators`, defaults a non-crypto prompt with no explicit allowed authenticator mask to `BIOMETRIC_WEAK`, then adds `DEVICE_CREDENTIAL`. The keyguard capability check at `:147–153` does not strengthen that prompt.

**Impact:** on affected older Android versions, interactive authentication can be weaker than the documented Class 3-only policy. The independent Vault Key passphrase screen provides an additional barrier when a stored key exists, so this is not demonstrated as complete vault access via weak biometrics alone.

**Fix:** use an explicit supported strong-biometric policy and a separate device-credential fallback on older versions, or document/approve the weaker contract. Add tests of actual constructed prompts and manual Class 2/Class 3/PIN-only behavior on supported APIs, not just pure capability booleans.

### H02 — [Severity: High] Session secret lifetime is longer than the documented 30-second background expiry

**Classification:** lifecycle/secret-lifetime mismatch. **Confidence:** high in source.

**Evidence:** `ui/auth/AppUnlockCoordinator.kt:121–123` records background time but starts no expiry timer and leaves state Unlocked. Locking is evaluated on foreground (`:98–118`). `MainActivity.kt:74–76` clears the passphrase only after a non-Unlocked transition. Thus a resident process remaining in the background can retain the session beyond 30 seconds. `domain/VaultKeySession.kt:24–25,58–65` explicitly returns independent copies that remain usable after locking; workers take a snapshot before waiting for engine work (`background/BackupWorkers.kt:49,108`).

**Impact:** new or already queued work may obtain/retain the passphrase while the app has been backgrounded for much longer than the stated interval. This does not bypass Android’s sandbox or export a key to arbitrary apps, but it invalidates a strict “no background work after lock expiry” interpretation.

**Fix:** define whether 30 seconds is only a foreground reauthentication rule or actual secret expiry. If actual expiry is intended, schedule monotonic expiry, revoke new snapshots atomically, manage verification-versus-lock races, and either cancel/revalidate in-flight jobs or explicitly document their independent lifetime.

### H03 — [Severity: High] Root/forensic/process compromise exposes more than the passphrase-encrypted SSH payload

**Classification:** accepted-boundary decision requiring accurate documentation. **Confidence:** high in source.

**Evidence:** `data/model/Account.kt:14–25` includes a raw Nostr private key encoded as String. `crypto/NostrKeyDerivation.kt:28–29` persists it as hex. It is protected by local Keystore AES-GCM, not by the Vault Key passphrase. `SecureStorageService` initializes persistent StateFlows of decrypted Account records (`:25–33`). `KeystoreCryptoService.kt:79–86` does not require user authentication or request/verify StrongBox/hardware security level.

**Impact:** UI reauthentication is not an authentication-bound cryptographic store. Same-UID code that can use the Keystore wrapper can read Nostr signing/backup-decryption keys even without learning the Vault Key passphrase. Signature-authorized KeyHive, by contrast, exposes only the encrypted SSH container and public Nostr identity, not these raw Nostr secrets. An ordinary third-party app is not shown to cross this boundary.

**Fix:** formally approve the threat model, minimize resident secret-bearing account caches, consider deriving Nostr secrets only inside an unlocked session, and evaluate a separate authentication-bound domain for interactive custody. Do not promise StrongBox where no capability check exists. Keep unattended sibling access limited to the explicitly approved encrypted payload contract.

### H04 — [Severity: High] Dependency security review must use resolved artifacts, not catalog versions

**Classification:** supply-chain/assessment gap. **Confidence:** high for resolved versions; no exhaustive reachable-CVE certification.

**Evidence:** `gradle/libs.versions.toml:13–21` requests coroutines 1.9.0, serialization 1.7.3, OkHttp 4.12.0, Quartz 1.08.0, BC 1.79, SSHD 2.12.1. Actual `releaseRuntimeClasspath` dependencyInsight resolves **OkHttp 5.3.2** and **Kotlin stdlib 2.3.20** through Quartz; the host test classpath also contains coroutines **1.10.2** and serialization **1.10.0**. Kotlin build plugins remain **2.2.21**, and release R8 warns about newer Kotlin metadata.

**Impact:** an assessment based only on declared direct versions would review the wrong code. BC 1.79 is within ranges of published advisories, but this is not evidence that the app’s Ed25519/AES/PBKDF2 calls exercise those vulnerable functions.

**Fix:** generate and review a resolved SBOM, lock/verify dependency artifacts, update compatible security-sensitive libraries, and record reachable/unreachable advisory decisions. For example, [CVE-2025-14813](https://github.com/advisories/GHSA-574f-3g2m-x479) concerns GOST `G3413CTRBlockCipher`, and [CVE-2026-5598](https://github.com/advisories/GHSA-p93r-85wp-75v3) concerns FrodoKEM `FrodoEngine`; neither was found in the app-owned crypto call paths. Apache [CVE-2024-41909](https://github.com/advisories/GHSA-2326-hx7g-3m9r) is patched in 2.12.0, so the declared 2.12.1 must not be falsely described as vulnerable to that specific older issue. Quartz/native-library CVE absence was not established.

## 5. Medium/Low findings and polish

### Storage/platform

- **[Medium] Explicit Android 12+ transfer exclusions are missing.** `app/src/main/AndroidManifest.xml:20` sets `allowBackup=false`, which is good, but there are no `dataExtractionRules`/legacy backup-rule resources. Fresh release lint raises `DataExtractionRules`. Device-bound Keystore keys do not accompany ordinary file restore; actual OEM transfer behavior was not tested. Exclude secure/preferences/WorkManager state from cloud and device transfer explicitly, and test supported-device migration with a clear unavailable-key recovery flow.
- **[Medium] Secure ciphertext is not bound to a logical record ID with AAD.** `crypto/KeystoreCryptoService.kt:25–35,46–61` authenticates the blob but never calls `updateAAD`. `SecureStorageService.read` does not verify that a decoded Account ID matches its preference slot. This matters primarily after local file tampering/restore, not as a demonstrated sandbox escape. Bind format/domain/record ID and validate decoded identity; design a versioned migration.
- **[Medium] No secure-record migration strategy is implemented.** The v2 preferences file and versioned cipher envelope support identification, not migration (`SecureStorageService.kt:96–99`; `KeystoreCryptoService.kt:48–49`). Unsupported data is currently unavailable. Document supported upgrade baselines and test them before distributing production updates.
- **[Low] `clearAll()` does not update the Vault Key StateFlow.** `SecureStorageService.kt:65–68` republishes accounts only. There is no user-facing reset today, but a future reset must also clear vault/session/cache/work state consistently.

### Crypto and memory hygiene

- **[Medium] Wiping is partial, not a guarantee.** `SshKeyCodec.kt:137–145` creates an immutable passphrase String and UTF-8 array; the latter and decrypted blobs/seeds are not wiped. `NostrKeyDerivation` returns raw keys plus `nsec`/`npub` Strings; persisted Nostr keys are immutable hex Strings. Nigori keys/sessions and decoded backup payloads also lack deterministic disposal. Wipe owned buffers in `finally`, reduce copies and data-class `toString` exposure, and accurately document managed-runtime limits. No heap dump was collected.
- **[Medium] Production settings permit bcrypt round count 1 without a risk warning.** `SshKeyCodec.kt:21–23`; `ui/settings/SettingsScreen.kt:166–183`. Default 128 is materially stronger, but a user can substantially weaken newly created associated keys. Establish a calibrated production floor or expert override with explicit offline-guessing consequences. zxcvbn estimates are not a device-specific bcrypt benchmark.
- **[Medium] Nigori authenticates ciphertext, not its IV.** `bravesync/crypto/NigoriCrypto.kt:54–73` computes HMAC only over ciphertext. A probe flipped IV bit 0 and decryption accepted an altered first plaintext byte. This appears to be a legacy protocol constraint, not evidence that Vault can simply change the MAC and remain Brave-compatible. Inner SSH encryption and payload validation remain important. Document this non-AEAD limitation, confirm exact interoperability, and consider a separately versioned authenticated inner envelope rather than silently changing Nigori.
- **[Low] Do not replace protocol KDFs arbitrarily.** Brave PBKDF2-SHA1 iterations 1003/1004 and fixed salt are compatibility parameters (`NigoriCrypto.kt:26–45`), with high-entropy mnemonic input. Their presence alone is not proof of a weak user-password database. Argon2 is not currently used and cannot be substituted without a protocol migration.

### Network/protocol

- **[Medium] Nigori keybag/rotation compatibility is incomplete.** `SyncMessages.kt:142–168` parses keybag data but `BravePasswordService.resolveKeys:65–73` only derives keys from KDF metadata; `PasswordReader.kt:18–20` does not select keys using `EncryptedData.keyName`. Test real keybag import and key rotation. Either support them or state and enforce a restricted chain compatibility contract; do not count undecryptable rotated records as simple absence.
- **[Medium] Cached Brave auth tokens have no refresh policy.** `BravePasswordService.kt:49–62` caches one timestamped token per source instance/run. Long runs can outlive server freshness; no refresh-on-expiry path is implemented. Bound run duration and refresh safely on a classified authentication rejection.
- **[Medium] Transport operations lack an overall Brave call budget/cancellation bridge.** `BraveSyncTransport.kt:14,34` uses default OkHttp timeouts and synchronous `execute`; no explicit call timeout or coroutine cancellation hook exists. Slow trickle responses can outlast per-read timeouts. Add total budgets and cancel calls when work stops.
- **[Medium] Server-controlled error content crosses exception boundaries.** `BraveSyncTransport.kt:37` includes up to 500 body characters; `BravePasswordService.kt:92,99` includes protocol error strings. Persisted consolidation state coarsens these errors, which is good, but future crash reporting/support logging could leak echoed tokens/payloads. Replace raw response text with safe codes and bounded redacted diagnostics.
- **[Medium] Third-party logging is not governed by app release configuration.** Resolved Quartz `Log.kt` defaults to DEBUG; `BasicRelayClient.kt` logs malformed raw websocket input, and event verification paths can log full invalid events. No app-owned key logging was found by text search, but that is not an end-to-end “no sensitive logs” proof. Configure/sanitize library logs, test hostile echoed data, and verify release logcat before adding telemetry.
- **[Low/decision] No certificate pinning is configured.** Both transports use normal OkHttp TLS/hostname verification; no permissive TrustManager/hostname verifier was found. Source normalization enforces `wss`/`https` (`BackupSources.kt:150–164`). Absent pinning alone is not a release blocker; any pinning design needs backup pins and rotation/outage procedures. Explicit `usesCleartextTraffic=false` would make intent clearer; target SDK defaults already disallow cleartext on the Android app path. The loopback HTTP probe was a host-only injected transport, not proof that Android permits HTTP.
- **[Low] Canonical account IDs are not fully canonicalized at ingestion.** `BackupSources.kt:104–107` accepts digit strings with leading zeros and zero; the provider requires positive exact round-trip IDs (`keyhive/AssociatedAccountsProvider.kt:45–46`). Reject alias forms before indexing/restoring and verify the SSH comment/account ID binding where appropriate.

### UX, privacy and operations

- **[Medium] Offline mode blocks access to already-local credentials.** `MainActivity.kt:135–151` removes navigation when internet/time prerequisites fail. Encrypted local export/public-key access should not inherently require a network connection; this is a product availability choice needing gate-3 approval. Separate remote-write safety checks from offline read/export access.
- **[Medium] Clipboard export is not marked sensitive and has no app-managed expiry.** `ui/accounts/AccountDetailScreen.kt:165–170` uses plain ClipData without a sensitive flag. The payload is the encrypted OpenSSH key, not a plaintext seed/passphrase, and copy is explicit/passphrase-gated; nevertheless it is offline-attackable secret-bearing material. Mark it sensitive and offer bounded clearing with clear OS/history limitations. Do not claim the app controls keyboard/cloud clipboard history.
- **[Medium] Names/profile metadata and network correlation require a complete privacy assessment.** Beyond B12, backup IDs/timestamps, IP addresses and derived identities are visible at service boundaries; the same deterministic Brave client identity is derived from the same mnemonic across endpoints (`BraveSyncAuth.kt:66–81`). Per-endpoint transport instances do not make those credentials unrelated. Include Google/Cloudflare time checks, public profile relays, remote retention and signature-authorized sibling sharing in disclosures.
- **[Medium] No user-facing account deletion, vault reset or key rotation.** Confirmed by navigation (`ui/VaultNavGraph.kt`) and settings, consistent with `product-status.md:25`. This is not automatically a Play account-deletion-policy violation—there is no demonstrated conventional hosted account—but there must be a usable local wipe/recovery and external SSH revocation/support story. Uninstall cannot recall public relay copies or revoke SSH authorized_keys on servers.
- **[Medium] Profile publication retries are all-or-nothing across seven relays.** `ProfileService.kt:74–79` requires every event at every profile relay; the worker retries exceptions indefinitely (`background/ProfilePublicationWorker.kt:34–50`). A single permanently unavailable relay can cause repeated publication with new timestamps and battery/network use. Track per-relay completion, classify permanent failures and expose status without blocking use.
- **[Low] Status timestamps can overstate what happened.** `AccountConsolidationEngine.markPresent:525–528` updates “last successful backup” even when only observing an existing ID. Presence is not a fresh write or proof of payload usability. Label verification time separately from write time.
- **[Low] Lint polish:** oversized vector artwork, unused resources, missing monochrome launcher icons and modifier ordering remain. Fresh lint reported 51 warnings, largely dependency-update notices; no lint errors. Its “newer version available” suggestions are not vetted safe upgrade instructions.

### CI/supply chain

- **[Medium] Wrapper/distribution verification is incomplete.** `gradle/wrapper/gradle-wrapper.properties:3–7` has HTTPS and URL validation but no `distributionSha256Sum`. No tracked dependency verification metadata/lockfiles were located. Set official checksums and review artifact verification/locking.
- **[Medium] Build CI uses mutable Action tags.** `.github/workflows/ci.yml:29,36,42,53,67` uses version tags; security workflow checkout/upload actions use commit SHAs. Scanner containers are version-tagged, not digest-pinned, and Semgrep rules are downloaded at runtime. Pin executable supply-chain inputs and preserve exact rules/SBOM/artifact evidence where reproducibility matters.
- **[Medium] Scan checks are not proven merge enforcement.** Workflows fail on Gitleaks findings and Semgrep ERROR severity, so it would be inaccurate to say there is no SAST gate at all. But documented absent branch protection means these checks do not enforce review/merge policy. No dependency reachability/SBOM, release instrumented tests, DAST or provenance gate was located. Repository administration was not queried.
- **[Low] Secret fixture allowlists cover paths, not individual fixture values.** `.gitleaks.toml:34–39` and `.semgrepignore:8–13` intentionally exempt generated golden fixtures/generator. Keep those files under review so a genuine secret cannot be hidden by the exemption. The public Brave service constant is not a secret and should not be rotated as if it were one.

## 6. Verified-good — evidence worth retaining

These statements are deliberately bounded; “good primitive present” does not mean the entire product is safe.

1. **Fresh host suite succeeds:** 198 tests in 36 JUnit XML suites, zero failures/errors/skipped. The source tree has 37 files under `src/test`, including fixture/helper files. Tests are not all happy-path: wrong passphrases, invalid sync-code checksum, input size limits, seed vectors, fixed source policy, quorum failures, unknown-index handling, targeted scope and restore-before-backup ordering are covered. Representative contracts: `crypto/SshKeyCodecTest`, `crypto/QuartzParityTest`, `domain/SetupServiceTest`, `domain/AccountConsolidationEngineTest` and `domain/VaultKeyRecoveryServiceTest`. **Tested.**
2. **Deterministic crypto parity is real, though limited:** `QuartzParityTest` exercises golden NIP-06 outputs, official NIP-44 conversation keys and fixed-nonce payloads. `SshKeyCodecTest` extracts expected seed/fingerprint/mnemonic from an encrypted OpenSSH fixture. `SeedifyTest` covers the date-derived 25th word and wrapping horizon. The `vkbckp` derivation uses a fixed label hash plus seed (`crypto/Seedify.kt:56–59`); no hidden extra passphrase is applied to the raw-seed BIP-39 convention. **Read/tested.**
3. **Entropy and ordinary cipher selection are appropriate building blocks:** generated Ed25519 seed/salt/check integers use SecureRandom (`SshKeyCodec.kt:97–115`); SSH uses OpenSSH-compatible bcrypt_pbkdf/AES-CTR. NIP-44 v2 is delegated to Quartz rather than a new app-owned encryption algorithm (`crypto/Nip44.kt`). Nigori is a protocol-specific compatibility implementation and has the caveats above. **Read/tested vectors, not independent full crypto certification.**
4. **Local secret values are Keystore AES-256-GCM encrypted:** `KeystoreCryptoService.kt:25–37,75–88` uses a non-exportable AndroidKeyStore alias, fresh generated IV and 128-bit tag, with a format version. A missing key is not created on decrypt (`:60,69–72`). SecureStorage stores serialized Account values/registry through this wrapper. Hardware security level/StrongBox and actual device behavior were not tested. **Read; instrumented tests exist but were not run.**
5. **Passphrase persistence has been removed from the Account schema:** `data/model/Account.kt:14–25` contains no passphrase. `VaultKeySession.kt:43–65` verifies by decrypting the actual SSH key and keeps a copy in memory; worker inputs contain IDs/triggers only (`BackupWorkers.kt:174–203`). Worker snapshots are wiped in finally blocks. This does not guarantee every transient String/byte copy is wiped. **Read.**
6. **Generated passphrase policy is checked below the UI:** `VaultKeyPassphrasePolicy.kt:31–59` requires minimum length 12 and zxcvbn score 3; `CreateVaultKeyViewModel.choosePassphrase:152–170` remeasures rather than trusting stale UI state. Estimation is off-main, local, length-bounded at 256 and clears its WipeableString (`PassphraseStrengthChecker.kt:53–86`). It intentionally does not gate pre-existing imported/recovered passphrases. **Read/tested.**
7. **Core consolidation has a sound sequential skeleton:** `AccountConsolidationEngine.kt:273–298,307–365,388–460` indexes/restores all configured sources before repair, uses one engine mutex, does not intentionally parallelize source operations, preserves existing local accounts and reads back associated writes. Tests exercise ordering, no-write-after-failed-index and targeted-account isolation. Adapter completion semantics and storage concurrency still violate assumptions as described above. **Read/tested.**
8. **Vault and associated relay identities are separated in the actual factory:** `BackupSourceAdapters.kt:34–40,103–115,144–164` derives the recoverable `vkbckp` identity per run and uses it for Vault Key publication; associated records use the stored Vault Key identity. Some older docs incorrectly say later Vault Key operations switch identities. **Read.**
9. **Received Nostr events are locally signature/author/kind filtered:** `NostrRelayService.kt:179–181`, `NostrEventCodec.kt:29`, and restore checks in `BackupSourceAdapters.kt:129–141`. Remote associated payloads are fully SSH-parsed only when restoring locally missing accounts. NIP-42 uses Quartz RelayAuthenticator on the same client connection (`NostrRelayService.kt:220–242`); successful live relay handshake and replay resistance were not operationally validated. **Read/tested component vectors; not live-tested.**
10. **KeyHive is an intentionally narrow exported boundary:** manifest signature read permission and `grantUriPermissions=false` (`app/src/main/AndroidManifest.xml:14–16,39–44`); provider URI/argument checks and read-only methods (`keyhive/AssociatedAccountsProvider.kt:39–57`); SharedAssociatedAccount includes only encrypted SSH key and public Nostr identity (`keyhive/SharedAssociatedAccount.kt:15–25`). No certificate-derived “secret encryption” is used. Arbitrary third-party access was not device-tested. **Read/host contract tests.**
11. **Whole-activity screenshot protection is set:** `MainActivity.kt:48–53` applies FLAG_SECURE before rendering. Cancellation/retryable prompt errors remain on a locked UI (`AppUnlockCoordinator.kt:46–57,89–94`). Cold-start and 30-second foreground policy have host tests, but actual platform screenshots/Recents/biometric behavior were not tested. **Read/tested pure policies.**
12. **Normal TLS verification is retained:** no custom permissive certificate/hostname policy was found in app transports; configured sources require secure schemes. **Read; no TLS interception/device test.**
13. **Scanners execute successfully:** Gitleaks scanned 287 commits / approximately 3.63 MB and reported no leaks; Semgrep ran 55 rules across 213 tracked files and reported zero ERROR findings. These are scanner results, not proof that secrets or vulnerabilities cannot exist. **Ran.**
14. **Debug assembly and fresh release lint pass.** Minification/resource shrinking are enabled for release, but release assembly itself fails. **Built.**

## 7. Documentation drift — corrections required

Documentation was not edited during the read-only review. The report records corrections for a future implementation/documentation change.

| Document/reference | Drift | Correction based on actual implementation |
| --- | --- | --- |
| `docs/technical/product-status.md:7` | Says background backup remains unattended in the same sense as KeyHive. | New consolidation workers require an in-memory session; explain foreground-only expiry evaluation and independent in-flight copies, rather than either “always unattended” or “immediately stops when locked.” |
| `docs/technical/product-status.md:8,19` | Claims a pre-setup source list/add UI and at most three custom relays; points to absent `ui/sources/`. | No source-management route exists in `ui/VaultNavGraph.kt`; domain persistence remains. Align with `docs/features/backup-source-management.md`. |
| `docs/technical/architecture.md:48` | Says four fixed defaults. | `DefaultBackupSources.all` contains five. |
| `docs/technical/architecture.md`, “App-entry safety gate” | Attributes startup consolidation/precondition checking to VaultApplication. | `VaultApplication.kt:19–26` only clears legacy state and explicitly does not start consolidation; the successful passphrase gate queues work. |
| `docs/technical/security-model.md:38` and `docs/technical/ssh-key-seed-derivation.md`, final “Important distinction” paragraph | Say only initial creation uses the `vkbckp` relay identity and later general Vault Key backup uses the stored original-seed identity. | The current factory supplies `vkbckp` for Vault Key operations in every engine run; associated records retain the original identity. `docs/features/vault-key-setup.md` and `encrypted-backup.md` describe this more accurately. |
| `docs/features/vault-key-setup.md:14–18`; `docs/technical/architecture.md:62`; `docs/features/encrypted-backup.md`, setup lifecycle | Present quorum as a universal prerequisite for completed setup. | Imported setup enforces it; generated setup currently completes unconditionally after combination verification. Fix B04 rather than merely normalizing a weaker promise. |
| `docs/features/vault-key-setup.md:16`; `docs/technical/product-status.md:9` | Promise stored-key retry without repaste. | Initial import failure does not enable the retry flag; restart routes to Get Started. Fix B10 and add durable-flow tests. |
| `docs/technical/brave-sync-backup-protocol.md`, “Verify” / failure table | Presents read-back as applying to acknowledged backup writes generally; says missing entry success fails. | Vault Key writes have no read-back and missing/empty commit responses currently succeed. Fix B05 and document scope precisely. |
| `docs/technical/brave-sync-backup-protocol.md:84` | Says presence checks do not decrypt payloads. | Brave indexing decrypts the outer Nigori password payload through PasswordReader. SSH private-key parsing and comparison are deferred; distinguish the two encryption layers. |
| `docs/technical/brave-sync-backup-protocol.md`, glossary/key-name/session sections | Implies key-name/keybag rotation support; describes salt as generally random per chain. | Current initialization uses protocol-fixed PBKDF2 salt, and the read path derives one key set rather than processing arbitrary keybag rotation. State the actual compatibility scope. |
| `docs/technical/brave-sync-backup-protocol.md`, “Security properties” | Says credentials never cross server boundaries. | Transport/session objects are separate, but the same mnemonic deterministically derives the same auth identity and key material at compatible endpoints. Do not imply endpoint-specific credential derivation. |
| `docs/features/device-authentication.md`; `docs/technical/security-model.md:27`; stakeholder lock descriptions | Promise strong biometric enforcement on every supported API. | Deprecated pre-30 no-CryptoObject prompt defaults to weak biometric support. Fix H01 or explicitly revise the approved policy. |
| `docs/features/device-authentication.md:12`; lock/session descriptions | Can be read as secrets being dropped at the 30-second background instant. | Actual decision happens on foreground return; in-flight snapshots persist. State that distinction or implement real expiry. |
| `docs/features/sensitive-key-access.md:9–16`; `scope-and-roadmap.md:19,26` | Describe Show private key/reveal dialogs and planned export. | Current AccountDetail offers passphrase-gated Notes and Clipboard exports, not the described on-screen reveal flow. Export is implemented, with limitations. |
| `docs/stakeholder/screens-and-states.md:108` | Says generated VaultKey is never stored and discarded when wizard leaves. | Generated root is committed to secure storage before backup/export and wizard progress is durable; only the passphrase/recovery indices are memory-only. |
| `docs/stakeholder/user-journeys.md:32` | Says generated setup cannot complete because a post-verification step is unimplemented. | Completion exists at `completeGeneratedVaultKeySetup`; the issue is missing quorum enforcement, not absent completion. |
| `docs/stakeholder/user-journeys.md`, account inspection safety expectation | Says Account Details never exports/copies private material. | It deliberately exports the passphrase-encrypted OpenSSH key after reauthentication. Public-copy and encrypted-private-export actions are separate. |
| `docs/stakeholder/product-overview.md`, key concepts/boundaries; `scope-and-roadmap.md:30`; `backup-and-recovery.md:56`; `user-journeys.md:97` | Categorically deny recovery of a lost Vault Key/private key. | Secret Combinations plus its passphrase can recover a remotely backed-up Vault Key; neither can recover a forgotten passphrase or a missing/unavailable valid backup. Use a recovery-material matrix rather than a blanket denial. |
| `docs/features/associated-account-management.md`, behavior step 3 versus expectations; flowchart account-name node | One paragraph says 8-character name minimum, while code and other docs say 3. | `AccountService.kt:99` and NewAccountScreen enforce 3–128. Also disclose public publication before entry. |
| `docs/features/vault-key-setup.md`, strength projection description; comments in CreateVaultKeyViewModel/SecretCombinationsState | Some passages/comments call UI projections entirely non-secret. | Strength findings intentionally carry matched passphrase fragments; combination indices encode recovery secrets. They are not persisted, but are sensitive in-memory state. |
| `app/src/test/resources/golden/README.md` | Says SSH seed extraction remains to be built; calls official NIP-44 vectors ChaCha20-Poly1305 parity. | SSH extraction is implemented/tested. NIP-44 v2 is not ChaCha20-Poly1305; describe its actual construction rather than using the wrong AEAD name. |
| `docs/stakeholder/flowcharts.md`, Vault Key consolidation prose | Says the Vault Key is never restored remotely without restricting the statement to consolidation. | Consolidation does not restore the root; the separate Recover VaultKey flow does. The entry diagram already shows that flow. |

Some documents correctly preserve important limitations: no reset/deletion, no proof that a receiving notes app retained an export, and missing live interoperability are confirmed, not refuted. Those limitations need mitigation/approval rather than removal from docs.

## 8. Test, build and CI evidence

### Commands and outcomes

Commands were run from the repository root unless noted. Logs were written under `/tmp/vault-review-run/` rather than to source files. JDK 17 ran Gradle; Gradle provisioned/used JDK 21 for host tests as configured. No emulator/device was attached.

| Exact command | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL in 998 ms; **34 tasks up-to-date**. Not counted as fresh test execution. |
| `./gradlew :app:testDebugUnitTest --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL in 2m 10s**, 34 executed tasks. XML results: **198 tests, 0 failures, 0 errors, 0 skipped**, 36 suites. |
| `./gradlew :app:assembleRelease` | **BUILD FAILED in 13s** at R8; 46 actionable tasks, 3 executed/43 up-to-date. No validated release APK produced. |
| `./gradlew :app:lintRelease` | BUILD SUCCESSFUL in 820 ms; analysis/report outputs cached. |
| `./gradlew :app:lintRelease --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL in 49s**, 28 executed tasks; XML: **51 warnings, 0 errors**. |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL in 10s**, 40 actionable tasks, 4 executed/36 up-to-date. |
| `./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency okhttp --console=plain` | Success; resolves requested 4.12.0 to **5.3.2** through Quartz. |
| `./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency kotlin-stdlib --console=plain` | Success; resolves **2.3.20**. |
| `adb devices -l` | Empty attached-device list. No install, instrumentation, biometric, screenshot or device-transfer tests were run. |
| `docker run --rm --user "$(id -u):$(id -g)" --volume "$PWD:/repo:ro" ghcr.io/gitleaks/gitleaks:v8.30.1 detect --source=/repo --redact --no-banner --exit-code=1` | Exit 0; **287 commits**, ~3.63 MB scanned, **no leaks found**. Read-only repository mount. |
| `docker run --rm --volume "$PWD:/src:ro" --workdir /src semgrep/semgrep:1.180.0 semgrep scan --config p/default --config p/kotlin --config p/security-audit --metrics=off --severity ERROR --error` | Exit 0; **55 rules run, 213 targets, 0 findings**, four ignored matches. Registry selected 313 code rules but only applicable rules ran. Read-only mount. |
| `git diff --exit-code` and `git status --short` before report creation | No tracked changes; pre-existing untracked `audits/` only. |

### Release failure — verbatim relevant output

```text
ERROR: Missing classes detected while running R8. Please add the missing classes or apply additional keep rules that are generated in /home/daniyal/projects/ZenTenApps/vault-kotlin/app/build/outputs/mapping/release/missing_rules.txt.
ERROR: R8: Missing class javax.management.MBeanException (referenced from: java.lang.Throwable org.apache.sshd.common.util.ExceptionUtils.peelException(java.lang.Throwable))
Missing class javax.management.ReflectionException (referenced from: java.lang.Throwable org.apache.sshd.common.util.ExceptionUtils.peelException(java.lang.Throwable))
Missing class net.i2p.crypto.eddsa.EdDSAPrivateKey (referenced from: java.lang.Class org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderUtils.getEDDSAPrivateKeyType())
Missing class net.i2p.crypto.eddsa.EdDSAPublicKey (referenced from: java.lang.Class org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderUtils.getEDDSAPublicKeyType())
Missing class org.slf4j.impl.StaticLoggerBinder (referenced from: void org.slf4j.LoggerFactory.bind() and 3 other contexts)

> Task :app:minifyReleaseWithR8 FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:minifyReleaseWithR8'.
> A failure occurred while executing com.android.build.gradle.internal.tasks.R8Task$R8Runnable
   > Compilation failed to complete

BUILD FAILED in 13s
46 actionable tasks: 3 executed, 43 up-to-date
Configuration cache entry reused.
```

Repeated warning, also verbatim:

```text
WARNING: R8: An error occurred when parsing kotlin metadata. This normally happens when using a newer version of kotlin than the kotlin version released when this version of R8 was created. To find compatible kotlin versions, please see: https://developer.android.com/studio/build/kotlin-d8-r8-versions
```

Do not conflate metadata warnings with the directly reported missing-class build failure; both require triage, but causation beyond those messages was not proven.

### Synthetic probes and their limits

Temporary probe sources were compiled against the actual debug host-test classpath using the cached Kotlin compiler/JDK 21. No probe was added to `app/src/test` or run against real user credentials. Classpath extraction used an external Gradle init script:

```text
./gradlew -I /tmp/vault-review-run/classpath.init.gradle :app:reviewPrintTestClasspath --no-configuration-cache
```

The first classpath-extraction attempt failed because a transform output was queried without a task dependency; the external init script was corrected to depend on the existing transform tasks, then extraction succeeded. This was harness setup, not a repository build defect.

Probe runs used:

```text
$JDK/bin/javac -cp "$CP" /tmp/vault-review-run/ReviewProbe.java
$JDK/bin/java -cp "/tmp/vault-review-run:$CP" ReviewProbe
$JDK/bin/java -cp "$COMPILER:$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d /tmp/vault-review-run/contracts.jar /tmp/vault-review-run/ReviewContracts.kt
$JDK/bin/java -cp "/tmp/vault-review-run/contracts.jar:$CP" ReviewContractsKt
$JDK/bin/java -cp "$COMPILER:$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d /tmp/vault-review-run/relayprobe.jar /tmp/vault-review-run/RelayProbe.kt
$JDK/bin/java -cp "/tmp/vault-review-run/relayprobe.jar:$CP" RelayProbeKt
```

Here `$JDK` was `/home/daniyal/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`, `$CP` was the extracted test classpath, and `$COMPILER` was the cached `kotlin-compiler-embeddable-2.2.21.jar`.

Observed probe assertions:

```text
SSH parser accepts seed inconsistent with advertised public key=true
SSH metadata validator accepts rounds=2147483647 (KDF intentionally not run)
Brave accepts empty HTTP-200 protobuf as successful index+Nigori init+commit=true; entries=0; requests=4
Nigori altered IV accepted=true
Vault recovery accepts different key under original recovery words=true
Concurrent secure-storage inserts: stored account records=2; visible registry accounts=1
Vault quorum succeeds when every source always reads empty and only acknowledges writes=true
Relay explicit CLOSED restricted reported as successful empty index=true
```

Important qualifications:

- The Java loopback HTTP probe emitted an Android `Log.isLoggable` not-mocked warning during platform detection but continued and completed its real HTTP fixture requests. It did not validate Android TLS/platform behavior.
- Initial Kotlin compiler bootstrap failed due to a missing reflection class in the compiler launcher classpath; using the existing complete test classpath corrected the temporary harness.
- Attempts at an actual raw host websocket fixture were **inconclusive** and included timeout/disconnection behavior under the Android host classpath. They are not used as evidence of relay interoperability or the CLOSED defect. The final deterministic injected-socket callback probe demonstrated that defect using the actual Quartz/Vault protocol path with platform logging mocked.
- The storage race used a controlled SharedPreferences double and mocked crypto to force the real service’s non-atomic read-modify-write interleaving. It establishes a logical lost update, not measured Android disk corruption.
- No huge KDF, gzip bomb, stack-overflow payload, factory reset, device wipe or stress-induced data loss was executed.

### Test quality assessment

The passing suite is valuable and includes real contract coverage, not merely UI snapshots. In particular, the 12 engine tests assert source order, restore-before-write, known/unknown distinction, targeted scope, freshness, and Vault Key separation; nine setup tests assert quorum retention and preconditions; fourteen SSH-codec tests assert fixtures, parsing, normalization and supported generation settings.

However, important contracts are not established:

- `NostrRelayServiceTest` has only three tests of auth-required string detection and duplicate-acknowledgement classification; it does not exercise websocket AUTH/OK/REQ/CLOSED sequencing.
- `VaultKeyRecoveryServiceTest` has four tests centered on Nostr recovery, future date wrapping, connectivity and a wrong passphrase. It does not establish Brave-only real-server recovery, wrong-root binding, all duplicate candidates, or recovery interrupted at persistence boundaries.
- Crypto fixtures predominantly cover valid vectors; malformed key integrity, hostile work factors, parser fuzzing, MAC/IV limitations and seed/public consistency require additional tests.
- Keystore and secure-storage instrumented tests cover round-trip, fresh IV, tamper rejection and recreation/order, not key invalidation, migrations, OEM device transfer, storage-full failure or concurrent transactions. They were not run here.
- No source `@Ignore`/`@Disabled`/assumption-based skips were found in the app test search, and fresh XML reports zero skipped. That does not establish behavioral completeness or code coverage percentage; no coverage measurement was run.
- Pure device-lock policy tests do not prove actual prompt strength, Android callback order, app-switcher behavior or secret expiry.

### CI interpretation

`.github/workflows/ci.yml` runs host tests, debug assembly and debug lint. `.github/workflows/security.yml` runs full-history Gitleaks and ERROR-gated Semgrep, including a scheduled scan. These are useful safeguards and both local scanner runs passed. Missing enforced branch controls, minified release tests, real endpoints, instrumentation and artifact provenance remain release gaps; absence of DAST is not by itself a demonstrated vulnerability.

## 9. Open questions, assumptions and required follow-up

1. **Operational endpoint contract:** are litesync1/litesync2 and the official Brave server tested for empty/error responses, pagination, duplicate client tags, timestamp windows, key rotation, server resets and durable read-after-write? No live backup traffic was generated here.
2. **Actual independence of five sources:** separate URLs do not establish independent ownership, storage, failure domains, retention or backup restoration. Infrastructure evidence is required.
3. **Authentication and session policy:** is 30 seconds only a return-to-foreground privacy gate, or a requirement to clear secrets/stop background work at that instant? Are weak biometrics on older APIs explicitly acceptable? Current documentation suggests stronger guarantees than code.
4. **Recovery trust and identity:** should custom sources not trusted for associated restore be usable for Vault Key recovery? Should any successful root recovery require re-establishing destination quorum before new account creation? Current recovery sets setup complete immediately.
5. **Passphrase changes:** the account service comments allow restored accounts to retain a different passphrase, but current automatic restoration parses every associated key with the current Vault Key passphrase (`BackupSourceAdapters.kt:141,250–257`). No passphrase rotation/multiple-passphrase recovery policy was established.
6. **User export:** are supported text recipients actually encrypted/private, and what is the approved wording when Android accepts a target but it never retains a copy? Paste-back proves a readable copy, not durable custody. No receiving app was exercised.
7. **Play/privacy obligations:** what public privacy policy, Data safety answers, encryption/export declarations, third-party disclosure/retention agreements and account-deletion-policy analysis have been approved? No Play Console or legal records were available. No specific store-policy breach is asserted without them.
8. **Signing:** where are production signing keys held, which sibling apps share the trust certificate, how are they reviewed, and how do signing-key upgrades preserve KeyHive access/update continuity? No keystore contents or credentials were inspected or changed.
9. **Incident and rotation:** who can disable/replace a compromised relay, how do fixed defaults get updated, how are SSH authorized_keys revoked outside this app, and how do users migrate away from a compromised root? Lack of a UI rotation feature does not remove the need for a support runbook.
10. **GitHub administration:** are branch/ruleset protections, required reviews/checks, Dependabot alerts and secret rotation actually enabled? The repo claims some external settings and says branch protection is absent; external settings were not queried.
11. **Device matrix:** actual API 26–36 behavior, PIN-only/strong/weak biometric cases, OEM background restrictions, screenshots/Recents, Doze, low-memory process death, Keystore security level, transfer and upgrade remain unverified. No attached device was available.
12. **Supply-chain scope:** this review checked selected current advisories and resolved artifacts, not every transitive/native component or every CVE. The known advisory examples were function-triaged; absence of an applicable advisory elsewhere is not established.
13. **Reproducibility:** builds used existing SDK/dependency caches; the fresh flags force task execution, not an isolated clean-room dependency acquisition. No bit-for-bit reproducibility, signed artifact verification or 16 KB page-size runtime certification was performed.
14. **Final acceptance:** who signs off specialist security assessment, remediation retests, UX/accessibility and support drills? The five release gates need named owners and evidence, not a status change based on this document alone.

## 10. Prioritized remediation and exit criteria

1. **Stop credential-integrity/recovery defects first:** transactional secure persistence (B01–B02), seed/public validation (B03), root-to-mnemonic binding and candidate iteration (B09).
2. **Make backup guarantees truthful:** generated quorum (B04), strict Brave response/read-back validation (B05), complete inventories (B06), real retry semantics (B08), durable setup retry (B10).
3. **Bound hostile work and fix main-thread crypto:** B07/B11; add negative/fuzz and low-end device tests.
4. **Close user-safety decisions:** public-name disclosure (B12), pre-30 auth strength, secret expiry, offline local access, clipboard/export warnings, recovery-material matrix.
5. **Produce and validate a release artifact:** B13; aligned resolved toolchain, narrowly reviewed R8 rules, protected signing, minified runtime tests, SBOM/verification/provenance and enforced checks.
6. **Run release acceptance:** real supported endpoint tests, clean-device restoration with retained test keys, lifecycle/storage-failure matrix, privacy/support/incident documentation and drills, then specialist remediation retest (B14).

**Release exit condition:** all section-3 blockers fixed and regression-tested, residual risks explicitly accepted by accountable owners, and every gate in `scope-and-roadmap.md:36–40` supported by dated acceptance evidence. Passing the existing 198 tests or the scanners alone is insufficient.

---

### Documentation/change-control note

No implemented behavior, configuration or source was changed during the review, so feature/technical/flowchart documents were intentionally left untouched. This requested audit report records the documentation corrections and future implementation acceptance criteria; those changes should be made together with remediation, not silently introduced during an independent read-only assessment.
