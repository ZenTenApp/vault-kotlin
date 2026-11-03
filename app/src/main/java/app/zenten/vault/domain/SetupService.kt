package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
import kotlinx.coroutines.CancellationException
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
        // Durable before any remote work so the launch router and the onboarding screen agree,
        // even after a process death, that an imported key is stored and only its backup is
        // missing. [AppStorageService.setSetupComplete] clears it on success.
        appStorage.requireImportedVaultKeyBackup()
        // The local commit happens first, so a prerequisite failure still leaves a retryable
        // Vault Key instead of sending the user back to key derivation.
        try {
            throwIfRemoteOperationBlocked()
            val result = withVaultPassphrase { passphrase ->
                consolidationEngine.backupVaultKey(vaultKey, passphrase)
            }
            check(result.quorumReached) { result.quorumFailureMessage() }
        } catch (failure: CancellationException) {
            // Cancellation is not a backup outcome; propagating it keeps structured concurrency.
            throw failure
        } catch (failure: Exception) {
            // Tell the caller the key is already committed, so it can offer the stored-key retry
            // instead of asking the user to paste and derive the key again.
            throw VaultKeyPersistedException(
                "The VaultKey is saved locally, but its backup is incomplete. " +
                    (failure.message ?: "Retry the backup."),
                failure,
            )
        }
        appStorage.setSetupComplete()
        // Record the evidence that justified completion, so every setup-complete state in the app
        // also carries the backup fact it depends on.
        appStorage.setVaultKeyQuorumReached(true)
        workScheduler?.scheduleConsolidation()
    }

    /**
     * Commits a generated key before the wizard advances and attempts every configured source.
     * Setup intentionally remains incomplete because later wizard steps are still mandatory.
     *
     * The quorum outcome is returned instead of thrown: a network problem must not undo the
     * irreversible local commit or send the user back to key generation. Only the final
     * [completeGeneratedVaultKeySetup] step turns a reached quorum into completed setup.
     */
    suspend fun persistGeneratedVaultKeyAndBackup(
        vaultKey: Account,
        createFlowRelayIdentity: NostrKeyPair,
        onProgress: suspend (AccountConsolidationEngine.VaultKeyBackupProgress) -> Unit = {},
    ): Result<VaultKeySetupState> = runCatching {
        persistVaultKey(vaultKey)
        appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.BACKUP_INTRO)
        // Address the Vault Key record under the recoverable `vkbckp` identity. The factory would
        // derive that same identity from this key's seed anyway; passing it explicitly keeps the
        // create flow correct even if the stored key's own `nostr` value ever differs from the
        // one this run was created with.
        val createFlowVaultKey = vaultKey.copy(nostr = createFlowRelayIdentity)
        // Source-level state records failures for later retries, so a blocked prerequisite is
        // treated like any other source failure: it records no quorum and is retried later.
        val result = if (blockedRemoteOperation() == null) {
            runCatching {
                withVaultPassphrase { passphrase ->
                    consolidationEngine.backupVaultKey(createFlowVaultKey, passphrase, onProgress)
                }
            }.getOrNull()
        } else {
            null
        }
        val state = VaultKeySetupState.of(result)
        // Durable so the last wizard step and a process restart agree on whether quorum holds.
        appStorage.setVaultKeyQuorumReached(state.quorumReached)
        state
    }

    suspend fun retryVaultKeyBackup(): Result<Unit> = runCatching {
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        throwIfRemoteOperationBlocked()
        val result = withVaultPassphrase { passphrase ->
            consolidationEngine.backupVaultKey(vaultKey, passphrase)
        }
        check(result.quorumReached) { "VaultKey backup quorum is still unavailable" }
        appStorage.setSetupComplete()
        appStorage.setVaultKeyQuorumReached(true)
        workScheduler?.scheduleConsolidation()
    }

    /**
     * Whether an imported Vault Key is stored but its backup never reached quorum.
     *
     * The generated-key create flow also leaves a stored key while setup is incomplete, but it
     * always has a [CreateVaultKeyStep] marker and must keep routing to its own wizard, so that
     * case is excluded here.
     */
    suspend fun hasPendingImportedVaultKeyBackup(): Boolean =
        secureStorage.getVaultKey() != null &&
            appStorage.importedVaultKeySetupPending.first() &&
            !appStorage.setupComplete.first()

    /**
     * Marks generated-VaultKey setup complete, but only when a remote backup quorum is known.
     *
     * This is the final wizard step, so it is the last point at which the documented invariant
     * can be enforced. The check reads the persisted flag rather than a caller-supplied value so
     * a process restart cannot complete setup that the backup never satisfied. On failure the
     * caller is expected to offer [retryGeneratedVaultKeyBackup] on the same screen.
     */
    suspend fun completeGeneratedVaultKeySetup(): Result<Unit> = runCatching {
        check(appStorage.vaultKeyQuorumReached.first()) {
            "VaultKey backup quorum is still unavailable"
        }
        appStorage.setSetupComplete()
    }

    /** Re-attempts the Vault Key backup and records whether it now satisfies quorum. */
    suspend fun retryGeneratedVaultKeyBackup(): Result<Unit> = runCatching {
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        throwIfRemoteOperationBlocked()
        val result = withVaultPassphrase { passphrase ->
            consolidationEngine.backupVaultKey(vaultKey, passphrase)
        }
        appStorage.setVaultKeyQuorumReached(result.quorumReached)
        check(result.quorumReached) { result.quorumFailureMessage() }
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

/**
 * [SetupService.performSetup] failed after the imported Vault Key was committed to secure
 * storage.
 *
 * The distinction matters because the user can resume from the stored key without pasting it
 * again, unlike a parse/derivation failure where nothing was persisted.
 */
class VaultKeyPersistedException(message: String, cause: Throwable) : Exception(message, cause)

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

/**
 * Remote-backup outcome of one Vault Key backup attempt.
 *
 * The generated-VaultKey wizard keeps this instead of a plain failure so it can distinguish
 * "the backup could not reach quorum" from "key generation failed": the first is retryable on
 * the final wizard step, the second sends the user back to passphrase verification.
 */
data class VaultKeySetupState(
    val quorumReached: Boolean,
    val relayQuorum: Boolean,
    val braveQuorum: Boolean,
) {
    companion object {
        /** A blocked prerequisite or thrown source error reports no coverage at all. */
        fun of(result: AccountConsolidationEngine.VaultKeyBackupResult?): VaultKeySetupState =
            VaultKeySetupState(
                quorumReached = result?.quorumReached == true,
                relayQuorum = result?.relayQuorum == true,
                braveQuorum = result?.braveQuorum == true,
            )
    }
}

/**
 * Explains which part of the two-type Vault Key quorum is missing, without naming a source
 * credential or implying that a partially successful backup is a usable one.
 */
internal fun AccountConsolidationEngine.VaultKeyBackupResult.quorumFailureMessage(): String = when {
    !relayQuorum && !braveQuorum -> "VaultKey backup needs at least one relay and one Brave Sync server"
    !relayQuorum -> "VaultKey backup did not reach relay quorum"
    else -> "VaultKey backup did not reach Brave Sync quorum"
}
