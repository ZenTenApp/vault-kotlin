# Implementation status

This is a repository-oriented status view, not a production-readiness certification. **Present in code** does not imply completed security, interoperability, UX, or operational validation.

| Area | Status | Repository evidence | Notes |
| --- | --- | --- | --- |
| Compose navigation and main screens | Present in code | `ui/`, `VaultNavGraph.kt` | Onboarding, pre-setup source list/add, account list/create/detail, and dedicated Master Account details are implemented. |
| Master-account key intake | Present in code | `ui/onboarding/`, `domain/SetupService.kt` | Paste-oriented; retains validated master and supports retry when source quorum fails. |
| OpenSSH Ed25519 parsing/passphrase validation | Present in code | `crypto/SshKeyCodec.kt`, tests | Requires production crypto/dependency review. |
| Deterministic recovery identity | Present in code | `crypto/Seedify.kt`, `NostrKeyDerivation.kt` | Golden/parity tests exist. |
| Secure local storage | Present in code | `data/secure/`, `data/kv/` | Device, migration, and backup-policy review remains required. |
| Associated-account generation/listing | Present in code | `domain/AccountService.kt`, `ui/accounts/` | Local-first persistence; labels currently use creation IDs. |
| Private-key reveal/copy/local backup | Partial | `ui/accounts/AccountDetailScreen.kt`, `ui/accounts/LocalAccountBackupScreen.kt` | Confirmation and screenshot protection exist. Mandatory local backup requires a Sharesheet export of the encrypted OpenSSH private key followed by user confirmation that it was saved. Installed supported note apps are preferred, but system chooser order is not guaranteed; clipboard lifecycle and independent verification of delivery remain unavailable. |
| Independent Nostr source adapter | Present in code | `domain/BackupSourceAdapters.kt`, `network/nostr/` | Includes transient NIP-42 handling; both default relays still require opt-in operational interoperability validation. |
| Independent Brave-compatible source adapter | Present in code | `domain/BackupSourceAdapters.kt`, `domain/BraveSyncBackupService.kt` | All three endpoints require end-to-end infrastructure validation. |
| Source repository/default policy | Present in code | `domain/BackupSources.kt`, `BackupSourceRepository.kt`, `ui/sources/` | Five immutable defaults and custom-source persistence. The UI currently adds at most three pre-setup Nostr relays; broader repository operations and custom Brave support are not exposed. |
| Two-phase consolidation and retry | Present in code | `domain/AccountConsolidationEngine.kt`, `background/BackupWorkers.kt` | Sequential restore-before-backup and hourly scheduling; lifecycle/failure validation should continue. |
| Per-source status UI | Partial | `ui/accounts/AccountDetailScreen.kt`, `ui/master/` | Live rows exist; final accessible copy, timestamps, and remediation need product validation. |
| Automatic associated-account recovery | Present in code | `domain/AccountConsolidationEngine.kt`, source adapters | Trusted-source candidates restore automatically; real-service end-to-end validation remains. |
| Account deletion and vault reset | Not available | — | Neither action is currently provided. |
| Export/share workflow | Partial | `ui/accounts/LocalAccountBackupScreen.kt` | Android Sharesheet exports encrypted OpenSSH private-key text, preferring installed supported note apps while preserving all compatible targets. User confirmation is required after export, but cannot prove that a target saved the key. |

See the [stakeholder scope and roadmap](../stakeholder/scope-and-roadmap.md) for client-review context.
