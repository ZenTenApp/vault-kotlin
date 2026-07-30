package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService

/** First-run state transition: master persistence is rolled back unless its relay backup succeeds. */
class SetupService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val backupCoordinator: AccountBackupCoordinator,
    private val recoveryService: RecoveryService,
    private val retryScheduler: RelayBackupRetryScheduler? = null,
) {
    /** Compatibility constructor for Nostr-only callers/tests. */
    constructor(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        backupService: BackupService,
        recoveryService: RecoveryService,
        retryScheduler: RelayBackupRetryScheduler? = null,
    ) : this(
        secureStorage,
        appStorage,
        AccountBackupCoordinator(setOf(NostrAccountBackupTarget(backupService))),
        recoveryService,
        retryScheduler,
    )
    suspend fun performSetup(master: Account): Result<Unit> = runCatching {
        require(master.nostr != null) { "Master account must have a Nostr identity" }
        secureStorage.addMasterAccount(master)
        val report = backupCoordinator.backupMaster(master)
        check(report.isSuccess) { "Master backup failed for: ${report.failedTargets.joinToString()}" }
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
        val report = backupCoordinator.backupSubAccount(master, account)
        if (!report.isSuccess) {
            appStorage.markPendingAccountBackup(account.createdAt, report.failedTargets)
            retryScheduler?.schedule()
        }
    }
}
