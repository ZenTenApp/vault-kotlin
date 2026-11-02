# Device authentication

## User outcome

Interactive vault screens are only reachable by someone who can unlock the Android device. This is an app-access privacy lock, not a cryptographic gate over stored data.

## Current behavior

1. A cold start checks whether Android has an accepted secure lock configured: a strong biometric or a device PIN, pattern, or password.
2. When available, the app presents Android's system authentication prompt.
3. After successful device authentication, if a VaultKey is stored, the app shows a **VaultKey passphrase** screen. The passphrase is verified by decrypting the stored VaultKey and is kept only in memory (`VaultKeySession`) for the session; a fresh install with no stored VaultKey skips straight to onboarding, which collects the passphrase itself. The input is masked by default and a trailing eye toggle reveals it so the user can check for typos before submitting; the reveal state is UI-only and resets to masked when the screen is shown again. The internet/time gate and normal navigation follow only after the passphrase gate is passed.
4. When the app leaves the foreground, the device-unlocked session remains valid for 30 seconds. Returning at or after that boundary requires authentication again and drops the in-memory passphrase, so the passphrase screen reappears. Process death always clears the passphrase and relocks the app.
5. If no accepted device lock is configured, a blocking screen directs the user to Android security enrollment and offers a recheck. There is no bypass.
6. The whole activity uses Android's secure-window flag to prevent screenshots, screen recording, and sensitive Recents previews where the platform supports it.

## Scope and threat model

This feature protects the **interactive application surface**.

### Protects against

- Someone casually opening the app on an unlocked, unattended device.
- Interactive access after the app has been backgrounded beyond the 30-second grace period.
- Screenshots, screen recording, and sensitive Recents previews where Android honors the secure-window flag.

### Does not protect against

- Root, forensic extraction, or a compromised OS/process running under the app's UID.
- Background backup work while the user is absent: the passphrase is never persisted, so consolidation can only run while an in-memory session is unlocked.
- The signature-protected KeyHive `AssociatedAccountsProvider`, which must keep serving authorized sibling apps unattended.
- Cryptographic use of the existing storage key without a recent user prompt.
- A compromised trusted sibling application.

The local Keystore-backed AES-GCM store (`KeystoreCryptoService` behind `SecureStorageService`) is intentionally **not** bound to user authentication. Requiring recent user authentication on that shared key would break unattended background backup and KeyHive access, which are approved requirements. Authentication-bound storage for interactive private-key disclosure remains a separate, tracked design question.

## Failure and security expectations

- Cancellation and retryable errors stay on a locked screen; there is no path to navigation.
- Errors that mean the device cannot satisfy the policy (no device credential, no enrolled biometric, no suitable hardware) route to the mandatory enrollment screen.
- Unlock state and the monotonic grace-period timestamp exist only in memory.
- Removing the device lock causes the next foreground check to block the app.
- Android owns all PIN, pattern, password, and biometric collection; Vault never receives or stores those credentials.

## Android version compatibility

The combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator set is unsupported by `canAuthenticate` before Android 11 (API 30) and unsupported when building prompt options on Android 9–10 (API 28–29). The implementation handles this by:

- checking device-lock capability with the keyguard's "device is secure" signal on API < 30, and
- configuring the prompt with the device-credential fallback option on API < 30 while using the combined authenticator set on API 30+.

Covered configurations for manual verification: PIN/password only, biometric plus credential, no lock (enrollment), and biometric lockout.

## Current limitations

- This is an interactive UI gate. Stored vault data uses a Keystore key that is not bound to user authentication.
- Background consolidation fails closed when the in-memory passphrase session is locked; it is re-enqueued on the next unlocked launch.
- The KeyHive provider's IPC contract is unchanged while the UI is locked.

## Primary implementation locations

- Coordinator, policy, and error classification: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockCoordinator.kt`
- Blocking UI: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockScreen.kt`
- Passphrase gate state and screen: `app/src/main/java/app/zenten/vault/ui/auth/VaultUnlockGateViewModel.kt`, `app/src/main/java/app/zenten/vault/ui/auth/VaultPassphraseScreen.kt`
- In-memory passphrase holder: `app/src/main/java/app/zenten/vault/domain/VaultKeySession.kt`
- Activity-level gate and secure window: `app/src/main/java/app/zenten/vault/MainActivity.kt`
