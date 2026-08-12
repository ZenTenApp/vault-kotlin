package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
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
        require(master.nostr != null) { "Master account must have a Nostr identity" }
        // Keep the imported master on quorum failure so users can retry without re-entering it.
        secureStorage.addMasterAccount(master)
        appStorage.clearSetupComplete()
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
        workScheduler?.scheduleAccountBackup(account.createdAt)
    }

    suspend fun consolidateNow(): Result<AccountConsolidationEngine.ConsolidationResult?> = runCatching {
        consolidationEngine.consolidate(ConsolidationTrigger.MANUAL, force = true)
    }
}

interface BackupWorkScheduler {
    fun scheduleAccountBackup(accountId: Long)
    fun scheduleConsolidation()
    fun schedulePendingRetry()
}
