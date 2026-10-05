package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
import kotlinx.coroutines.flow.first

/** VaultKey onboarding and local-first associated-account persistence. */
class SetupService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val consolidationEngine: AccountConsolidationEngine,
    private val workScheduler: BackupWorkScheduler? = null,
) {
    suspend fun performSetup(vaultKey: Account): Result<Unit> = runCatching {
        persistVaultKey(vaultKey)
        val result = consolidationEngine.backupVaultKey(vaultKey)
        check(result.quorumReached) {
            when {
                !result.relayQuorum && !result.braveQuorum -> "VaultKey backup needs at least one relay and one Brave Sync server"
                !result.relayQuorum -> "VaultKey backup did not reach relay quorum"
                else -> "VaultKey backup did not reach Brave Sync quorum"
            }
        }
        appStorage.setSetupComplete()
        workScheduler?.scheduleConsolidation()
    }

    /**
     * Commits a generated key before the wizard advances and attempts every configured source.
     * Setup intentionally remains incomplete because later wizard steps are still mandatory.
     */
    suspend fun persistGeneratedVaultKeyAndBackup(
        vaultKey: Account,
        createFlowRelayIdentity: NostrKeyPair,
        onProgress: suspend (AccountConsolidationEngine.VaultKeyBackupProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        persistVaultKey(vaultKey)
        appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.BACKUP_INTRO)
        // Only the create-flow relay operation uses the recoverable Brave sub-account
        // identity. The stored vaultKey keeps its normal identity for later consolidation.
        val createFlowVaultKey = vaultKey.copy(nostr = createFlowRelayIdentity)
        // Source-level state records failures for later retries. A network problem must not
        // undo the irreversible local commit or send the user back to key generation.
        runCatching { consolidationEngine.backupVaultKey(createFlowVaultKey, onProgress) }
    }

    suspend fun retryVaultKeyBackup(): Result<Unit> = runCatching {
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        val result = consolidationEngine.backupVaultKey(vaultKey)
        check(result.quorumReached) { "VaultKey backup quorum is still unavailable" }
        appStorage.setSetupComplete()
        workScheduler?.scheduleConsolidation()
    }

    suspend fun addAccount(vaultKey: Account, account: Account): Result<Unit> = runCatching {
        check(appStorage.setupComplete.first()) {
            "VaultKey backup quorum must be satisfied before creating an associated account"
        }
        require(secureStorage.getVaultKey() != null && vaultKey.createdAt == secureStorage.getVaultKey()?.createdAt)
        secureStorage.addAccount(account)
        // Targeted consolidation indexes every source before writing, so an existing canonical
        // account ID is never blindly pushed again and unrelated accounts are not processed.
        workScheduler?.scheduleAccountConsolidation(account.createdAt)
    }

    suspend fun consolidateNow(): Result<AccountConsolidationEngine.ConsolidationResult?> = runCatching {
        consolidationEngine.consolidate(ConsolidationTrigger.MANUAL, force = true)
    }

    private suspend fun persistVaultKey(vaultKey: Account) {
        // Keep the vaultKey on backup failure so setup can resume without generating another key.
        secureStorage.addVaultKey(vaultKey)
        appStorage.clearSetupComplete()
    }
}

interface BackupWorkScheduler {
    fun scheduleConsolidation()
    fun scheduleAccountConsolidation(accountId: Long)
}
