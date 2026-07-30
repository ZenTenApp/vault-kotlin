package app.zenten.vault.domain

import app.zenten.vault.data.model.Account

/** Applies Vault's backup policy across all configured remote targets. */
class AccountBackupCoordinator(
    private val targets: Set<AccountBackupTarget>,
) {
    init { require(targets.map { it.id }.distinct().size == targets.size) { "Backup target IDs must be unique" } }

    suspend fun backupMaster(master: Account): BackupReport = backup { it.backupMaster(master) }
    suspend fun backupSubAccount(master: Account, account: Account): BackupReport = backup { it.backupSubAccount(master, account) }

    suspend fun retrySubAccount(master: Account, account: Account, targetIds: Set<BackupTargetId>): BackupReport =
        backup(targetIds) { it.backupSubAccount(master, account) }

    private suspend fun backup(
        targetIds: Set<BackupTargetId> = targets.mapTo(mutableSetOf()) { it.id },
        operation: suspend (AccountBackupTarget) -> Result<Unit>,
    ): BackupReport {
        val results = targets.filter { it.id in targetIds }.associate { target -> target.id to operation(target) }
        return BackupReport(results.filterValues { it.isFailure }.keys)
    }
}

data class BackupReport(val failedTargets: Set<BackupTargetId>) {
    val isSuccess get() = failedTargets.isEmpty()
}
