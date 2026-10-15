# Implementation status

This is a repository-oriented status view, not a production-readiness certification. **Present in code** does not imply completed security, interoperability, UX, or operational validation.

| Area | Status | Repository evidence | Notes |
| --- | --- | --- | --- |
| Device authentication and foreground lock | Present in code | `ui/auth/`, `MainActivity.kt` | Strong biometric or device credential is mandatory; a memory-only 30-second background grace period applies, prompt errors are classified, pre-API-30 capability is handled via the keyguard, and the entire activity is screenshot protected. This is an interactive UI gate rather than authentication-bound storage, so background backup and KeyHive access remain unattended. |
| Compose navigation and main screens | Present in code | `ui/`, `VaultNavGraph.kt` | Onboarding, pre-setup source list/add, account list/create/detail, and dedicated Vault Key details are implemented. |
| Vault Key-account key intake | Present in code | `ui/onboarding/`, `domain/SetupService.kt` | Paste-oriented; retains validated Vault Key and supports retry when source quorum fails. |
| In-app VaultKey generation wizard | Implemented | `ui/onboarding/createvaultkey/` | Consent, a length-and-guessability passphrase policy (`VaultKeyPassphrasePolicy`, zxcvbn-backed `PassphraseStrengthChecker`), passphrase creation and verification, a non-dismissible creation screen with stage and per-source sync progress, generated-key persistence and remote backup attempts, local **Create Backup** export, pasted-key **Confirm Backup** verification with an escalating wrong-paste lockout (1/2/3 minutes, then back to export), grouped BIP-39-index transcription, sequential re-entry verification, and setup completion are implemented. |
| OpenSSH Ed25519 parsing/passphrase validation | Present in code | `crypto/SshKeyCodec.kt`, tests | Requires production crypto/dependency review. |
| Deterministic recovery identity | Present in code | `crypto/Seedify.kt`, `NostrKeyDerivation.kt` | Golden/parity tests exist. |
| Secure local storage | Present in code | `data/secure/`, `data/kv/` | Device, migration, and backup-policy review remains required. |
| Associated-account generation/listing | Present in code | `domain/AccountService.kt`, `ui/accounts/` | Local-first persistence; labels currently use creation IDs. |
| Private-key reveal/copy/local backup | Partial | `ui/accounts/AccountDetailScreen.kt`, `ui/accounts/LocalAccountBackupScreen.kt` | Confirmation and screenshot protection exist. Mandatory local backup requires a text export of the encrypted OpenSSH private key followed by user confirmation that it was saved. The in-app target picker prefers installed supported note apps and bypasses the Android Sharesheet so no Copy action is offered; independent verification of delivery remains unavailable. |
| Independent Nostr source adapter | Present in code | `domain/BackupSourceAdapters.kt`, `network/nostr/` | Includes transient NIP-42 handling; both default relays still require opt-in operational interoperability validation. |
| Independent Brave-compatible source adapter | Present in code | `domain/BackupSourceAdapters.kt`, `domain/BraveSyncBackupService.kt` | All three endpoints require end-to-end infrastructure validation. |
| Source repository/default policy | Present in code | `domain/BackupSources.kt`, `BackupSourceRepository.kt`, `ui/sources/` | Five immutable defaults and custom-source persistence. The UI currently adds at most three pre-setup Nostr relays; broader repository operations and custom Brave support are not exposed. |
| Two-phase consolidation and retry | Present in code | `domain/AccountConsolidationEngine.kt`, `background/BackupWorkers.kt` | Sequential restore-before-backup and hourly scheduling; lifecycle/failure validation should continue. |
| Separate Vault Key consolidation | Present in code | `domain/AccountConsolidationEngine.kt`, `background/BackupWorkers.kt` | Vault Key records use the Brave relay identity and have their own hourly worker, freshness window, and manual action, independent of account consolidation. |
| Per-source status UI | Partial | `ui/accounts/AccountDetailScreen.kt`, `ui/Vault Key/` | Live rows exist; final accessible copy, timestamps, and remediation need product validation. |
| Automatic associated-account recovery | Present in code | `domain/AccountConsolidationEngine.kt`, source adapters | Trusted-source candidates restore automatically; real-service end-to-end validation remains. |
| Account deletion and vault reset | Not available | — | Neither action is currently provided. |
| Export/share workflow | Partial | `ui/accounts/LocalAccountBackupScreen.kt` | An in-app picker lists compatible text targets, prefers installed supported note apps, and launches the selected activity explicitly. This preserves the encrypted OpenSSH private key as text without showing the Android Sharesheet's Copy action. User confirmation is required after export, but cannot prove that a target saved the key. |

See the [stakeholder scope and roadmap](../stakeholder/scope-and-roadmap.md) for client-review context.
