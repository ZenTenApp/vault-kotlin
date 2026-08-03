package app.zenten.vault.domain

import app.zenten.vault.data.model.Account

/** Adapts the existing Nostr backup protocol to the provider-neutral backup contract. */
class NostrAccountBackupTarget(
    private val nostrBackupService: BackupService,
) : AccountBackupTarget {
    override val id = BackupTargetId.NOSTR

    override suspend fun backupMaster(master: Account): Result<Unit> = runCatching {
        check(nostrBackupService.backupMaster(master).acknowledged) { "Master relay backup was not acknowledged" }
    }

    override suspend fun backupAssociatedAccount(master: Account, account: Account): Result<Unit> = runCatching {
        check(nostrBackupService.backupAssociatedAccount(master, account).acknowledged) { "Associated account relay backup was not acknowledged" }
    }
}
