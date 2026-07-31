# ZenTen Vault

ZenTen Vault is an Android proof of concept for managing SSH credentials with user-held recovery material and redundant encrypted backups. A user begins with the private key and passphrase for an existing master account, then creates associated accounts that are automatically restored when discovered on another device.

> **Proof of concept — not production-ready.** Key-management software requires independent security review, release hardening, and operational controls before it should be trusted with real credentials.

## Documentation

- [Documentation index](docs/README.md)
- [Feature documentation](docs/features/README.md) — behavior, safeguards, limitations, and source locations for every current app feature.
- [Stakeholder product guide](docs/stakeholder/README.md) — feature flows, recovery expectations, and scope for product, design, and client review.
- [Technical documentation](docs/technical/README.md) — developer setup and implementation references.

## Requirements

- JDK 17 for Android builds
- JDK 21 available to Gradle for host-side unit tests (provisioned through the Foojay toolchain resolver when needed)
- Android SDK platform 36 and build-tools 36.0.0
- `local.properties` containing `sdk.dir=<your Android SDK path>`; do not commit this file

## Build and test

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current implementation scope

The repository contains Android UI, secure local-storage, SSH-key handling, encrypted Nostr relay backup, Brave Sync integration, backup reconciliation, and automated tests. See the [implementation status](docs/technical/product-status.md) for a feature-level view and its limitations.
