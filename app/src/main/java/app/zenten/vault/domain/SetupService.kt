package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService

/** First-run state transition: master persistence is rolled back unless its relay backup succeeds. */
class SetupService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val backupService: BackupService,
    private val recoveryService: RecoveryService,
    private val retryScheduler: RelayBackupRetryScheduler? = null,
) {
    suspend fun performSetup(master: Account): Result<Unit> = runCatching {
        require(master.nostr != null) { "Master account must have a Nostr identity" }
        secureStorage.addMasterAccount(master)
        val result = backupService.backupMaster(master)
        check(result.acknowledged) { "Master relay backup was not acknowledged" }
        appStorage.setSetupComplete()

        Unit
    }.onFailure {
        secureStorage.removeMasterAccount()
        appStorage.clearSetupComplete()
    }

    /**
     * Rebuilds the empty local registry after importing an existing master key. This runs
     * separately from setup so the Accounts screen can report progress while relays respond.
     */
    suspend fun recoverExistingAccounts(): Result<RecoveryService.RecoveryResult> = runCatching {
        val master = requireNotNull(secureStorage.getMasterAccount()) { "Master account is unavailable" }
        // A recovery error is returned to the UI rather than silently converted to an empty
        // result. The master remains available and the user can retry.
        recoveryService.discoverSubAccounts(master)
    }

    suspend fun addAccount(master: Account, account: Account): Result<Unit> = runCatching {
        secureStorage.addAccount(account)
        val result = backupService.backupSubAccount(master, account)
        if (!result.acknowledged) {
            appStorage.addFailedBackupAccountId(account.createdAt)
            retryScheduler?.schedule()
        } else {
            appStorage.removeFailedBackupAccountId(account.createdAt)
        }
    }
}
