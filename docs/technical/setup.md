# Development setup

## Prerequisites

- Android Studio with an Android SDK installation.
- Android SDK platform 36 and build-tools 36.0.0.
- JDK 17 for Android compilation.
- Network access the first time Gradle provisions its host-test JDK 21 toolchain through the Foojay resolver.

## Local SDK configuration

Create `local.properties` in the repository root:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Do not commit `local.properties` or real private-key material.

## Open and verify

1. Open the repository in Android Studio, or use the Gradle wrapper from a terminal.
2. Let Gradle synchronize dependencies.
3. Run `./gradlew :app:testDebugUnitTest`.
4. Run `./gradlew :app:assembleDebug`.
5. Install the resulting APK on an emulator/device only with non-sensitive test credentials.

## Secret-handling rules

- Never commit SSH private keys, passphrases, recovery phrases, Sync codes, access tokens, or production relay credentials.
- Use synthetic fixtures only; inspect test fixtures before adding them.
- Do not paste secret values into issues, pull requests, build logs, screenshots, or crash reports.
- Treat Android devices and emulators used for manual testing as sensitive while test key material is installed.
