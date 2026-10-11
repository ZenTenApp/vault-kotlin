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

## Before opening a pull request

At minimum:

1. Run `./gradlew :app:testDebugUnitTest`.
2. Run `./gradlew :app:assembleDebug` if Android/UI/build configuration changed.
3. Exercise affected sensitive screens manually on a device/emulator where feasible. Device-authentication changes should cover PIN-only, strong biometric plus credential, no-lock enrollment, cancellation, biometric lockout, and background-grace behavior, including pre-Android 11 (API 26–29) capability handling.
4. Confirm no secrets, generated APKs, or local configuration files are included in the change.
