# Build and test

Run commands from the repository root.

```bash
# Compile a debug APK
./gradlew :app:assembleDebug

# Run host-side JUnit tests
./gradlew :app:testDebugUnitTest

# Run Android instrumented tests on a connected device/emulator
./gradlew :app:connectedDebugAndroidTest
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release build status

```bash
# Compile and minify the release APK (signed when VAULT_RELEASE_* are set)
./gradlew :app:assembleRelease

# Run release lint (includes R8/resource-shrinker checks)
./gradlew :app:lintRelease
```

The release variant is minified by R8 and has resource shrinking enabled
(`app/build.gradle.kts`). It compiles, minifies, and passes `lintRelease`, and the
artifact is produced at:

```text
app/build/outputs/apk/release/app-release.apk        # signing configured
app/build/outputs/apk/release/app-release-unsigned.apk  # no signing material
```

**This artifact is not shippable yet** because:

- **The minified build has not been smoke-tested on a device or emulator.** R8
  keep rules for interfaces and libraries reached only by reflection (Quartz
  serialization, Hilt, JNI bindings) are verified by compilation, not by runtime
  behavior. Any release-track claim of correctness rests on the debug build until
  an on-device pass of the minified APK is recorded.
- **The wired keystore is a development key.** `vault.jks` is self-signed `CN=vault`
  and lives in the working tree, and it is stored in legacy JKS format whose password
  protection is a single un-iterated SHA-1 pass. It is fine for sideload and
  smoke-test builds; it must not become the Play upload identity. See
  [Publishing to Google Play](play-store-release.md#21-generate-the-upload-keystore-once-ever).
- **Signing material is local only.** The signing configuration reads credentials
  from `VAULT_RELEASE_*` environment variables (see below); CI secrets, documented key
  custody/rotation, and an incident plan for a compromised signing key are still
  needed before any Play Store submission.

The R8 keep/dontwarn rules that make the current release build succeed are in
`app/proguard-rules.pro`; the comments there explain why each suppressed
reference is optional.

## Release signing

`app/build.gradle.kts` reads release signing credentials from four environment
variables. Nothing secret is read from, or written to, the repository:

| Variable | Meaning |
| --- | --- |
| `VAULT_RELEASE_KEYSTORE` | Path to the keystore file (absolute, or relative to the repository root) |
| `VAULT_RELEASE_STORE_PASSWORD` | Keystore password |
| `VAULT_RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `VAULT_RELEASE_KEY_PASSWORD` | Key password |
| `VAULT_RELEASE_REQUIRE_SIGNING` | Optional; `true` fails the build when credentials are absent |

Behaviour by state:

- **All four set** — the `release` signing config is created and the variant is signed.
- **None set** — the release variant configures and builds, but produces
  `app-release-unsigned.apk`. A contributor without signing rights is not blocked.
- **Some set** — the build fails with a `GradleException` naming the missing
  variables. A partial set is always a mistake (a renamed CI secret, or three of
  four exported), and silently building unsigned would hide it.
- **`VAULT_RELEASE_REQUIRE_SIGNING=true` with none set** — the build fails. This is
  the CI setting: a release pipeline must never emit an unsigned artifact.

**A successful `assembleRelease` therefore does not prove the artifact is signed.**
Verify explicitly before distributing, and make CI assert the signature rather than
infer it from the build result:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

A signed APK is produced at `app/build/outputs/apk/release/app-release.apk` and
carries the v2 signature scheme only.

Local builds export the variables in the shell that runs Gradle. Do not commit them,
and do not write them into `gradle.properties` (tracked) or `local.properties` (not a
secrets store). CI supplies them from repository secrets — see
[Publishing to Google Play](play-store-release.md#phase-5--make-ci-produce-and-protect-the-release-artifact).

## Versioning shared APKs

The Android version is configured in `app/build.gradle.kts`:

- `versionName` is the team-visible SemVer version. Use development pre-releases for shared in-progress APKs, such as `0.0.1-dev.1`, and reserve the corresponding plain version (`0.0.1`) for an internally approved baseline.
- `versionCode` is Android's install/upgrade integer. Increment it for every APK distributed to the team; never reuse or decrease it.
- For additional builds in the same milestone, increment the pre-release number (`0.0.1-dev.1` → `0.0.1-dev.2`). Use patch versions for compatible fixes (`0.0.2`), minor versions for meaningful feature milestones (`0.1.0`), and `1.0.0` for the first stable/public release.

## Test coverage areas

The current test suite includes device-lock capability and foreground grace-period policy, SSH key-codec behavior, deterministic crypto parity/golden vectors, Nostr event encoding, Brave Sync encoding/crypto components, source/status persistence, fixed-source policy, Vault Key quorum, sequential two-phase consolidation, local-first account behavior, Create VaultKey passphrase verification and key generation, and navigation destinations. Real default-relay/server verification is an integration concern and should remain opt-in rather than making host unit tests depend on external services.

Host-side tests run on JDK 21 because the Quartz dependency ships JVM 21 bytecode. The Android app itself compiles with Java/Kotlin target 17; Gradle is configured to provision the host-test toolchain when necessary.

## Continuous integration

The GitHub Actions workflow at `.github/workflows/ci.yml` runs on pull requests targeting `main` and on pushes to `main`. It uses a GitHub-hosted Ubuntu runner with JDK 21 (the app still compiles against Java/Kotlin target 17) and Android SDK platform 36 with build-tools 36.0.0, then runs:

1. `./gradlew :app:testDebugUnitTest`
2. `./gradlew :app:assembleDebug`
3. `./gradlew :app:lintDebug`

Android lint errors fail the build, so a change that introduces a new lint error cannot pass CI. Instrumented tests (`:app:connectedDebugAndroidTest`) are not run in CI because they require an emulator or connected device.

**CI does not build the release variant in `ci.yml`.** `:app:assembleRelease` and
`:app:lintRelease` are not workflow steps there, so an R8, shrinking, or proguard
rule regression passes `ci.yml` even though it breaks the release artifact. A
dedicated `.github/workflows/release.yml` covers this on a `v*` tag, but it requires
signing secrets and has not been executed yet. Run both locally before a change that
touches dependencies, crypto/JNI code, reflection, or `app/proguard-rules.pro`.

Branch protection for `main` (required pull-request review, required status checks, and disabled force pushes) is not enabled yet. Until it is, the workflow provides signal but does not block direct pushes to `main`.

## Dependency and security scanning

GitHub's built-in code scanning (GitHub Code Security / CodeQL) and secret scanning (GitHub Secret Protection) are paid products and are not available for this private repository on the GitHub Free organization plan. The repository therefore uses free substitutes and records the trade-offs here.

- **Dependabot** is enabled in repository settings for vulnerability alerts and security updates. In addition, `.github/dependabot.yml` opens weekly version-update pull requests: routine Gradle minor/patch bumps are grouped into one PR, while crypto/native/protocol dependencies (BouncyCastle, secp256k1-kmp, Quartz) are deliberately left ungrouped for individual review.
- **Gitleaks** (MIT-licensed) scans the full git history for committed secrets. It runs as the `secret-scan` job in `.github/workflows/security.yml` and fails the run on any finding.
- **Semgrep** (OSS) scans Kotlin/Android sources for high-severity defects. It runs as the `code-scan` job and fails the run only on `ERROR`-severity findings; the SARIF report is uploaded as an artifact.

Things the free substitutes do not provide:

- Findings appear in the Actions log and the `semgrep-sarif` artifact, not the repository **Security** tab. There is no unified security dashboard, alert dismissal, or push protection.
- Semgrep rules are fetched from the free Semgrep registry at run time, so rule content can change between runs.
- `--severity ERROR` is the merge gate. Lower-severity advisories (for example, mutable Action-tag warnings for `.github/workflows/ci.yml`) are visible in local runs but do not fail CI.
- Full-history secret scanning means a flagged value must be handled in configuration or history; removing it in a later commit does not clear the alert.
- These scans run on GitHub-hosted runners and consume the organization's free Actions minutes.

Non-secret values are allowlisted narrowly so genuine findings still fail:

- `.gitleaks.toml` allowlists public Nostr `npub` keys, Brave's public Sync service key, Kotlin source that wraps a key-encoding call in OpenSSH PEM markers, and the golden crypto fixtures plus their generator.
- `.semgrepignore` excludes generated golden fixtures and the test-vector generator.

Reproduce either scan locally with the same image, configuration, and gate used in CI:

```bash
# Secret scan (full history)
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/repo" \
  ghcr.io/gitleaks/gitleaks:v8.30.1 detect --source=/repo --redact --verbose

# Code scan (fails on ERROR severity)
docker run --rm -v "$PWD:/src" -w /src semgrep/semgrep:1.180.0 \
  semgrep scan --config p/default --config p/kotlin --config p/security-audit \
  --metrics=off --severity ERROR --error
```

## Before opening a pull request

At minimum:

1. Run `./gradlew :app:testDebugUnitTest`.
2. Run `./gradlew :app:assembleDebug` if Android/UI/build configuration changed.
3. Run `./gradlew :app:lintDebug` if Android resources or Kotlin sources changed.
4. Run `./gradlew :app:assembleRelease :app:lintRelease` if dependencies,
   `app/proguard-rules.pro`, JNI/crypto code, or anything reached by reflection
   changed — CI does not cover the release variant.
5. Exercise affected sensitive screens manually on a device/emulator where feasible. Device-authentication changes should cover PIN-only, strong biometric plus credential, no-lock enrollment, cancellation, biometric lockout, and background-grace behavior, including pre-Android 11 (API 26–29) capability handling.
6. Confirm no secrets, generated APKs, or local configuration files are included in the change.
7. Run the Gitleaks and Semgrep commands under [Dependency and security scanning](#dependency-and-security-scanning) when the change touches crypto, network, storage, CI, or dependencies, or when adding test fixtures that contain key material.
