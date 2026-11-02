package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
import kotlinx.coroutines.flow.first

/** VaultKey onboarding and local-first associated-account persistence. */
class SetupService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val consolidationEngine: AccountConsolidationEngine,
    private val remoteOperationPrecondition: RemoteOperationPrecondition,
    private val vaultKeySession: VaultKeySession,
    private val workScheduler: BackupWorkScheduler? = null,
) {
    suspend fun performSetup(vaultKey: Account): Result<Unit> = runCatching {
        persistVaultKey(vaultKey)
        // The local commit happens first, so a prerequisite failure still leaves a retryable
        // Vault Key instead of sending the user back to key derivation.
        throwIfRemoteOperationBlocked()
        val result = withVaultPassphrase { passphrase ->
            consolidationEngine.backupVaultKey(vaultKey, passphrase)
        }
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
        // Only the create-flow relay operation uses the recoverable Vault Key backup (`vkbckp`)
        // associated-account identity. The stored vaultKey keeps its normal identity for later
        // consolidation.
        val createFlowVaultKey = vaultKey.copy(nostr = createFlowRelayIdentity)
        // Source-level state records failures for later retries. A network problem must not
        // undo the irreversible local commit or send the user back to key generation, so a
        // blocked prerequisite is treated like any other source failure and skipped.
        if (blockedRemoteOperation() == null) {
            runCatching {
                withVaultPassphrase { passphrase ->
                    consolidationEngine.backupVaultKey(createFlowVaultKey, passphrase, onProgress)
                }
            }
        }
    }

    suspend fun retryVaultKeyBackup(): Result<Unit> = runCatching {
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        throwIfRemoteOperationBlocked()
        val result = withVaultPassphrase { passphrase ->
            consolidationEngine.backupVaultKey(vaultKey, passphrase)
        }
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

    private suspend fun persistVaultKey(vaultKey: Account) {
        // Keep the vaultKey on backup failure so setup can resume without generating another key.
        secureStorage.addVaultKey(vaultKey)
        appStorage.clearSetupComplete()
    }

    /**
     * Runs [block] with a copy of the session passphrase, zeroing it afterwards.
     *
     * The passphrase is never persisted; callers must have unlocked the session first (after
     * setup, creation, or recovery, or via the launch gate).
     */
    private suspend fun <T> withVaultPassphrase(block: suspend (CharArray) -> T): T {
        val passphrase = vaultKeySession.snapshot()
            ?: error("VaultKey passphrase is not available")
        return try {
            block(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * Returns the blocking prerequisite, or null when timestamped remote work is allowed.
     *
     * Mirrors [app.zenten.vault.background.AccountConsolidationWorker]: a time-source outage is
     * not evidence that the device clock is wrong, so only proven internet/clock failures block.
     */
    private suspend fun blockedRemoteOperation(): RemoteOperationReadiness? =
        when (val readiness = remoteOperationPrecondition.check()) {
            RemoteOperationReadiness.READY,
            RemoteOperationReadiness.TIME_UNVERIFIABLE -> null
            RemoteOperationReadiness.NO_VALIDATED_INTERNET,
            RemoteOperationReadiness.DEVICE_TIME_INCORRECT -> readiness
        }

    /** Fails the enclosing [runCatching] with a user-facing reason for the blocked operation. */
    private suspend fun throwIfRemoteOperationBlocked() {
        blockedRemoteOperation()?.let { blocked ->
            throw IllegalStateException(
                when (blocked) {
                    RemoteOperationReadiness.NO_VALIDATED_INTERNET ->
                        "No validated internet connection. Check your connection and retry."
                    RemoteOperationReadiness.DEVICE_TIME_INCORRECT ->
                        "Device time is incorrect. Enable automatic date and time and retry."
                    else -> "Remote operation prerequisites are not satisfied."
                },
            )
        }
    }
}

interface BackupWorkScheduler {
    /** Enqueues the post-unlock Vault Key and account cycles; the engine still applies freshness. */
    fun scheduleStartupConsolidation()

    /** Enqueues a full associated-account consolidation cycle. */
    fun scheduleConsolidation()

    /** Enqueues a cycle for one associated account only. */
    fun scheduleAccountConsolidation(accountId: Long)

    /** Enqueues a Vault Key-only cycle, which uses its own Nostr identity. */
    fun scheduleVaultKeyConsolidation()
}
