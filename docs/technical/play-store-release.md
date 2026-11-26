# Publishing to Google Play

This guide covers how to publish ZenTen Vault (`app.zenten.vault`) on Google Play:
what to decide first, signing, building and verifying the bundle, the store listing,
the Play Console policy declarations, test tracks, and production rollout.

The [release gates](../stakeholder/scope-and-roadmap.md#release-gates-before-production-use)
still decide whether the app may be published at all. This guide explains *how* to
publish once those gates are met. It does not meet them.

> **Status: not ready to submit.** The signing key is still a development key, the
> minified build has not been tested on a device, and the store assets and privacy
> policy do not exist yet. See [Readiness checklist](#readiness-checklist).

## Phase 0 — Facts about this app that change the usual steps

Read these first. Missing any of them leads to a real mistake.

| Fact | Why it matters on Play |
| --- | --- |
| `applicationId` is `app.zenten.vault` | It is permanent. Play identifies the app by it, and it cannot change after the first upload. |
| `targetSdk = 36`, `minSdk = 26` | Meets Play's current target-API rule for new apps. Android 16 behavior (edge-to-edge, and so on) applies in production. |
| The app ships native libraries (secp256k1 JNI, DataStore, graphics) for four ABIs | Apps targeting API 35+ must be 16 KB page-size aligned. `release.yml` checks this; re-check after every dependency bump. |
| `app.zenten.permission.VAULT_ACCESS` is a **signature** permission guarding `AssociatedKeysProvider` | Play re-signs the app with the *app-signing key*, not your upload key. KeyHive sibling apps must carry the same app-signing certificate, or they silently lose access to the provider. This is a security boundary. |
| `allowBackup="false"`, `fullBackupContent="false"`, and `res/xml/data_extraction_rules.xml` exclude every domain | The OS never copies vault data through cloud backup or device-to-device transfer. A user moves devices with **Recover VaultKey** or by importing the VaultKey. Data safety must reflect this. |
| `CAMERA` permission, with the camera feature `required="false"` | Used only by **Login with QR Code**. Frames are decoded on the device (CameraX + ZXing) and never stored or sent. Devices without a camera can still install the app. |
| No analytics, crash reporting, ads, or developer-run backend | Data safety declares no first-party collection. Play Console **Android vitals** is the only crash/ANR signal you will have. |
| Data leaves the device to third-party Nostr relays and litesync endpoints (`litesync1.zentext.me`, `litesync2.nostr.box`) when the user backs up | Must be declared. Key material is encrypted, but the **key label is readable** by anyone who can list those records. |
| Creating a VaultKey needs a live network and an HTTPS clock check (`google.com`/`cloudflare.com`) | Testers and Play reviewers need real network access. Airplane mode or captive portals will block setup. |
| Release builds use R8 with resource shrinking | Upload `mapping.txt` with every release, and test the minified build on a device. A working debug build proves nothing about R8 keep rules. |
| `FLAG_SECURE` blocks screenshots | Store screenshots need a separate capture approach (see Phase 6). |

## Phase 1 — Decide before you build

Write these decisions down (for example in the release ticket) before any Play
Console work:

1. **Developer account type.** A *personal* account created after 2023-11-13 must run
   a closed test with at least 12 opted-in testers for 14 days in a row before it can
   apply for production. An *organization* account (needs a D-U-N-S number) skips this.
2. **Who holds the upload key** and who may produce a release.
3. **App-signing key choice** (see 2.3). If KeyHive sibling apps must share Vault's
   certificate, decide now whether Google generates the app-signing key or you supply it.
4. **Key/data deletion position.** The app has no delete-key or reset action
   ([Implementation status](product-status.md)). Vault has no user accounts, so
   Play's account-deletion rule likely does not apply, but the Data safety deletion
   answer must be truthful. Get a written decision, ideally from legal counsel.
5. **Release gates.** Confirm the independent security assessment and the live
   interoperability test (create → back up → restore on a second device) are done or
   formally accepted as open.

## Phase 2 — Create the upload key and wire signing

### 2.1 Generate the upload keystore (once, ever)

Run this **outside the repository** and store the result in a secrets manager:

```bash
keytool -genkeypair \
  -keystore vault-upload.jks \
  -alias vault-upload \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

Rules:

- **Never commit it.** `.gitignore` covers `*.jks` and `*.keystore`, but a keystore
  committed by mistake cannot be removed from git history in practice.
- Keep at least two independent, access-controlled backups. Losing the upload key
  means asking Google support to reset it, which takes days.
- Use PKCS12, not legacy JKS. JKS protects the file with a weak password hash.
- Record the certificate SHA-256 fingerprint somewhere durable:

  ```bash
  keytool -list -v -keystore vault-upload.jks -alias vault-upload
  ```

> **Do not upload with `vault.jks`.** The `vault.jks` in the working tree (untracked)
> is a self-signed `CN=vault` development key in legacy JKS format with no recorded
> history. It is only for sideload and smoke-test builds.

Write a short custody note covering who holds the key, where backups live, how to
rotate the upload key (Play Console → **App integrity → Request upload key reset**),
and what to do if it leaks. The signing identity is also the KeyHive provider's trust
anchor, so treat this as a security control.

### 2.2 Signing from environment variables

Signing is already wired in `app/build.gradle.kts` through `VAULT_RELEASE_KEYSTORE`,
`VAULT_RELEASE_STORE_PASSWORD`, `VAULT_RELEASE_KEY_ALIAS` and
`VAULT_RELEASE_KEY_PASSWORD`. See [Release signing](build-and-test.md#release-signing)
for the full behavior. In short: all four set → signed; none set → unsigned; some set
→ build fails. Set `VAULT_RELEASE_REQUIRE_SIGNING=true` to make "none set" fail too.

For a local release build, export the variables in the shell that runs Gradle. Never
put them in `gradle.properties` (tracked) or `local.properties`.

### 2.3 Enroll in Play App Signing

New Play apps must upload Android App Bundles (`.aab`) and use Play App Signing.
When you create the first release, Play Console asks how to handle the app-signing key:

- **Google generates and holds it** (simplest). Google signs the APKs users install.
- **You supply it** (export with Google's PEPK tool). Needed if sibling apps must
  share the exact certificate and you sign them outside Play.

Afterwards, copy the **app-signing certificate SHA-256** from Play Console →
**Test and release → App integrity** into [KeyHive provider](key-hive-provider.md) and
into the sibling apps' configuration. Sibling apps must be signed with that
certificate, not with your upload key.

## Phase 3 — Set the version

Follow [Versioning shared APKs](build-and-test.md#versioning-shared-apks):

- Increase `versionCode` for **every** upload, including internal-test builds. Play
  rejects a reused or lower value.
- `versionName` is SemVer. Use pre-releases such as `0.9.0-rc.1` for testing tracks and
  reserve `1.0.0` for the first public production release.

## Phase 4 — Build and verify the release bundle

Run the full local gate:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
./gradlew :app:bundleRelease :app:assembleRelease
```

Outputs:

```text
app/build/outputs/bundle/release/app-release.aab   # upload this to Play
app/build/outputs/apk/release/app-release.apk      # device smoke test only
app/build/outputs/mapping/release/mapping.txt      # upload with the release
```

Check before uploading:

```bash
BT=$ANDROID_HOME/build-tools/36.0.0

# 1. Bundle is signed (with the upload key, not vault.jks)
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab

# 2. APK certificate matches the recorded upload-key fingerprint
$BT/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk

# 3. 16 KB page-size alignment
$BT/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk

# 4. versionCode, versionName, targetSdk and permissions are as expected
$BT/aapt2 dump badging app/build/outputs/apk/release/app-release.apk
```

### Device smoke test of the minified build

The debug build does not count. Install `app-release.apk` and test at least:

1. Fresh install on an API 26-29 device and an API 36 device.
2. Unlock: PIN only, strong biometric, cancelled prompt, biometric lockout.
3. Rotate mid-flow; the session survives. Kill the process; the app relocks.
4. Create a VaultKey end to end: passphrase rules, backups reach quorum, export,
   paste-back confirmation, Secret Combinations.
5. Login with QR Code (grant, deny, and "don't ask again" camera permission) and paste login.
6. Create an associated key, including the mandatory export and paste-back.
7. Restart; check the key list and backup status. Recover on a second device.
8. Export a key to Notes and to Clipboard; the clipboard clears after 60 seconds.
9. Screenshots are blocked and Recents shows no content.
10. A sibling app signed with the app-signing certificate can read the KeyHive
    provider; an unrelated app cannot.

Record device, OS version and result for each item in the release ticket. Any crash
caused by R8 usually means a missing rule in `app/proguard-rules.pro`.

## Phase 5 — Make CI produce and protect the release artifact

`.github/workflows/release.yml` runs on a `v*` tag push or a manual
`workflow_dispatch`. It runs unit tests and release lint, builds the signed `.aab`
and `.apk`, asserts the signatures with `apksigner`/`jarsigner`, checks 16 KB
alignment, and uploads the artifacts plus `mapping.txt` (kept 90 days).

The signing secrets live on the **`release` GitHub Environment**, not at repository
level:

| Secret | Value |
| --- | --- |
| `VAULT_RELEASE_KEYSTORE_BASE64` | `base64 -w0 vault-upload.jks` |
| `VAULT_RELEASE_STORE_PASSWORD` | Keystore password |
| `VAULT_RELEASE_KEY_ALIAS` | Key alias |
| `VAULT_RELEASE_KEY_PASSWORD` | Key password |

Before the first Play release, **replace these with the real upload key** from 2.1:

```bash
base64 -w0 vault-upload.jks | gh secret set VAULT_RELEASE_KEYSTORE_BASE64 -e release
gh secret set VAULT_RELEASE_STORE_PASSWORD -e release   # paste at the prompt
gh secret set VAULT_RELEASE_KEY_ALIAS -e release
gh secret set VAULT_RELEASE_KEY_PASSWORD -e release
```

Do not pass values with `--body`; that exposes them in the process list. Add
**required reviewers** on the `release` environment once more than one person can
push. Never add this workflow to `pull_request` triggers.

To cut a release:

```bash
git tag v0.9.0-rc.1 && git push origin v0.9.0-rc.1
gh run watch   # then download the release-* and mapping-* artifacts
```

## Phase 6 — Prepare the store listing

None of these exist in the repository yet.

| Item | Requirement |
| --- | --- |
| App icon | 512 × 512 PNG (32-bit, alpha), ≤ 1 MB, square with no rounding or shadow. Base it on `vault_app_icon.xml` so it matches the launcher icon. |
| Feature graphic | 1024 × 500 JPEG or 24-bit PNG, no alpha. Required. |
| Phone screenshots | 2-8 images, 320-3840 px per side, long side ≤ 2× short side. `FLAG_SECURE` blocks normal capture, so use a staging build or another capture path. Never show real keys, passphrases or Secret Combinations. |
| Tablet screenshots | Recommended; the Compose UI runs on tablets and foldables. |
| App name | ≤ 30 characters. The in-app label `Vault` is too generic for the store; use something distinct such as "ZenTen Vault". |
| Short description | ≤ 80 characters. |
| Full description | ≤ 4000 characters. Do not promise recovery the app cannot give: a forgotten VaultKey passphrase cannot be reset. |
| Privacy policy URL | Public, no login, names the app and developer, and describes the relay/litesync uploads and the readable key label. |
| Support contact | Email (required) and ideally a support page that explains recovery-material limits. |

Every claim must match [Scope and roadmap](../stakeholder/scope-and-roadmap.md).

## Phase 7 — Complete the Play Console declarations

Create the app (**Home → Create app**), then complete everything under
**Policy and programs → App content**. Each answer is a legal statement.

1. **Privacy policy** — the URL from Phase 6.
2. **App access** — the app needs device authentication and a VaultKey. Give
   reviewers step-by-step instructions (and a test VaultKey export plus passphrase if
   you want them to skip setup). Without this, review stalls.
3. **Ads** — no ads.
4. **Content rating** — complete the IARC questionnaire honestly.
5. **Target audience** — 18+ is the safe choice for a credential manager.
6. **Data safety**:
   - No data collected by the developer: no analytics, crash reporting or backend.
   - Data **is shared** with third parties (Nostr relays, litesync endpoints) to provide
     app functionality, at the user's direction. Declare the encrypted key material
     and the key label.
   - State that key material stays encrypted, and that the label is readable.
   - Data in transit is encrypted (TLS).
   - Camera: not collected (frames are processed on the device only).
   - Deletion: answer according to the Phase 1 decision. Relay copies cannot be
     guaranteed deleted, so do not claim they can.
7. **Financial features, health, news, government, COVID-19** — answer no.
8. **Export compliance** — the app uses encryption (TLS, BouncyCastle, secp256k1,
   NIP-44). Declare it; most consumer apps qualify for the mass-market exemption, but
   have someone qualified confirm it.

## Phase 8 — Console account and test tracks

1. **Developer account.** One-time US$25 fee, identity verification, 2-step
   verification. Complete developer verification as Play Console prompts.
2. **Internal testing** (up to 100 testers, no full review). Upload the first `.aab`
   here with `mapping.txt` (or let Play read it from the bundle) and release notes.
   Use it for the Phase 4 device matrix and keep it as the dogfood track.
3. **Closed testing.** For a personal account this is where the 12 testers × 14 days
   rule is met. Recruit testers who will complete a full backup and restore on a real
   network, not just open the app.
4. **Apply for production access** (personal accounts). Answer the questions
   specifically: what was tested, what broke, what was fixed. Review takes about a week.

Keep **Managed publishing** off until you are ready to go live.

## Phase 9 — Production rollout

1. Promote the verified build to **Production**, or upload a new bundle with a higher
   `versionCode`.
2. Use a **staged rollout** for the first public release, for example
   5% → 20% → 50% → 100%.
3. At each stage check **Android vitals** (crash rate, ANR rate), reviews and support
   email daily. Vitals is the only crash signal the app has.
4. Rollback is not possible for installed users. You can **halt** the rollout, then
   ship a fix with a higher `versionCode`. Write down who can do each and how quickly.
5. Write honest release notes, including known limitations.

## Phase 10 — Every later release

- [ ] Bump `versionCode`; set `versionName` per the versioning policy.
- [ ] Run unit tests, `lintRelease`, and `bundleRelease` locally or via `release.yml`.
- [ ] Confirm 16 KB alignment still passes after dependency bumps.
- [ ] Repeat the device smoke test on the minified build.
- [ ] Upload `mapping.txt` with the bundle.
- [ ] Review Dependabot, Gitleaks and Semgrep results since the last release.
- [ ] Update Data safety and the privacy policy if any new endpoint, permission or
      SDK was added.
- [ ] Update `docs/technical/product-status.md` and affected feature docs.
- [ ] Staged rollout, then watch vitals.

## Readiness checklist

What stands between this repository and a first Play upload:

| Item | Status |
| --- | --- |
| Release signing wired from environment variables | Done |
| CI builds, signs and verifies the release bundle (`release.yml`) | Done |
| Backup and device-transfer exclusions declared | Done; device verification across OEMs still needed |
| Real upload key generated, custodied, and loaded into the `release` environment | Open |
| Key custody, rotation and compromise-response note | Open |
| App-signing certificate decision for KeyHive sibling apps | Open |
| Minified release build tested on devices (Phase 4) | Open |
| Key deletion / reset decision for Data safety | Open (no delete or reset action exists) |
| Independent security assessment (release gate 1) | Open |
| Live interoperability test on two devices (release gate 2) | Open |
| Store assets, privacy policy and support contact | Open |
