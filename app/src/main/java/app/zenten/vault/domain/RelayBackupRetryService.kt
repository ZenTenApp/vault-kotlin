package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import kotlinx.coroutines.flow.first

/** Retries only the remote targets that previously failed for each local account. */
class RelayBackupRetryService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val backupCoordinator: AccountBackupCoordinator,
) {
    /** Compatibility constructor for Nostr-only callers/tests. */
    constructor(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        backupService: BackupService,
    ) : this(secureStorage, appStorage, AccountBackupCoordinator(setOf(NostrAccountBackupTarget(backupService))))
    suspend fun retryFailedBackups(): RetrySummary {
        val master = secureStorage.getMasterAccount() ?: return RetrySummary(0, 0, 0)
        // Migrate the pre-provider-aware relay queue once, so existing installs do not
        // retain stale retry work after upgrading.
        appStorage.failedBackupAccountIds.first().forEach { accountId ->
            appStorage.markPendingAccountBackup(accountId, BackupTargetId.entries.toSet())
        }
        var attempted = 0
        var succeeded = 0
        var removedMissing = 0
        appStorage.pendingAccountBackups.first().forEach { pending ->
            val account = secureStorage.getAccountById(pending.accountId)
            if (account == null) {
                appStorage.clearPendingAccountBackupTargets(pending.accountId, pending.targets)
                appStorage.removeFailedBackupAccountId(pending.accountId)
                removedMissing++
                return@forEach
            }
            attempted++
            val report = backupCoordinator.retryAssociatedAccount(master, account, pending.targets)
            val succeededTargets = pending.targets - report.failedTargets
            if (succeededTargets.isNotEmpty()) {
                appStorage.clearPendingAccountBackupTargets(pending.accountId, succeededTargets)
            }
            if (report.isSuccess) succeeded++
            // Keep legacy state coherent while old callers/tests still inspect it.
            if (report.isSuccess) appStorage.removeFailedBackupAccountId(pending.accountId)
        }
        return RetrySummary(attempted, succeeded, removedMissing)
    }

    data class RetrySummary(val attempted: Int, val succeeded: Int, val removedMissing: Int)
}
