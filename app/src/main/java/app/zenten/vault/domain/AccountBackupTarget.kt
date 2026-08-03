package app.zenten.vault.domain

import app.zenten.vault.data.model.Account

/** A remote destination for Vault account backups. */
enum class BackupTargetId { NOSTR, BRAVE_SYNC }

interface AccountBackupTarget {
    val id: BackupTargetId
    suspend fun backupMaster(master: Account): Result<Unit>
    suspend fun backupAssociatedAccount(master: Account, account: Account): Result<Unit>
}
