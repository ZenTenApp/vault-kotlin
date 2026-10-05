# App flowcharts

These diagrams are a discussion aid for the currently implemented Android proof of concept. They describe observed application behavior, not a production-readiness commitment. See [User journeys](user-journeys.md) for user goals and limitations, and [Backup and recovery](backup-and-recovery.md) for the user recovery responsibilities.

## App entry and navigation

```mermaid
flowchart TD
    Launch["App launch"] --> Prerequisites{"Verified internet and device time within 2 min of HTTPS server time?"}
    Prerequisites -->|No| Blocked["Unavailable screen: explain errors/warnings and offer a recheck"]
    Blocked --> Prerequisites
    Prerequisites -->|Yes| Root["Root checks persisted state"]
    Root --> Setup{"Vault Key setup complete?"}
    Setup -->|No| GeneratedProgress{"Generated VaultKey already committed?"}
    GeneratedProgress -->|No| Welcome["Get started"]
    GeneratedProgress -->|Create Backup pending| CreateBackupIntro
    GeneratedProgress -->|Confirm Backup pending| CreateConfirmBackup
    GeneratedProgress -->|Secret Combinations pending| CreateSecrets
    GeneratedProgress -->|Congratulations pending| Congratulations
    Welcome -->|"Login with VaultKey"| VaultKeySetup["Set up Vault Key"]
    Welcome -->|"Create VaultKey"| CreateIntro["Getting Started: unrecoverable-loss warning + consent gate"]
    CreateIntro -->|"Back"| Welcome
    CreateIntro -->|"Confirm (consent checked)"| CreatePassphrase["Create Passphrase: unmet rules disappear after 2-second success feedback"]
    CreatePassphrase -->|"Back"| CreateIntro
    CreatePassphrase -->|"Confirm (8+ chars; upper, lower, number, special)"| CreateConfirm["Re-enter Passphrase"]
    CreateConfirm -->|"Back"| CreatePassphrase
    CreateConfirm -->|"Verify"| CreateMatch{"Re-entry matches the chosen passphrase?"}
    CreateMatch -->|No| CreateMismatch["Inline error; nothing created"]
    CreateMismatch --> CreateConfirm
    CreateMatch -->|Yes| CreateProgressScreen["Creating VaultKey: no Back; warn not to close app"]
    CreateProgressScreen --> CreateGenerate["Show creation stage; generate encrypted Ed25519 VaultKey"]
    CreateGenerate -->|Failure| CreateGenerateFailed["Return to confirmation with generic retry message; nothing created"]
    CreateGenerateFailed --> CreateConfirm
    CreateGenerate -->|Success| CreateStore["Show secure-save stage; store generated Vault Key locally"]
    CreateStore --> CreateIdentity["Derive Nostr identity from the 24-word Brave sub-account mnemonic"]
    CreateIdentity --> CreateRemote["Use that identity for initial relay backup; use 24 words + daily 25th word for Brave Sync"]
    CreateRemote --> CreateProgress["Persist next unfinished wizard step"]
    CreateProgress --> CreateBackupIntro["Create Backup: explain the first Vault Backup"]
    CreateBackupIntro -->|"Back"| CreateBackupIntro
    CreateBackupIntro -->|"Export VaultKey"| CreateExport["Choose an installed text target and export encrypted VaultKey"]
    CreateExport --> CreateConfirmBackup["Confirm Backup: paste VaultKey"]
    CreateConfirmBackup -->|"Back"| CreateBackupIntro
    CreateConfirmBackup -->|"Paste VaultKey"| CreateClipboard{"Clipboard read and cleared; supported key format?"}
    CreateClipboard -->|No| CreateConfirmBackup
    CreateClipboard -->|Yes| CreateBackupMatch{"Pasted key matches generated VaultKey?"}
    CreateBackupMatch -->|No| CreateConfirmBackup
    CreateBackupMatch -->|Yes| CreateSecrets["Secret Combinations: 24 BIP-39 indices in eight groups of three"]
    CreateSecrets -->|"Hide / show"| CreateSecrets
    CreateSecrets -->|"Acknowledge backup + Continue"| VerifySecrets["Verify Secret Combinations: sequential segment entry"]
    VerifySecrets -->|"Wrong segment"| VerifyError["Red border; remain on current segment"]
    VerifyError --> VerifySecrets
    VerifySecrets -->|"Correct segment"| VerifySecrets
    VerifySecrets -->|"Back"| CreateSecrets
    VerifySecrets -->|"All 24 correct"| Congratulations["Completion: large outlined check for 3 seconds"]
    Congratulations -->|"Automatic"| MarkComplete["Mark generated VaultKey setup complete"]
    MarkComplete --> Accounts
    Welcome -->|"Google / Apple / Microsoft"| ComingSoon["Coming soon message"]
    ComingSoon --> Welcome
    Setup -->|Yes| PendingExport{"Pending local key export?"}
    PendingExport -->|Yes| Export["Export account"]
    PendingExport -->|No| Accounts["Accounts"]
    Export --> Accounts
    Accounts --> Create["Create new SSH account"]
    Accounts --> Detail["Account details"]
    Accounts --> Settings["Settings"]
    Settings --> Vault Key["Vault Key"]
    Detail --> Accounts
    Vault Key --> Accounts
```

After passphrase verification, the dedicated **Creating VaultKey** screen blocks system Back and provides creation, secure-save, and per-source synchronization progress until it advances automatically. Vault Key backup and consolidation use the Nostr identity derived from the 24-word `brave` sub-account mnemonic for relay operations; Brave Sync uses those same 24 words plus its daily 25th word. General associated-account consolidation uses the Vault Key Nostr identity on relays and the Brave seed on Brave Sync. The two record classes remain separate, so account consolidation cannot create a duplicate Vault Key relay copy under the Vault Key identity. The app resumes the mandatory export screen after a restart when an account has been created but its local export step has not yet been confirmed. It likewise resumes the generated-VaultKey wizard at **Create Backup** after the key has been committed, at **Confirm Backup** after Android accepted the export, at **Save your Secret Combinations** after the pasted key matched, or at **Congratulations** after every combination was verified. Back navigation to key generation is blocked because generation is irreversible, while **Confirm Backup** can return to **Create Backup** to repeat the export. Back from combination verification returns to the saved-combinations screen while preserving entered verification inputs, so returning to verification resumes at the active segment. The completion screen blocks Back, displays only a centered outlined check, then automatically marks setup complete after three seconds and replaces the wizard with the account list.

## Vault Key-account setup

```mermaid
flowchart TD
    Start["Select Paste VaultKey"] --> Clipboard{"Clipboard text read and current entry cleared?"}
    Clipboard -->|No| PasteError["Show safe error and remain on paste step"]
    Clipboard -->|Yes| KeyFormat{"Supported encrypted OpenSSH-v1 Ed25519 container?"}
    KeyFormat -->|No| PasteError
    KeyFormat -->|Yes| Passphrase["Keep key off-screen and enter key passphrase"]
    Passphrase --> Validate{"Key and passphrase valid?"}
    Validate -->|No| Error["Show safe error and remain on setup"]
    Validate -->|Yes| Store["Store Vault Key securely"]
    Store --> Sources["Check enabled backup sources sequentially"]
    Sources --> Missing["Write Vault Key only to successfully checked sources where it is absent"]
    Missing --> Quorum{"Eligible Nostr and Brave backups confirmed?"}
    Quorum -->|Yes| Complete["Mark setup complete"]
    Complete --> Schedule["Schedule associated-account consolidation"]
    Schedule --> Accounts["Open My Accounts"]
    Quorum -->|No| Incomplete["Keep Vault Key stored and leave setup incomplete"]
    Incomplete --> Retry["Retry backup without re-importing"]
    Retry --> Sources
```

The setup screen never renders the supplied key and does not show secret material in errors. Vault clears the current Android clipboard entry before opening the passphrase step, but operating-system or third-party clipboard history can remain outside its control. A failed source check is treated as unknown, not as permission to write another backup.

## Associated-account creation and local export

```mermaid
flowchart TD
    Add["Select Create New Account"] --> EnterPassphrase["Enter Vault Key passphrase"]
    EnterPassphrase --> Verify{"Passphrase valid?"}
    Verify -->|No| Rejected["Show error and do not create account"]
    Verify -->|Yes| EnterName["Enter mandatory account name, 8-128 characters"]
    EnterName --> Check{"Name valid, Vault Key quorum complete, and fewer than 99 associated accounts?"}
    Check -->|No| Rejected
    Check -->|Yes| Generate["Generate encrypted Ed25519 OpenSSH account"]
    Generate --> Save["Save account locally and record pending export"]
    Save --> Queue["Queue targeted background consolidation"]
    Queue --> Export["Open required Export account screen"]
    Export --> Choose["Choose a trusted text-capable app from the in-app picker"]
    Choose --> Confirm{"User confirms the encrypted key was saved?"}
    Confirm -->|No| Export
    Confirm -->|Yes| Clear["Clear pending export"]
    Clear --> QueueProfile["Queue durable kind 0 profile and kind 10050 DM relay list"]
    QueueProfile --> Accounts["Open Accounts without waiting"]
    QueueProfile --> ProfileWorker["Background publish both events to all profile relays with retry"]
```

Launching the selected app is recorded, but Android cannot confirm that the receiving app retained the key. The in-app picker launches the chosen activity directly so the encrypted key remains a text export without exposing the Android Sharesheet's Copy action. Back navigation is blocked on the export screen until the user confirms the export. Confirmation removes the completed export screen from navigation history, so Back from **My Accounts** cannot reopen it. Profile publication runs as account-keyed unique WorkManager work, so relay latency or failure does not prevent navigation to **My Accounts**.

## Associated-account consolidation and automatic recovery

```mermaid
flowchart TD
    Trigger["Successful setup, account action, app startup, or hourly account work"] --> Phase1["Phase 1: build a remote inventory and restore missing local accounts"]
    Phase1 --> Index["For each enabled source, retrieve stored associated-account IDs"]
    Index --> IndexOK{"Could the app retrieve that source's stored ID list?"}
    IndexOK -->|No| Unknown["Mark the source unavailable or unknown and exclude it from this run's repair"]
    IndexOK -->|Yes| Restore{"Valid missing local account found on a trusted restore source?"}
    Restore -->|Yes| Recover["Decrypt, validate, and save the missing account locally"]
    Restore -->|No| NextSource["Continue collecting source inventories"]
    Recover --> NextSource
    Unknown --> NextSource
    NextSource --> AllIndexed{"All enabled sources checked?"}
    AllIndexed -->|No| Index
    AllIndexed -->|Yes| Phase2["Phase 2: repair missing remote backups"]
    Phase2 --> SelectSource["Choose a source whose inventory was retrieved successfully"]
    SelectSource --> Compare["Compare the complete local vault, including recovered accounts, with that source's inventory"]
    Compare --> MissingRemote{"Associated-account ID absent from this source?"}
    MissingRemote -->|Yes| Write["Write that missing backup to the source"]
    MissingRemote -->|No| NextAccount["Check the next local account"]
    Write --> Acknowledged{"Source acknowledges the write?"}
    Acknowledged -->|Yes| NextAccount
    Acknowledged -->|No| Pending["Record the failed backup as pending for retry"]
    Pending --> NextAccount
    NextAccount --> SourceComplete{"All local accounts checked for this source?"}
    SourceComplete -->|No| Compare
    SourceComplete -->|Yes| ReadBack["Read back the source and update per-source status"]
    ReadBack --> MoreSources{"Another successfully checked source?"}
    MoreSources -->|Yes| SelectSource
    MoreSources -->|No| Result["Show backed up, missing, failed, or unknown status"]
```

Phase 1 completes for every source before phase 2 begins. This ensures accounts recovered from one source are included when the app repairs another source. Existing local accounts are never overwritten by remote records. Sources whose inventory cannot be retrieved are not written to because the app cannot safely determine whether a backup is absent. This general flow excludes Vault Key records; Vault Key consolidation is handled independently with the Brave relay identity.

**How the source check works:** Before reading or writing account data, the app asks each backup source for its list of stored identifiers: `vaultkey` for the Vault Key and a numeric creation ID for each associated account. A successful response is a reliable basis for deciding whether a backup is already present or needs writing. A failed response does not prove an account is absent, so the app does not write to that source during the current run.

## Targeted account consolidation

```mermaid
flowchart TD
    Trigger["Select Consolidate backup on an associated account"] --> Select["Use the selected account ID"]
    Select --> Index["For each enabled source, retrieve stored associated-account IDs"]
    Index --> IndexOK{"Could the app retrieve that source's stored ID list?"}
    IndexOK -->|No| Unknown["Mark that account's source status unavailable or unknown"]
    IndexOK -->|Yes| Present{"Selected account ID already present?"}
    Present -->|Yes| BackedUp["Record the source as backed up"]
    Present -->|No| Write["Write only the selected account to the source"]
    Write --> WriteOK{"Source acknowledges the write?"}
    WriteOK -->|No| Failed["Record backup failure and keep it pending for retry"]
    WriteOK -->|Yes| ReadBack["Read back the source and update status"]
    Unknown --> Next["Continue with the next source"]
    BackedUp --> Next
    Failed --> Next
    ReadBack --> Next
    Next --> Complete{"All sources processed?"}
    Complete -->|No| Index
    Complete -->|Yes| Result["Show the selected account's per-source backup status"]
```

Targeted consolidation only checks, writes, and updates status for the selected associated account. It does not restore missing accounts and does not process unrelated associated accounts.

## Backup-status actions

```mermaid
flowchart LR
    Accounts["Accounts"] --> Account["Open associated account"]
    Account --> Status["Review per-source status"]
    Status --> One["Select Consolidate backup"]
    One --> Targeted["Run targeted consolidation"]
    Accounts --> Settings["Open Settings"]
    Settings --> VaultKey["Open Vault Key"]
    VaultKey --> VaultKeyState{"Vault Key quorum complete?"}
    VaultKeyState -->|No| Retry["Retry Vault Key setup"]
    VaultKeyState -->|Yes| All["Select Consolidate VaultKey backup"]
    All --> Full["Run Vault Key consolidation only"]
```

## Vault Key consolidation

```mermaid
flowchart TD
    Trigger["App startup, hourly Vault Key work, or Consolidate VaultKey backup"] --> Stored{"Vault Key stored locally?"}
    Stored -->|No| Skip["Do nothing"]
    Stored -->|Yes| Fresh{"Manual request, or last cycle older than one hour, or a Vault Key write pending?"}
    Fresh -->|No| Skip
    Fresh -->|Yes| Index["For each enabled source, retrieve its stored Vault Key ID using the Brave relay identity and Brave seed"]
    Index --> IndexOK{"Could the app retrieve that source's stored ID list?"}
    IndexOK -->|No| Unknown["Mark unavailable or unknown and queue a retry"]
    IndexOK -->|Yes| Present{"Vault Key ID already present?"}
    Present -->|Yes| BackedUp["Record the source as backed up"]
    Present -->|No| Write["Write the Vault Key to the source"]
    Write --> WriteOK{"Source acknowledges the write?"}
    WriteOK -->|Yes| BackedUp
    WriteOK -->|No| Failed["Record the failure and queue a retry"]
    Unknown --> Next["Continue with the next source"]
    BackedUp --> Next
    Failed --> Next
    Next --> Complete{"All sources processed?"}
    Complete -->|No| Index
    Complete -->|Yes| Result["Report relay and Brave quorum"]
```

Vault Key consolidation never restores, writes, or changes status for associated accounts, and the Vault Key is never restored from a remote copy. It runs on its own hourly schedule so a failed Vault Key backup is retried without depending on account consolidation.

Per-source statuses are `Backed up`, `Missing`, `Checking`, `Backing up`, `Backup failed`, or `Unavailable / unknown`. The status reflects the latest completed or active operation; it is not a guarantee that a remote service remains reachable.
