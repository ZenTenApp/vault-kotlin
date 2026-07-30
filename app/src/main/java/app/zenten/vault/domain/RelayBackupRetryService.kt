package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import kotlinx.coroutines.flow.first

/** Retries durable sub-account relay failures. Missing local records are pruned, never retried forever. */
class RelayBackupRetryService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val backupService: BackupService,
) {
    suspend fun retryFailedBackups(): RetrySummary {
        val master = secureStorage.getMasterAccount() ?: return RetrySummary(0, 0, 0)
        var attempted = 0
        var succeeded = 0
        var removedMissing = 0
        appStorage.failedBackupAccountIds.first().forEach { createdAt ->
            val account = secureStorage.getAccountById(createdAt)
            if (account == null) {
                appStorage.removeFailedBackupAccountId(createdAt)
                removedMissing++
                return@forEach
            }
            attempted++
            if (backupService.backupSubAccount(master, account).acknowledged) {
                appStorage.removeFailedBackupAccountId(createdAt)
                succeeded++
            }
        }
        return RetrySummary(attempted, succeeded, removedMissing)
    }

    data class RetrySummary(val attempted: Int, val succeeded: Int, val removedMissing: Int)
}
