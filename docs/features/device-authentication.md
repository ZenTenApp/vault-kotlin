# Device authentication

## User outcome

Interactive vault screens are only reachable by someone who can unlock the Android device. This is an app-access privacy lock, not a cryptographic gate over stored data.

## Current behavior

1. A cold start checks whether Android has an accepted secure lock configured: a strong biometric or a device PIN, pattern, or password.
2. When available, the app presents Android's system authentication prompt.
3. After successful device authentication, if a VaultKey is stored, the app shows a **VaultKey passphrase** screen. The passphrase is verified by decrypting the stored VaultKey and is kept only in memory (`VaultKeySession`) for the session; a fresh install with no stored VaultKey skips straight to onboarding, which collects the passphrase itself. The input is masked by default and a trailing eye toggle reveals it so the user can check for typos before submitting; the reveal state is UI-only and resets to masked when the screen is shown again. The internet/time gate and normal navigation follow only after the passphrase gate is passed.

   There is a third outcome besides "stored" and "nothing stored": stored records that this device cannot open. That happens when the wrapping Keystore key did not arrive (a device transfer that copied the ciphertext but not the hardware-bound key) or when the stored bytes are damaged. In that state the gate shows **Vault needs recovery** instead of the passphrase screen or onboarding, because no passphrase can open those records and reporting onboarding would present an empty vault. The screen's action clears the launch-routing flags and sets the unreadable ciphertext aside under a quarantine prefix so **Get started** → **Recover VaultKey** becomes reachable; when the device could not read its Keystore at all, it offers **Check again** instead and nothing is set aside. See [Device migration and backup](device-migration-and-backup.md).
4. The 30-second boundary is evaluated when the app returns to the foreground; no timer locks the session while it is backgrounded, and a continuously foregrounded app is not locked for inactivity. A return within 30 seconds retains the session. Returning at or after 30 seconds requires authentication again and drops the foreground passphrase session, so the passphrase screen reappears. A consolidation job that started while the session was unlocked holds an independent passphrase copy, continues to completion after the foreground session locks, and zeroes that copy in a `finally` block; a job that has not started when the session locks fails closed and remains pending for the next unlock. Process death always clears the passphrase and relocks the app.
5. A configuration change that recreates the Activity (rotation, theme or dark-mode switch, locale or font-size change) does **not** count as a background trip. The unlock session and its 30-second grace window are retained in an Activity-scoped `AppUnlockViewModel`, so the user is not re-prompted and the foreground passphrase session is not dropped. The session remains memory-only: process death discards the ViewModel and therefore relocks the app. Adding `android:configChanges` is deliberately not used, because it would only suppress the recreation instead of retaining the session and would leave process-death handling unchanged.
6. If no accepted device lock is configured, a blocking screen directs the user to Android security enrollment and offers a recheck. There is no bypass.
7. The whole activity uses Android's secure-window flag to prevent screenshots, screen recording, and sensitive Recents previews where the platform supports it.

## Scope and threat model

This feature protects the **interactive application surface**.

### Protects against

- Someone casually opening the app on an unlocked, unattended device.
- Interactive access after the app has been backgrounded beyond the 30-second grace period.
- Screenshots, screen recording, and sensitive Recents previews where Android honors the secure-window flag.

### Does not protect against

- Root, forensic extraction, or a compromised OS/process running under the app's UID.
- Background consolidation while the user is absent: the passphrase is never persisted, so it can only run while an in-memory session is unlocked.
- The signature-protected KeyHive `AssociatedAccountsProvider`, which must keep serving authorized sibling apps unattended.
- Cryptographic use of the existing storage key without a recent user prompt.
- A compromised trusted sibling application.

The local Keystore-backed AES-GCM store (`KeystoreCryptoService` behind `SecureStorageService`) is intentionally **not** bound to user authentication. Requiring recent user authentication on that shared key would break unattended background backup and KeyHive access, which are approved requirements. Authentication-bound storage for interactive private-key disclosure remains a separate, tracked design question.

## Failure and security expectations

- Cancellation and retryable errors stay on a locked screen; there is no path to navigation.
- Errors that mean the device cannot satisfy the policy (no device credential, no enrolled biometric, no suitable hardware) route to the mandatory enrollment screen.
- Unlock state and the monotonic grace-period timestamp exist only in memory.
- An Activity recreation for a configuration change retains the unlocked session; it is not treated as a background trip because the user never left the app.
- Removing the device lock causes the next foreground check to block the app.
- Android owns all PIN, pattern, password, and biometric collection; Vault never receives or stores those credentials.

## Android version compatibility

AndroidX Biometric 1.1.0 rejects the combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator set on Android 9–10 (API 28–29), both in `canAuthenticate` and in `PromptInfo.Builder.build()`. It accepts the combination on API 26–27 and API 30+. The implementation selects the prompt shape by API level so that no path leaves the authenticator set unset — an unset set is what makes the library default a non-crypto prompt to `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`:

- **API 26–27:** one prompt requests `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`. Biometric hardware exposed before API 28 is fingerprint-only, which is Class 3.
- **API 28–29:** the prompt requests `BIOMETRIC_STRONG` only. The prompt's fallback button, and any device with no strong biometric enrolled, opens the keyguard's device-credential confirmation instead, so PIN, pattern, or password remains available without admitting a Class 2 biometric.
- **API 30+:** one prompt requests `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`.

Device-lock capability is the combined `canAuthenticate` query on API 26–27 and 30+, and the keyguard's `isDeviceSecure` signal on API 28–29 (a secure keyguard proves the credential exists; strong-biometric availability is checked separately).

Covered configurations for manual verification: PIN/password only, strong biometric plus credential, a weak-biometric-only device with a credential (must require the credential, not the weak biometric), no lock (enrollment), and biometric lockout, on both API 28–29 and API 30+.

## Current limitations

- This is an interactive UI gate. Stored vault data uses a Keystore key that is not bound to user authentication.
- Background consolidation fails closed when the in-memory passphrase session is locked; it is re-enqueued on the next unlocked launch. A job that already started keeps its own passphrase copy, finishes after the foreground session locks, and zeroes the copy on completion.
- The KeyHive provider's IPC contract is unchanged while the UI is locked.

## Primary implementation locations

- Coordinator, policy, and error classification: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockCoordinator.kt` (the coordinator is Activity-bound and is rebound by `AppUnlockViewModel` to each recreated Activity)
- Retained unlock session and Activity binding: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockViewModel.kt` (an Activity-scoped `ViewModel`; it is not persisted and is created through its own factory because it has no injected dependencies)
- Blocking UI: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockScreen.kt`
- Passphrase gate state and screen: `app/src/main/java/app/zenten/vault/ui/auth/VaultUnlockGateViewModel.kt`, `app/src/main/java/app/zenten/vault/ui/auth/VaultPassphraseScreen.kt`
- In-memory passphrase holder: `app/src/main/java/app/zenten/vault/domain/VaultKeySession.kt`
- Activity-level gate and secure window: `app/src/main/java/app/zenten/vault/MainActivity.kt`
