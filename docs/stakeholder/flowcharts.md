# App flowcharts

These diagrams are a discussion aid for the currently implemented Android proof of concept. They describe observed application behavior, not a production-readiness commitment. See [User journeys](user-journeys.md) for user goals and limitations, and [Backup and recovery](backup-and-recovery.md) for the user recovery responsibilities.

## App entry and navigation

```mermaid
flowchart TD
    Launch["App launch"] --> Prerequisites{"Verified internet and device time within 2 min of HTTPS server time?"}
    Prerequisites -->|No| Blocked["Unavailable screen: explain errors/warnings and offer a recheck"]
    Blocked --> Prerequisites
    Prerequisites -->|Yes| Root["Root checks persisted state"]
    Root --> Setup{"Master setup complete?"}
    Setup -->|No| Welcome["Get started"]
    Welcome -->|"Login with MasterKey"| MasterSetup["Set up master account"]
    Welcome -->|"Create MasterKey / Google / Apple / Microsoft"| ComingSoon["Coming soon message"]
    ComingSoon --> Welcome
    Setup -->|Yes| PendingExport{"Pending local key export?"}
    PendingExport -->|Yes| Export["Export account"]
    PendingExport -->|No| Accounts["Accounts"]
    Export --> Accounts
    Accounts --> Create["Create new SSH account"]
    Accounts --> Detail["Account details"]
    Accounts --> Settings["Settings"]
    Settings --> Master["Master Account"]
    Detail --> Accounts
    Master --> Accounts
```

The app resumes the mandatory export screen after a restart when an account has been created but its local export step has not yet been confirmed.

## Master-account setup

```mermaid
flowchart TD
    Start["Paste encrypted Ed25519 OpenSSH key"] --> Passphrase["Enter key passphrase"]
    Passphrase --> Validate{"Key and passphrase valid?"}
    Validate -->|No| Error["Show safe error and remain on setup"]
    Validate -->|Yes| Store["Store master account securely"]
    Store --> Sources["Check enabled backup sources sequentially"]
    Sources --> Missing["Write master only to successfully checked sources where it is absent"]
    Missing --> Quorum{"Eligible Nostr and Brave backups confirmed?"}
    Quorum -->|Yes| Complete["Mark setup complete"]
    Complete --> Schedule["Schedule full-vault consolidation"]
    Schedule --> Accounts["Open My Accounts"]
    Quorum -->|No| Incomplete["Keep master stored and leave setup incomplete"]
    Incomplete --> Retry["Retry backup without re-importing"]
    Retry --> Sources
```

The setup screen does not show supplied secret material in errors. A failed source check is treated as unknown, not as permission to write another backup.

## Associated-account creation and local export

```mermaid
flowchart TD
    Add["Select Create New Account"] --> EnterPassphrase["Enter master passphrase"]
    EnterPassphrase --> Check{"Passphrase valid and master quorum complete?"}
    Check -->|No| Rejected["Show error and do not create account"]
    Check -->|Yes| Generate["Generate encrypted Ed25519 OpenSSH account"]
    Generate --> Save["Save account locally and record pending export"]
    Save --> Queue["Queue targeted background consolidation"]
    Queue --> Export["Open required Export account screen"]
    Export --> Choose["Choose a trusted app from Android Sharesheet"]
    Choose --> Confirm{"User confirms the encrypted key was saved?"}
    Confirm -->|No| Export
    Confirm -->|Yes| Clear["Clear pending export"]
    Clear --> QueueProfile["Queue durable kind 0 profile publication"]
    QueueProfile --> Accounts["Open Accounts without waiting"]
    QueueProfile --> ProfileWorker["Background publish to both profile relays with retry"]
```

Opening the Android chooser is recorded, but Android cannot confirm that the receiving app retained the key. Back navigation is blocked on the export screen until the user confirms the export. Profile publication runs as account-keyed unique WorkManager work, so relay latency or failure does not prevent navigation to **My Accounts**.

## Full-vault consolidation and automatic recovery

```mermaid
flowchart TD
    Trigger["Successful setup, Master Account action, or hourly work"] --> Phase1["Phase 1: build a remote inventory and restore missing local accounts"]
    Phase1 --> Index["For each enabled source, retrieve its stored master and account IDs"]
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
    Compare --> MissingRemote{"Master or associated-account ID absent from this source?"}
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

Phase 1 completes for every source before phase 2 begins. This ensures accounts recovered from one source are included when the app repairs another source. Existing local accounts are never overwritten by remote records. Sources whose inventory cannot be retrieved are not written to because the app cannot safely determine whether a backup is absent.

**How the source check works:** Before reading or writing account data, the app asks each backup source for its list of stored identifiers: `masterkey` for the master account and a numeric creation ID for each associated account. A successful response is a reliable basis for deciding whether a backup is already present or needs writing. A failed response does not prove an account is absent, so the app does not write to that source during the current run.

## Targeted account consolidation

```mermaid
flowchart TD
    Trigger["Select Consolidate backup on an associated account"] --> Select["Use the selected account ID"]
    Select --> Index["For each enabled source, retrieve its stored master and account IDs"]
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
    Settings --> Master["Open Master Account"]
    Master --> MasterState{"Master quorum complete?"}
    MasterState -->|No| Retry["Retry master setup"]
    MasterState -->|Yes| All["Select Consolidate backups"]
    All --> Full["Run full-vault consolidation"]
```

Per-source statuses are `Backed up`, `Missing`, `Checking`, `Backing up`, `Backup failed`, or `Unavailable / unknown`. The status reflects the latest completed or active operation; it is not a guarantee that a remote service remains reachable.
