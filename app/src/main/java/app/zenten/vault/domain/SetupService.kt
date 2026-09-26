package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.data.kv.CreateMasterKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import kotlinx.coroutines.flow.first

/** Master onboarding and local-first associated-account persistence. */
class SetupService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val consolidationEngine: AccountConsolidationEngine,
    private val workScheduler: BackupWorkScheduler? = null,
) {
    suspend fun performSetup(master: Account): Result<Unit> = runCatching {
        persistMaster(master)
        val result = consolidationEngine.backupMaster(master)
        check(result.quorumReached) {
            when {
                !result.relayQuorum && !result.braveQuorum -> "Master backup needs at least one relay and one Brave Sync server"
                !result.relayQuorum -> "Master backup did not reach relay quorum"
                else -> "Master backup did not reach Brave Sync quorum"
            }
        }
        appStorage.setSetupComplete()
        workScheduler?.scheduleConsolidation()
    }

    /**
     * Commits a generated key before the wizard advances and attempts every configured source.
     * Setup intentionally remains incomplete because later wizard steps are still mandatory.
     */
    suspend fun persistGeneratedMasterAndBackup(
        master: Account,
        onProgress: suspend (AccountConsolidationEngine.MasterBackupProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        persistMaster(master)
        appStorage.setCreateMasterKeyStep(CreateMasterKeyStep.BACKUP_INTRO)
        // Source-level state records failures for later retries. A network problem must not
        // undo the irreversible local commit or send the user back to key generation.
        runCatching { consolidationEngine.backupMaster(master, onProgress) }
    }

    suspend fun retryMasterBackup(): Result<Unit> = runCatching {
        val master = requireNotNull(secureStorage.getMasterAccount()) { "Master account is unavailable" }
        val result = consolidationEngine.backupMaster(master)
        check(result.quorumReached) { "Master backup quorum is still unavailable" }
        appStorage.setSetupComplete()
        workScheduler?.scheduleConsolidation()
    }

    suspend fun addAccount(master: Account, account: Account): Result<Unit> = runCatching {
        check(appStorage.setupComplete.first()) {
            "Master backup quorum must be satisfied before creating an associated account"
        }
        require(secureStorage.getMasterAccount() != null && master.createdAt == secureStorage.getMasterAccount()?.createdAt)
        secureStorage.addAccount(account)
        // Targeted consolidation indexes every source before writing, so an existing canonical
        // account ID is never blindly pushed again and unrelated accounts are not processed.
        workScheduler?.scheduleAccountConsolidation(account.createdAt)
    }

    suspend fun consolidateNow(): Result<AccountConsolidationEngine.ConsolidationResult?> = runCatching {
        consolidationEngine.consolidate(ConsolidationTrigger.MANUAL, force = true)
    }

    private suspend fun persistMaster(master: Account) {
        // Keep the master on backup failure so setup can resume without generating another key.
        secureStorage.addMasterAccount(master)
        appStorage.clearSetupComplete()
    }
}

interface BackupWorkScheduler {
    fun scheduleConsolidation()
    fun scheduleAccountConsolidation(accountId: Long)
}
