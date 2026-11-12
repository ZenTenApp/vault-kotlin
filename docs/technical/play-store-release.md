# Publishing to Google Play

This is the end-to-end release runbook for shipping ZenTen Vault (`app.zenten.vault`)
to Google Play. It covers the repository work that must land first, signing, the
release build, store-listing and policy declarations, and the Play Console track
sequence.

The [release gates](../stakeholder/scope-and-roadmap.md#release-gates-before-production-use)
still govern whether publishing is allowed at all. This document explains *how* to
publish once those gates are closed; it does not close them.

> **Current status: not submittable.** Linking this runbook in the repository does
> not make the app releasable. Phase 1 lists the specific, verifiable blockers that
> remain at the revision this document was written against.

## Phase 0 — Facts about this app that change the normal playbook

Read these before the checklist. Each one is specific to Vault and causes a real
mistake if it is missed.

| Fact | Why it matters for Play |
| --- | --- |
| `app.zenten.vault` is the `applicationId` | It is permanent. It identifies your account in Play Console and cannot be changed after the first upload. |
| `targetSdk = 36`, `minSdk = 26` | Satisfies Play's requirement that new apps target API 36 (Android 16) after 2026-08-31. Targeting 36 means edge-to-edge and Android 16 behaviour changes are live in production. |
| The app ships native libraries (`libsecp256k1-jni.so`, `libsqliteJni.so`, `libdatastore_shared_counter.so`, `libandroidx.graphics.path.so`) for four ABIs | Play requires 16 KB page-size alignment for apps targeting API 35+. Verified OK on the current build — but re-verify after **every** dependency bump. |
| `app.zenten.permission.VAULT_ACCESS` is a **signature-level** permission guarding the `AssociatedAccountsProvider` | The distributed app is re-signed by Google under Play App Signing. Sibling apps (KeyHive consumers) must be signed with the *Play app-signing certificate*, not your upload key, or the provider silently stops being reachable. This is a security boundary, not packaging trivia. |
| `android:allowBackup="false"` with `dataExtractionRules` and `fullBackupContent` | No app data is transferred by cloud backup or by device-to-device transfer; both sections of `res/xml/data_extraction_rules.xml` exclude every domain. Play Data safety must therefore describe no OS-level transfer of vault data, and migration is the in-app **Recover VaultKey** path. See [Device migration and backup](../features/device-migration-and-backup.md). |
| No analytics, crash reporting, or telemetry SDK | Simplifies Data safety: you declare no first-party collection. Everything that leaves the device goes to third-party relays **at the user's direction**. |
| The user's account **label** is plaintext on relay events and Brave Sync records (`label` tag / `label:<name>` note) | This is real user data leaving the device readable by relay operators. Data safety must declare it, and the privacy policy must describe it. |
| Creating a VaultKey needs live network access (2 Nostr relays + 2 Brave Sync endpoints) and a validated HTTPS clock check | Internal-test builds cannot be exercised on a plane, in airplane mode, or behind a captive portal. Give testers real devices on real networks. |
| Release builds are minified by R8 with `isShrinkResources = true` | The `mapping.txt` must be uploaded with every release, and the minified build must be smoke-tested on a device — the debug build proves nothing about R8 keep rules. |

## Phase 1 — Close the release blockers

Do not start Play Console work before this phase is done. Uploading an app you then
have to replace is worse than waiting. Track these against
[`audits/round2/consolidated-production-readiness-audit-2026-11-11.md`](../../audits/round2/consolidated-production-readiness-audit-2026-11-11.md).

| # | Blocker | Status | What "done" looks like |
| --- | --- | --- | --- |
| 1 | **H-01** bcrypt KDF on the UI thread | Addressed in `c51a8b7` | Verified on a device with a 8192-round container: no frozen screen, no ANR, on every passphrase-entry path. |
| 2 | **H-02** configuration change forces re-authentication | Addressed in `c51a8b7` | Rotate the device mid-flow and confirm the session survives; confirm process death still relocks. |
| 3 | **H-03** D2D transfer / undecryptable-vault dead end | **Addressed in code, pending device verification** | `dataExtractionRules` excludes every domain from both cloud backup and D2D transfer, plus `fullBackupContent="false"` for ≤ API 30; `SecureRecordState` + the `unreadableRecords` scan tell "ciphertext present but unreadable" apart from "absent", and separate a missing wrapping key from a damaged payload and from a Keystore the device could not read; the launch gate routes that state to `StrandedVaultScreen`, and `StrandedVaultResetService` gives the `pendingLocalBackupAccountIds` lock an exit. Verify on an API 31+ emulator over the OEM matrix recorded in the audit errata. |
| 4 | **H-04** unsigned release artifact | **Open** | Phase 2 complete: `signingConfigs.release` wired, `apksigner verify` passes, upload to Play Console succeeds. |
| 5 | **H-05** release variant never built in CI | **Open** | CI runs `:app:bundleRelease` and `:app:lintRelease`, and fails when the bundle is unsigned. |
| 6 | **M-06** no delete / reset path | **Open** | Play's account-deletion requirement is only enforceable if a deletion path exists. Resolve or get a documented legal decision that the requirement does not apply (Vault has no server-side account). |
| 7 | **M-01** workers report success when every source failed | **Open** | Backup status shown to users is truthful; a users' first backup failure is not hidden. |
| 8 | Independent security assessment | **Open** | Required by release gate 1. Key management software should not reach public distribution without it. |
| 9 | End-to-end interoperability with live relays | **Open** | Required by release gate 2. Exercise create → backup → restore on a second device against the production relay and litesync endpoints. |

Also decide, and write down, the following before you build anything:

- **Who is the developer of record** (personal vs. organization Play account).
- **Who holds the upload keystore** and who can produce a release.
- **Whether a shared certificate with KeyHive sibling apps is required** — this
  decides whether you accept Google-generated app signing keys or supply your own.

## Phase 2 — Create the upload key and wire signing

### 2.1 Generate the upload keystore (once, ever)

Run this outside the repository and store the output in a secrets manager, never in
git:

```bash
keytool -genkeypair \
  -keystore vault-upload.jks \
  -alias vault-upload \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype JKS
```

Rules for this file:

- **Never commit it.** `.gitignore` already covers `*.keystore`; add `*.jks` to be
  explicit. A committed keystore cannot be un-published from git history.
- Back it up in at least two independent, access-controlled locations. Losing the
  upload key means a support escalation to Google to reset it.
- Use `4096`-bit RSA. Play accepts 2048, but the upload key is long-lived.
- Record the certificate SHA-256 fingerprint somewhere durable:

```bash
keytool -list -v -keystore vault-upload.jks -alias vault-upload
```

### 2.2 Add a signing config fed from environment variables

In `app/build.gradle.kts`, add a `signingConfigs` block and attach it to the
`release` build type. Read the secrets from the environment so nothing sensitive is
committed, and fail the build loudly when they are missing rather than silently
producing an unsigned artifact:

```kotlin
android {
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("VAULT_RELEASE_KEYSTORE")
                ?: error("VAULT_RELEASE_KEYSTORE is not set")
            storeFile = rootProject.file(keystorePath)
            storePassword = System.getenv("VAULT_RELEASE_STORE_PASSWORD")
                ?: error("VAULT_RELEASE_STORE_PASSWORD is not set")
            keyAlias = System.getenv("VAULT_RELEASE_KEY_ALIAS")
                ?: error("VAULT_RELEASE_KEY_ALIAS is not set")
            keyPassword = System.getenv("VAULT_RELEASE_KEY_PASSWORD")
                ?: error("VAULT_RELEASE_KEY_PASSWORD is not set")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ... existing minify/shrink/proguard configuration ...
        }
    }
}
```

Do **not** put these values in `gradle.properties` (it is tracked) or in
`local.properties` (not a secrets store). For local convenience, use
`~/.gradle/gradle.properties` or export them in the shell that builds the release.

Keep the existing R8 configuration untouched:
`app/proguard-rules.pro` carries deliberate `-keep`/`-dontwarn` rules for
BouncyCastle, the secp256k1 JNI bindings, sshd, and kotlinx.serialization. Any
change there needs a device pass on the minified build.

### 2.3 Enrol in Play App Signing

New apps on Play must use Android App Bundles and Play App Signing. Two options:

- **Let Google generate and hold the app-signing key** (default, recommended for a
  first release). Google re-signs each generated APK; you never see the key.
- **Supply your own app-signing key** if you need signature continuity with apps
  already in the field, or if KeyHive sibling apps must share the certificate.

The decision has a security consequence: `VAULT_ACCESS` is signature-level, so
every trusted sibling app must be signed with the certificate that the *distributed*
Vault app ends up carrying. With Google-generated keys that is Google's app-signing
certificate, not your upload key — confirm how sibling builds will be signed before
you commit to it.

Record the resulting app-signing certificate SHA-256 in
`docs/technical/key-hive-provider.md` and in the sibling apps' build configuration.

## Phase 3 — Set the version

Per [Build and test](build-and-test.md#versioning-shared-apks):

- `versionCode` must increase for **every** upload, including every internal-test
  build. Play rejects a re-used or lower value.
- `versionName` is the user-visible SemVer string. Reserve `1.0.0` for the first
  public production release; a closed-test milestone is `0.9.0-rc.1` or similar.

At the revision this runbook was written, `app/build.gradle.kts` carries
`versionName = "0.0.1"` with `versionCode = 1`. A bare `"1.0"` is not valid SemVer per
that policy and must not be used for a public release; reserve `1.0.0` for the first public
production release and bump `versionCode` for every upload.

## Phase 4 — Build and verify the release bundle

Signing credentials are read from the untracked `keystore.properties` at the
repository root (see [Release signing](build-and-test.md#release-signing)); the
build fails to sign, or produces an unsigned artifact, if that file is absent or
incomplete. No environment variables are involved.

```bash
# Full local gate — mirrors what CI should run
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
./gradlew :app:bundleRelease :app:assembleRelease
```

Artifacts:

```text
app/build/outputs/bundle/release/app-release.aab       # upload this
app/build/outputs/apk/release/app-release.apk          # sideload/smoke test only
app/build/outputs/mapping/release/mapping.txt          # upload with the release
```

Verify before uploading:

```bash
# 1. Bundle is signed with the upload key
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab

# 2. APK carries the expected certificate
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk

# 3. 16 KB page-size alignment (required for targetSdk 35+)
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 \
  app/build/outputs/apk/release/app-release.apk

# 4. Manifest sanity: versionCode, versionName, targetSdk, permissions
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging \
  app/build/outputs/apk/release/app-release.apk
```

Then **smoke-test the minified APK on real hardware**. The debug build is not
evidence. Minimum pass:

1. Fresh install on an API 26-class device and on an API 36 device.
2. Unlock gate: PIN-only, strong biometric, cancelled prompt, biometric lockout.
3. Rotate the device mid-flow — session must survive.
4. Create a VaultKey end-to-end: passphrase policy, all four backup destinations,
   quorum reached, backup export, paste-back confirmation, Secret Combinations.
5. Paste an existing VaultKey; confirm import and retry-on-failed-backup.
6. Create an associated account, complete the mandatory local export and paste-back.
7. Restart the app; confirm the account list, per-source backup status, and
   consolidated restore on a second device.
8. Export Account to Notes and to Clipboard; confirm the passphrase gate runs off
   the main thread and the confirmation dialog stays responsive.
9. Verify screenshots are blocked (`FLAG_SECURE`) and Recents shows no content.
10. Confirm the KeyHive provider is readable by a sibling app signed with the
    app-signing certificate and **not** by an unrelated app.

Record the device/OS/result matrix in the release ticket. Retain `mapping.txt` —
without it, Play cannot deobfuscate R8 stack traces.

## Phase 5 — Make CI produce and protect the release artifact

`H-05`. Add to `.github/workflows/ci.yml` (or a dedicated release workflow triggered
on a tag):

1. `./gradlew :app:lintRelease` — currently absent, so R8/shrinker regressions pass CI.
2. `./gradlew :app:bundleRelease` with the keystore and passwords injected from
   GitHub Actions secrets.
3. A failing assertion that the produced `.aab` is signed (the `jarsigner -verify`
   check above, wrapped in a shell step).
4. Upload the `.aab` and `mapping.txt` as workflow artifacts.
5. Prefer a dedicated `release` workflow gated behind an environment with required
   reviewers, so signing secrets are not exposed to pull-request-triggered runs.

Do not expose signing secrets to `pull_request` workflows — that includes PRs from
branches inside the same repository.

## Phase 6 — Produce the store listing assets

Nothing here exists in the repository yet; Play requires all of it before review.

| Asset | Requirement |
| --- | --- |
| App icon | 512 × 512, 32-bit PNG with alpha, sRGB, ≤ 1 MB. Square, no rounding or shadow — Google masks it. Generate from the existing `vault_app_icon.xml` glyph over the `#121212` background so it matches the launcher icon. |
| Feature graphic | 1024 × 500, JPEG or 24-bit PNG **without** alpha. Required. |
| Phone screenshots | 2–8 per form factor, 320–3840 px per side, long side ≤ 2 × short side. Capture on an API 36 device; `FLAG_SECURE` blocks normal screenshots, so use a staging build or an external capture path. Never show real keys, passphrases, or Secret Combinations. |
| Tablet screenshots | Recommended: the app is Compose-based and runs on tablets; the audit calls out tablet/foldable rotation specifically. |
| App name | Max 30 characters. In-app the label is `Vault`; the listing will need a distinct, non-generic title (e.g. "ZenTen Vault — SSH Keys") because a bare "Vault" invites impersonation/policy review and is not discoverable. |
| Short description | Max 80 characters. |
| Full description | Max 4000 characters. Must not promise recovery you cannot deliver — Vault cannot reset a forgotten passphrase. |
| Privacy policy URL | Publicly reachable, no login, names the app and developer. |
| Support contact | Email and, ideally, a support URL with the recovery-material limitations explained. |

Copy must match the actual product. The stakeholder docs are the source of truth for
what the app does; do not publish capability claims that contradict
[Scope and roadmap](../stakeholder/scope-and-roadmap.md).

## Phase 7 — Complete the Play Console declarations

Create the app in Play Console (**All apps → Create app**), then fill every item under
**Policy → App content**. Each answer becomes a legal attestation.

1. **Privacy policy** — URL from Phase 6.
2. **App access** — the app requires device authentication and a VaultKey passphrase.
   Provide reviewer credentials or a precise description of how to reach each area,
   otherwise review cannot complete.
3. **Ads** — no ads.
4. **Content rating** — complete the IARC questionnaire. Expect a low rating; the
   questionnaire asks about user-generated content sharing and data transmission.
5. **Target audience** — 18+ is the defensible choice for a credential manager.
6. **News app**, **COVID-19**, **data safety for government**, **financial features**,
   **health** — answer no unless the product genuinely qualifies.
7. **Data safety** — the most consequential form. For this app:
   - No first-party collection: there is no analytics, no crash reporter, no
     developer-operated backend.
   - Answer the device-transfer question as **no OS-level transfer**: both the cloud
     backup and device-to-device sections of `res/xml/data_extraction_rules.xml`
     exclude every domain, so the vault, the VaultKey and the account records are not
     carried to a new device by the platform. A user moves devices by installing
     fresh and using **Recover VaultKey**.
   - Data **is** transmitted off-device at user direction to third-party relays and
     litesync endpoints. Declare the account name/label and the encrypted key
     material as collected/shared app functionality data.
   - State that the encrypted private keys stay encrypted (OpenSSH AES-CTR container
     wrapped in NIP-44 for relay records) and that the account **label is readable**
     by anyone who can list those records.
   - Data in transit is encrypted (TLS). Confirm this stays true for any endpoint you
     add later.
   - The "users can request data deletion" answer depends on M-06. Answering yes
     without an in-app path is a policy violation; answering no needs a documented
     decision, since remote relay copies by design cannot be guaranteed deleted.
     Play's account-deletion requirement formally targets apps with in-app account
     creation — confirm with legal counsel whether a locally held VaultKey identity
     counts.
8. **Export compliance / encryption self-classification** — the app uses encryption
   (TLS, BouncyCastle, secp256k1, NIP-44). Declare it; most consumer apps qualify for
   the mass-market exemption. Get this answer reviewed, not guessed.
9. **Sign-in details / credentials** for reviewers, as above.
10. **Government app** and **financial services** declarations if applicable (they are
    not).

## Phase 8 — Set up Play Console access and test tracks

1. **Developer account.** One-time $25 fee, identity verification (D-U-N-S number for
   organizations), and 2-factor authentication enforced on all accounts.
2. **Personal accounts created after 2023-11-13** must run closed testing with **at
   least 12 testers opted in continuously for 14 days** before production access can
   be requested. Plan for that calendar time; a personal account cannot launch on the
   day the build is done. Organization accounts are not subject to this rule.
3. **Internal testing** — up to 100 testers, no full review, fastest way to get the
   bundle onto devices. Use it for the Phase 4 device matrix, then keep it as the
   permanent dogfood track.
4. **Closed testing (alpha)** — the track that satisfies the 12-tester requirement.
   Recruit testers who will actually connect to real networks and complete a full
   backup-and-restore cycle; a tester who installs and opens the app once does not
   surface the interesting failures.
5. **Production access application** — Play reviews the closed-test answers, usually
   within about a week. Answer specifically: what was tested, what broke, what was
   fixed.
6. **Upload the bundle** under **Test and release → Testing → Internal testing**,
   attach the R8 mapping file, and add release notes.

For a first upload, keep **Managed publishing** off until you are ready, and keep
**staged rollout** at 100% for internal/closed tracks.

## Phase 9 — Production rollout

1. Promote the verified internal build to **Closed testing** → then **Production**
   (or upload a fresh bundle with the next `versionCode`).
2. Use a **staged rollout** (e.g. 5% → 20% → 50% → 100%) for the first public release.
   Key-management software is the wrong place to discover an OEM-specific crash on
   the whole install base at once.
3. Watch during each stage: Play Console **Android vitals** (ANR rate, crash rate,
   slow start), user reviews, and support email. With no crash reporter in the app,
   vitals is your only objective signal — check it daily during rollout.
4. Have a rollback plan: you cannot unpublish an installed version. The options are
   to halt the staged rollout, ship a fixed build with a higher `versionCode`, or
   use **halt** on the release in Play Console. Write down who can do each, and how
   fast.
5. Publish the release notes honestly. If the build carries a known limitation, say
   so; users of a credential vault read them.

## Phase 10 — Every subsequent release

- [ ] Bump `versionCode`; set `versionName` per the versioning policy.
- [ ] Close or explicitly re-accept any open audit finding.
- [ ] Re-run the full local gate: unit tests, `lintRelease`, `bundleRelease`.
- [ ] Re-verify 16 KB alignment — a dependency bump can break it.
- [ ] Re-run the device smoke matrix on the minified build.
- [ ] Upload the new `mapping.txt` with the bundle.
- [ ] Check Dependabot/security-workflow results since the last release.
- [ ] Update `docs/technical/product-status.md` and any affected feature docs.
- [ ] Staged rollout, then monitor vitals.

## Known gaps in the current repository

These are the concrete items standing between this tree and a successful first
upload. They are listed here so the runbook stays honest:

- No `signingConfigs` in `app/build.gradle.kts`; the existing
  `app/build/outputs/bundle/release/app-release.aab` reports `jar is unsigned`.
- `.github/workflows/ci.yml` never builds, signs, or lints the release variant.
- `dataExtractionRules` / `fullBackupContent` are now declared and exclude every domain (H-03); what remains is device verification over the OEM matrix, not code.
- No delete/reset path anywhere in the app (M-06).
- No store-listing assets, privacy policy, or support URL in the repository.
- No committed decision on the app-signing certificate for KeyHive sibling apps.
- The minified release build has never been recorded as device-tested.
