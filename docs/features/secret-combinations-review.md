# Secret Combinations review

## User outcome

A user with an already-configured vault can deliberately reveal the VaultKey's 24 BIP-39 recovery word positions, write them down again, and prove the written copy is accurate. This is the recovery path for a lost or destroyed offline copy of the Secret Combinations without re-importing the VaultKey.

## Current behavior

1. The user opens **Settings** and selects **Secret Combinations**.
2. The app asks the user to re-enter the passphrase that protects the VaultKey. No combination is derived or displayed before the passphrase matches. A wrong or blank passphrase shows an inline error and stays on the gate.
3. Passing the gate records a non-secret step marker and makes the flow irreversible. The app displays the 24 one-based English BIP-39 word-list indices of the VaultKey-derived Brave Sync mnemonic in eight numbered rows (1–8) of three, using the same three-row snapping wheel, warning card, acknowledgement checkbox, and **Continue** action as the create-flow **Save your Secret Combinations** step. Only the picker scrolls; the first and last rows can center without looping.
4. **Continue** advances to verification, where the user must re-enter all 24 positions in the same eight labeled rows. Input is strictly sequential: a correct segment advances to the next, and a diverging value turns to the error color and semi-bold weight (no border), an "Incorrect Secret Combination! Please try again." message appears below the picker, and the value must be corrected in place. Back (system Back or the in-flow Back action) returns to the display screen to review the combinations; values already entered are preserved when the user continues again.
5. After all segments match, the app shows a large outlined check for three seconds and returns to **Settings**.

Once the passphrase gate is passed, the flow cannot be cancelled: the display screen blocks system Back, Back from verification only moves to the display, and no action returns to normal navigation. The only exception is the unavailable state described below. The active step is persisted, so killing the app and reopening it resumes the unfinished display or verification step rather than returning to **Settings**; going Back from verification records the display step again so a restart during review resumes there.

## Failure and security expectations

- The combinations are derived from the stored VaultKey only after the user proves knowledge of its passphrase. The gate error is a fixed message that echoes no passphrase content.
- The flow is entered from **Settings** and is only reachable after setup, so the VaultKey exists in keystore-backed storage before the gate is offered.
- The displayed positions and the transcribed values are held only in the flow's graph-scoped ViewModel. They are never written to DataStore, placed in navigation arguments, logged, or included in the persisted resume marker, which records only which step is active.
- The flow cannot be abandoned once entered. This is deliberate: it prevents a user from glancing at the combinations and leaving with an unverified, possibly mistyped written copy.
- If the stored VaultKey cannot be decrypted, there is nothing to transcribe. In that case only, the screen offers an explicit **Back to Settings** action and clears the resume marker so the user is not trapped on an error.
- Verification is strict and self-paced. A wrong segment is corrected in place rather than skipped, and Back returns to the displayed combinations for review without leaving the flow.
- The flow is protected from screenshots wherever the app's activity-level screenshot protection applies.

## Current limitations

- The flow does not time out or re-lock while it is open; the persisted step resumes until verification completes.
- There is no way to copy the combinations to the clipboard or export them; they are shown visually for offline transcription only.
- The displayed values are the same Brave Sync word positions used during creation, so a user who already wrote down all eight combinations correctly gains no new information.

## Primary implementation locations

- UI flow and screens: `app/src/main/java/app/zenten/vault/ui/secretcombinations/`
- Shared combination pickers: `app/src/main/java/app/zenten/vault/ui/components/SecretCombinationsComponents.kt`
- Flow state, passphrase gate, and durable step transitions: `app/src/main/java/app/zenten/vault/ui/secretcombinations/SecretCombinationsViewModel.kt`
- Resume marker: `AppStorageService.secretCombinationsViewStep` in `app/src/main/java/app/zenten/vault/data/kv/AppStorageService.kt`
- Launch routing: `app/src/main/java/app/zenten/vault/ui/root/RootScreen.kt`
- Settings entry point: `app/src/main/java/app/zenten/vault/ui/settings/SettingsScreen.kt`
