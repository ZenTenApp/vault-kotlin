# Device authentication

## User outcome

Only a person who can unlock the Android device can enter ZenTen Vault's interactive UI.

## Current behavior

1. A cold start checks whether Android has a secure device credential configured.
2. When available, the app presents Android's system authentication prompt. A strong biometric or the device PIN, pattern, or password is accepted.
3. Successful authentication exposes the existing internet/time gate and then normal navigation.
4. When the app leaves the foreground, the unlocked session remains valid for 30 seconds. Returning at or after that boundary requires authentication again. Process death always locks the app.
5. If no accepted device lock is configured, a blocking screen directs the user to Android security enrollment and offers a recheck. There is no bypass.
6. The whole activity uses Android's secure-window flag to prevent screenshots, screen recording, and sensitive Recents previews where the platform supports it.

## Failure and security expectations

- Cancellation, prompt errors, and authentication failure never expose navigation.
- Unlock state and the monotonic grace-period timestamp exist only in memory.
- Removing the device lock causes the next foreground check to block the app.
- Android owns all PIN, pattern, password, and biometric collection; Vault never receives or stores those credentials.

## Current limitations

- This is an interactive UI gate. Stored vault data is not yet wrapped in an authentication-required Keystore key.
- The signature-protected KeyHive `AssociatedAccountsProvider` remains available to trusted sibling apps while the UI is locked. Device authentication does not alter that IPC contract.
- Background workers continue operating because they do not render interactive vault content.

## Primary implementation locations

- Coordinator and policy: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockCoordinator.kt`
- Blocking UI: `app/src/main/java/app/zenten/vault/ui/auth/AppUnlockScreen.kt`
- Activity-level gate and secure window: `app/src/main/java/app/zenten/vault/MainActivity.kt`
