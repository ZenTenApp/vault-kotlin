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

Branch protection for `main` (required pull-request review, required status checks, and disabled force pushes) is not enabled yet. Until it is, the workflow provides signal but does not block direct pushes to `main`.

## Dependency and security scanning

GitHub's built-in code scanning (GitHub Code Security / CodeQL) and secret scanning (GitHub Secret Protection) are paid products and are not available for this private repository on the GitHub Free organization plan. The repository therefore uses free substitutes and records the trade-offs here.

- **Dependabot** is enabled in repository settings for vulnerability alerts and security updates. In addition, `.github/dependabot.yml` opens weekly version-update pull requests: routine Gradle minor/patch bumps are grouped into one PR, while crypto/native/protocol dependencies (BouncyCastle, secp256k1-kmp, Quartz, androidx security-crypto) are deliberately left ungrouped for individual review.
- **Gitleaks** (MIT-licensed) scans the full git history for committed secrets. It runs as the `secret-scan` job in `.github/workflows/security.yml` and fails the run on any finding.
- **Semgrep** (OSS) scans Kotlin/Android sources for high-severity defects. It runs as the `code-scan` job and fails the run only on `ERROR`-severity findings; the SARIF report is uploaded as an artifact.

Things the free substitutes do not provide:

- Findings appear in the Actions log and the `semgrep-sarif` artifact, not the repository **Security** tab. There is no unified security dashboard, alert dismissal, or push protection.
- Semgrep rules are fetched from the free Semgrep registry at run time, so rule content can change between runs.
- `--severity ERROR` is the merge gate. Lower-severity advisories (for example, mutable Action-tag warnings for `.github/workflows/ci.yml`) are visible in local runs but do not fail CI.
- Full-history secret scanning means a flagged value must be handled in configuration or history; removing it in a later commit does not clear the alert.
- These scans run on GitHub-hosted runners and consume the organization's free Actions minutes.

Non-secret values are allowlisted narrowly so genuine findings still fail:

- `.gitleaks.toml` allowlists public Nostr `npub` keys, Brave's public Sync service key, and the golden crypto fixtures plus their generator.
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
4. Exercise affected sensitive screens manually on a device/emulator where feasible. Device-authentication changes should cover PIN-only, strong biometric plus credential, no-lock enrollment, cancellation, biometric lockout, and background-grace behavior, including pre-Android 11 (API 26–29) capability handling.
5. Confirm no secrets, generated APKs, or local configuration files are included in the change.
6. Run the Gitleaks and Semgrep commands under [Dependency and security scanning](#dependency-and-security-scanning) when the change touches crypto, network, storage, CI, or dependencies, or when adding test fixtures that contain key material.
