# ZenTen Vault — UI/UX Flows

This document describes every screen, navigation flow, and user interaction in ZenTen Vault. It is intended as a complete specification for reimplementing the UI in native Android (Kotlin/Jetpack Compose) or iOS (Swift/SwiftUI).

## Table of Contents

1. [Navigation Structure](#1-navigation-structure)
2. [App Initialization Flow](#2-app-initialization-flow)
3. [Onboarding Flow (Get Started)](#3-onboarding-flow-get-started)
4. [Accounts List Screen](#4-accounts-list-screen)
5. [Account Detail Screen](#5-account-detail-screen)
6. [Sub-Account Creation Flow](#6-sub-account-creation-flow)
7. [Backup Status Screen](#7-backup-status-screen)
8. [Settings Screen](#8-settings-screen)
9. [Locked Account Unlock Flow](#9-locked-account-unlock-flow)
10. [Pending Restore Flow](#10-pending-restore-flow)
11. [Component Catalog](#11-component-catalog)

---

## 1. Navigation Structure

Routes map to screens as follows:

```
/                           → App entry (redirects based on setup state)
/get-started/               → Welcome / onboarding root
/get-started/ssh            → SSH key entry + passphrase + setup progress
/accounts/                  → Accounts list (main screen after setup)
/accounts/[id]              → Account detail
/backup-status/             → Backup status overview
/settings/                  → Settings
/new-accounts/              → Create new sub-account
```

### Navigation Guards

- If `isSetupComplete() = false` → redirect to `/get-started`
- If `isSetupComplete() = true` → redirect to `/accounts`
- The `/get-started` route is only accessible before setup

---

## 2. App Initialization Flow

```
App Launch
    │
    ▼
Show LoadingScreen (spinner)
    │
    ▼
isSetupComplete()?
    │
    ├── false ──▶ Navigate to /get-started
    │
    └── true ──▶ getMasterAccount()
                    │
                    ├── null ──▶ Navigate to /get-started (corrupted state)
                    │
                    └── found ──▶ Load sub-accounts + locked accounts
                                    │
                                    ▼
                                 Navigate to /accounts
                                    │
                                    ▼ (background, non-blocking)
                                 Run background sync
                                 Run startup retries
```

### LoadingScreen

- Full-screen centered spinner
- No user interaction
- Shown during async initialization

### DeviceTimeGate

- Wraps the entire app
- Checks if device clock is within acceptable range
- If clock is wrong: shows `IncorrectTimeScreen` with instructions to fix device time
- Required because Nostr events use Unix timestamps; wrong clock breaks event signing

### OnlineGate

- Wraps screens that require network
- If offline: shows `OfflineScreen` with retry button

---

## 3. Onboarding Flow (Get Started)

### 3.1 Welcome Screen (`/get-started`)

**Purpose**: Entry point for new users.

**UI Elements**:

- App logo/icon (hero)
- App name and tagline
- "Get Started" / "Import SSH Key" button (primary action)
- Settings button (top-right, for relay URL configuration before setup)

**Behavior**:

- Tapping the primary button → navigate to `/get-started/ssh`
- Settings button → opens `PreLoginSettingsModal`

### 3.2 SSH Key Setup Screen (`/get-started/ssh`)

**Purpose**: Multi-step setup wizard for master account creation.

**Steps**:

#### Step 1: SSH Key Entry

**UI Elements**:

- Back button (returns to welcome)
- Title: "Enter your SSH Key"
- Multi-line text input for SSH private key
- Paste button / QR scan button
- Continue button

**Accepted formats**:

- PEM (`-----BEGIN OPENSSH PRIVATE KEY-----`)
- JSON `{"OPENSSH PRIVATE KEY": "..."}`
- Raw base64

**Validation**:

- Must be valid OpenSSH v1 format
- Must be Ed25519 key type
- Error shown inline if invalid

#### Step 2: Passphrase Entry

**UI Elements**:

- Title: "Enter your passphrase"
- Password text input (masked, with show/hide toggle)
- Continue button

**Validation**:

- Non-empty passphrase required
- App attempts to decrypt the SSH key with the passphrase
- Error shown if passphrase cannot decrypt the SSH key

#### Step 3: Progress Screen

**UI Elements**:

- Progress bar (animated)
- Status message (updates as steps complete)
- Step indicators:
  1. Validating SSH key
  2. Deriving mnemonic
  3. Deriving Nostr keys
  4. Generating Brave Sync code
  5. Publishing relay backup
  6. Syncing Brave Sync
  7. Looking for existing accounts
  8. Complete

**Behavior**:

- Non-interactive during progress
- On success with no existing sub-accounts → navigate to `/accounts`
- On success with existing sub-accounts found → show recovery flow before navigating to `/accounts`
- On failure (relay backup failed) → show error with retry option
- Relay backup failure is fatal; user must retry

**Pre-login Settings**:

- Settings button accessible before login (top-right on welcome screen)
- Opens `PreLoginSettingsModal` for relay URL configuration
- Relay URLs can only be changed before setup completes

---

## 4. Accounts List Screen (`/accounts`)

**Purpose**: Main screen showing all accounts.

### Layout

```
┌─────────────────────────────────────┐
│  Header Surface                     │
│  ┌─────────────────────────────┐    │
│  │ "My Accounts"               │    │
│  │ Subtitle: locked/pending    │    │
│  │ count indicators            │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │ Account Card (Sub 1)        │    │
│  │ Account Card (Sub 2)        │    │
│  │ ...                         │    │
│  └─────────────────────────────┘    │
│                                     │
│  [Pending Restores Section]         │
│  ┌─────────────────────────────┐    │
│  │ Pending Restore Card 1      │    │
│  │ ...                         │    │
│  └─────────────────────────────┘    │
│                                     │
│  [Locked Accounts Section]          │
│  ┌─────────────────────────────┐    │
│  │ Locked Account Card 1       │    │
│  │ ...                         │    │
│  └─────────────────────────────┘    │
│                                     │
│  [FAB: Add Account]                 │
└─────────────────────────────────────┘
```

### Account Card

- Avatar with account initials (colored)
- Account label (the `createdAt` timestamp for a sub-account)
- The master account is a hidden signing/recovery identity and is never listed.
- SSH key fingerprint (truncated)
- Backup status indicator (healthy / partial / missing)
- Chevron icon
- Tap → navigate to `/accounts/[id]`

### Pending Restore Card

- Cloud download icon (amber/warning color)
- Label (createdAt timestamp)
- Source badge (relay or brave sync)
- Tap → opens `RestoreAccountDialog`

### Locked Account Card

- Lock icon (red/error color)
- Account label (createdAt timestamp)
- Tap → opens unlock dialog

### Unlock Dialog

- Title: "Unlock Account"
- Explanation that the account needs to be re-fetched from backup
- Retry button
- Cancel button
- Error message on failure

### FAB (Floating Action Button)

- "+" icon
- Tap → navigate to `/new-accounts/` to create a new sub-account

### Empty State

- Shown when no sub-accounts, no locked accounts, no pending restores
- Icon + message: "No accounts yet"
- Prompt to add first sub-account

---

## 5. Account Detail Screen (`/accounts/[id]`)

**Purpose**: Shows full details for a single account.

### Layout

```
┌─────────────────────────────────────┐
│  Back button + Account Label        │
│  (createdAt timestamp)              │
│                                     │
│  Created: <formatted timestamp>     │
│                                     │
│  [SSH Key Section]                  │
│  ┌─────────────────────────────┐    │
│  │ SSH Fingerprint             │    │
│  │ Public Key (truncated)      │    │
│  │ [Copy Fingerprint]          │    │
│  │ [Show Private Key]          │    │
│  └─────────────────────────────┘    │
│                                     │
│  [Backup Status Section]            │
│  ┌─────────────────────────────┐    │
│  │ Relay: ✓ / ✗ per relay      │    │
│  │ Brave Sync: ✓ / ✗           │    │
│  │ [Retry Backup] (if failed)  │    │
│  └─────────────────────────────┘    │
│                                     │
│  [Danger Zone]                      │
│  [Delete Account]                   │
└─────────────────────────────────────┘
```

### Private Key Modal (`PrivateDataModal`)

- Confirmation prompt before showing (e.g. "Are you sure?")
- Shows SSH private key (PEM format)
- Copy button
- Share button (file export)

---

## 6. Sub-Account Creation Flow

**Purpose**: Create a new associated account (fresh SSH key pair).

### Entry Point

- FAB on accounts list → `/new-accounts/`

### Flow

#### Step 1: Confirmation

**UI Elements**:

- Title: "Create New Account"
- Explanation: "A new SSH key pair will be generated and backed up automatically."
- The account will be labeled with its creation timestamp
- Create button
- Cancel button

#### Step 2: Progress

**UI Elements**:

- Animated progress bar
- Status messages:
  1. "Recording timestamp..."
  2. "Generating SSH key pair..."
  3. "Saving account..."
  4. "Starting backup..."
  5. "Done"

**Behavior**:

- Non-interactive during progress
- On success → navigate back to accounts list
- New account appears immediately in the list with its `createdAt` timestamp as label
- Backup jobs run in background (non-fatal)

### Account Creation Shell (`AccountCreationShell`)

Shared wrapper component providing:

- Consistent header with back button
- Animated fade-in content
- Progress bar (animated width, 0-100%)
- Error surface with icon and message
- Loading state management

---

## 7. Backup Status Screen (`/backup-status`)

**Purpose**: Shows backup health for all accounts across all sources.

### Layout

```
┌─────────────────────────────────────┐
│  "Backup Status"                    │
│  Last updated: <timestamp>          │
│  [Refresh button]                   │
│                                     │
│  Summary Cards:                     │
│  ┌──────┐ ┌──────┐ ┌──────┐        │
│  │  ✓   │ │  ⚠   │ │  ✗   │        │
│  │  5   │ │  2   │ │  1   │        │
│  │Healthy│ │Partial│ │Missing│       │
│  └──────┘ └──────┘ └──────┘        │
│                                     │
│  Relay URLs:                        │
│  relay.damus.io  relay.nostr.band   │
│  nos.lol  ...                       │
│                                     │
│  Account List:                      │
│  ┌─────────────────────────────┐    │
│  │ BackupStatusAccountCard 1   │    │
│  │ BackupStatusAccountCard 2   │    │
│  │ ...                         │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
```

### BackupStatusAccountCard

- Account label (createdAt timestamp)
- Health badge (healthy/partial/missing/unknown)
- Per-relay presence indicators (✓/✗ per relay)
- Brave Sync presence indicator
- Retry button (if partial or missing)

### Refresh Behavior

- Manual refresh button triggers `backupStatusCacheService.refresh()`
- Shows loading spinner during refresh
- Auto-refreshes when screen comes into focus

---

## 8. Settings Screen (`/settings`)

**Purpose**: App configuration and account management.

### Sections

#### Relay Configuration

- List of configured relay URLs
- Each relay: URL + connection status indicator
- Add relay button (only before setup lock)
- Remove relay button (only before setup lock)
- Note: relays are locked after setup completes

#### App Information

- App version
- Build number

#### Danger Zone

- "Reset App" button
  - Confirmation dialog
  - Clears all local data (secure storage + AsyncStorage)
  - Navigates to onboarding

### Pre-Login Settings Modal (`PreLoginSettingsModal`)

Accessible from the get-started screen before setup.

- Relay URL list (editable)
- Add/remove relay URLs
- Validate URLs before saving
- Save button

---

## 9. Locked Account Unlock Flow

**Trigger**: User taps a locked account card on the accounts list.

### Unlock Dialog

```
┌─────────────────────────────────────┐
│  "Unlock Account"                   │
│  "<account label>"                  │
│                                     │
│  "This account could not be fully   │
│   restored. Tap Retry to attempt    │
│   re-fetching from backup."         │
│                                     │
│  [Retry] button                     │
│  [Cancel] button                    │
│                                     │
│  Error: "Could not fetch backup"    │
└─────────────────────────────────────┘
```

**Behavior**:

1. User taps Retry
2. App attempts to re-fetch account from relays or Brave Sync
3. On success:
   - Account moves from locked list to full accounts list
   - Dialog closes
   - Accounts list refreshes
4. On failure:
   - Error message shown
   - User can retry again or cancel

---

## 10. Pending Restore Flow

**Trigger**: User taps a pending restore card on the accounts list.

### Restore Account Dialog (`RestoreAccountDialog`)

```
┌─────────────────────────────────────┐
│  "Restore Account"                  │
│                                     │
│  Account: <createdAt timestamp>     │
│  Source: Relay / Brave Sync         │
│  Last updated: <date>               │
│                                     │
│  "This account was found in your    │
│   backup. Restore it now?"          │
│                                     │
│  [Restore] button                   │
│  [Cancel] button                    │
└─────────────────────────────────────┘
```

**Behavior**:

1. User taps Restore
2. `importAccountFromBackup(backup, masterAccount)` called
3. Result:
   - `"restored"` → account added to full accounts list, dialog closes
   - `"locked"` → account added to locked accounts list, dialog closes
   - `"skipped"` → dialog closes (already exists)
4. Accounts list refreshes

---

## 11. Component Catalog

### Core Components

#### `Btn`

Custom button component.

- Variants: filled, outlined, text
- Loading state (spinner)
- Disabled state

#### `AccountCard`

Displays a single account in the accounts list.

- Avatar with initials
- Label (createdAt timestamp for sub-accounts)
- SSH fingerprint (truncated)
- Backup health indicator

#### `BackupStatusAccountCard`

Card showing backup health for a single account on the Backup Status screen.

#### `PrivateDataModal`

Modal for displaying sensitive data (SSH private key).

- Confirmation prompt before showing.
- Copy and share buttons.

#### `RestoreAccountDialog`

Dialog for confirming account restore from pending restore.

#### `AccountCreationShell`

Shared wrapper for the sub-account creation screen.

#### `PreLoginSettingsButton`

Settings button shown on the get-started screen.

#### `PreLoginSettingsModal`

Modal for relay URL configuration before setup.

#### `DeviceTimeGate`

Wrapper that blocks app if device time is incorrect.

#### `OnlineGate`

Wrapper that shows offline screen if no network.

#### `LoadingScreen`

Full-screen loading spinner.

#### `OfflineScreen`

Full-screen offline indicator with retry button.

#### `IncorrectTimeScreen`

Full-screen error for wrong device clock.

#### `ErrorBoundary`

Catches unhandled errors and shows a fallback UI.

#### `CustomToast`

Toast notification component (success/error/info).

#### `AppVersion`

Displays current app version string.

#### `QRScannerModal`

Camera-based QR code scanner modal. Used for importing SSH keys via QR code during onboarding.

#### `MainAppShell`

Wrapper providing consistent app chrome (navigation bar, safe areas).

---

## 12. Navigation Patterns

### Stack Navigation

All routes use stack navigation (push/pop). Back button always returns to previous screen.

### Modal Presentation

Dialogs and modals are presented as overlays using the platform's native dialog/sheet mechanism.

### Deep Linking

Sub-account detail supports deep linking via `/accounts/[id]` where `id` is the `createdAt` microsecond timestamp. The master identity has no detail route.

### Tab Navigation

The main app uses bottom tab navigation:

- Accounts tab → `/accounts`
- Backup Status tab → `/backup-status`
- Settings tab → `/settings`

---

## 13. UI State Management

### Loading States

All async operations show loading indicators:

- Button loading state (spinner replaces icon)
- Progress bar for multi-step operations
- Full-screen loading for app initialization

### Error States

Errors are displayed:

- Inline below the relevant input (validation errors)
- In an error surface with icon (operation errors)
- As toast notifications (background operation results)

### Empty States

Screens with lists show empty state illustrations when no data:

- Accounts list: "No accounts yet" with add prompt
- Backup status: "No backup data" with refresh prompt

### Optimistic Updates

Account list updates optimistically:

- New account appears immediately after creation
- Removed account disappears immediately
- Background jobs update status asynchronously

---

## 14. Accessibility & UX Guidelines

### Password Fields

- Always masked by default
- Show/hide toggle (eye icon)
- No autocomplete/autofill for security

### Sensitive Data

- SSH private keys are never shown without explicit user action
- Require confirmation before revealing
- Auto-hide after timeout (if implemented)

### Animations

- Fade-in for screen content
- Slide-in for modals
- Progress bar animations for key generation

### Haptic Feedback

- Success haptic on account creation
- Error haptic on validation failure

### Offline Behavior

- All read operations work offline (local storage)
- Write operations that require network show appropriate error
- Background sync deferred until online
