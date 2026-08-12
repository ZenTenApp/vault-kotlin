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

## Test coverage areas

The current test suite includes SSH key-codec behavior, deterministic crypto parity/golden vectors, Nostr event encoding, Brave Sync encoding/crypto components, source/status persistence, fixed-source policy, master quorum, sequential two-phase consolidation, local-first account behavior, and navigation destinations. Real default-relay/server verification is an integration concern and should remain opt-in rather than making host unit tests depend on external services.

Host-side tests run on JDK 21 because the Quartz dependency ships JVM 21 bytecode. The Android app itself compiles with Java/Kotlin target 17; Gradle is configured to provision the host-test toolchain when necessary.

## Before opening a pull request

At minimum:

1. Run `./gradlew :app:testDebugUnitTest`.
2. Run `./gradlew :app:assembleDebug` if Android/UI/build configuration changed.
3. Exercise affected sensitive screens manually on a device/emulator where feasible.
4. Confirm no secrets, generated APKs, or local configuration files are included in the change.
