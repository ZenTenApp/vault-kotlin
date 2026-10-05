package app.zenten.vault.domain

import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AccountSourceStatus
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.BackupErrorCategory
import app.zenten.vault.data.kv.ConsolidationCheckpoint
import app.zenten.vault.data.kv.ConsolidationPhase
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.data.kv.PendingSourceWrite
import app.zenten.vault.data.kv.PendingWriteReason
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.util.UUID

/**
 * Reconciles local accounts with their remote backup copies.
 *
 * There are three independent entry points, each with its own scope:
 *
 * | Entry point             | Records handled         | Restores missing local accounts |
 * | ----------------------- | ----------------------- | ------------------------------- |
 * | [backupVaultKey]        | the Vault Key only      | no                              |
 * | [consolidateVaultKey]   | the Vault Key only      | no                              |
 * | [consolidateAccount]    | one associated account  | no                              |
 * | [consolidate]           | all associated accounts | yes                             |
 *
 * Vault Key consolidation is deliberately separate from account consolidation because the two
 * record classes are published under different Nostr identities (see [NostrBackupSource]).
 *
 * Two rules govern every operation:
 *
 * 1. **Unknown is not missing.** A source is written to only after its own index call succeeded
 *    and proved the record absent. A failed index is recorded as `UNKNOWN`, never as `MISSING`.
 * 2. **Everything is sequential.** One [operationMutex] serializes whole operations so two runs
 *    can never interleave writes or durable status updates for the same source.
 *
 * Progress is durable: per-account/per-source state goes to [AccountSourceStatus], unfinished
 * work to [PendingSourceWrite], and the active position of a run to [ConsolidationCheckpoint].
 */
class AccountConsolidationEngine(
    private val sourceRepository: BackupSourceRepository,
    private val sourceFactory: BackupSourceFactory,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val operationMutex = Mutex()

    // -----------------------------------------------------------------------------------
    // Vault Key consolidation
    // -----------------------------------------------------------------------------------

    /**
     * Ensures every enabled source holds the Vault Key record and reports which ones do.
     *
     * Used by the setup paths, which pass the Vault Key explicitly because it may not be the
     * stored one yet (the Create VaultKey wizard backs it up under a create-flow identity).
     * This always runs; freshness is only applied by the scheduled [consolidateVaultKey].
     */
    suspend fun backupVaultKey(
        vaultKey: Account,
        onProgress: suspend (VaultKeyBackupProgress) -> Unit = {},
    ): VaultKeyBackupResult = operationMutex.withLock {
        runVaultKeyBackup(vaultKey, onProgress)
    }

    /**
     * Periodic/manual reconciliation of the Vault Key record using the stored Vault Key.
     *
     * Returns `null` when no Vault Key is stored yet or the previous cycle is still fresh and
     * nothing is queued for retry. Unlike [consolidate] this never touches associated accounts.
     */
    suspend fun consolidateVaultKey(force: Boolean = false): VaultKeyBackupResult? = operationMutex.withLock {
        if (!force && isVaultKeyResultStillFresh()) return@withLock null
        val vaultKey = secureStorage.getVaultKey() ?: return@withLock null
        runVaultKeyBackup(vaultKey)
    }

    /**
     * Attempts the Vault Key record at every enabled source.
     *
     * Every source is attempted even after earlier ones fail, because callers need the full
     * quorum picture (see [VaultKeyBackupResult]) rather than only the first error. No
     * [ConsolidationCheckpoint] is written: that checkpoint describes the two-phase account
     * cycle, and this single-record pass has no phase to resume.
     */
    private suspend fun runVaultKeyBackup(
        vaultKey: Account,
        onProgress: suspend (VaultKeyBackupProgress) -> Unit = {},
    ): VaultKeyBackupResult {
        val sources = enabledSources()
        val progressSources = sources.map { VaultKeyBackupSource(it.label, it.type) }
        val runContext = sourceFactory.createRunContext(vaultKey)
        val successes = mutableSetOf<String>()
        val failures = mutableSetOf<String>()

        sources.forEachIndexed { position, config ->
            // Report both edges of each source operation so the UI can show the active
            // destination as well as progress that advances after it finishes.
            onProgress(VaultKeyBackupProgress(progressSources, position, position))
            val source = sourceFactory.create(config, runContext)
            if (ensureVaultKeyAtSource(source, config, vaultKey)) successes += config.id else failures += config.id
            onProgress(VaultKeyBackupProgress(progressSources, position, position + 1))
        }

        // Quorum needs one relay *and* one Brave source, so the two types are counted apart.
        val configs = sources.associateBy { it.id }
        val quorumTypes = successes.mapNotNullTo(mutableSetOf()) { id ->
            configs[id]?.takeIf { it.countsTowardQuorum }?.type
        }
        appStorage.markVaultKeyConsolidationCompleted(clock.millis())
        return VaultKeyBackupResult(
            relayQuorum = BackupSourceType.NOSTR_RELAY in quorumTypes,
            braveQuorum = BackupSourceType.BRAVE_SYNC in quorumTypes,
            successfulSourceIds = successes,
            failedSourceIds = failures,
        )
    }

    /** Skips a non-forced Vault Key cycle while the last one is recent and nothing is pending. */
    private suspend fun isVaultKeyResultStillFresh(): Boolean {
        val last = appStorage.lastVaultKeyConsolidationCompletedAtMillis.first() ?: return false
        val pending = appStorage.pendingSourceWrites.first()
            .any { it.accountId == BackupAccountId.VAULT_KEY_VALUE }
        return clock.millis() - last < FRESHNESS_MILLIS && !pending
    }

    /**
     * Checks one source for the Vault Key record and writes it only when a successful index
     * proved it absent. Returns `true` when the source is known to hold the Vault Key.
     */
    private suspend fun ensureVaultKeyAtSource(
        source: AccountBackupSource,
        config: BackupSourceConfig,
        vaultKey: Account,
    ): Boolean {
        val id = BackupAccountId.VaultKey
        markChecking(id, config)
        val remote = source.index(vaultKey).getOrElse { error ->
            markUnknown(id, config, error)
            markPendingRetry(id, config, PendingWriteReason.VAULT_KEY_QUORUM)
            return false
        }
        if (id in remote.ids) {
            markPresent(id, config)
            return true
        }
        markBackingUp(id, config)
        return source.write(vaultKey, id, vaultKey).fold(
            onSuccess = {
                markWriteConfirmed(id, config)
                true
            },
            onFailure = { error ->
                markWriteFailed(id, config, error)
                markPendingRetry(id, config, PendingWriteReason.VAULT_KEY_QUORUM)
                false
            },
        )
    }

    // -----------------------------------------------------------------------------------
    // Targeted (single associated account) consolidation
    // -----------------------------------------------------------------------------------

    /** Reconciles one associated account without restoring, writing, or changing statuses for other accounts. */
    suspend fun consolidateAccount(
        accountId: Long,
        trigger: ConsolidationTrigger = ConsolidationTrigger.MANUAL,
    ): ConsolidationResult = operationMutex.withLock {
        // The Vault Key is only a credential source here; its own backup is never touched.
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        val account = requireNotNull(secureStorage.getAccountById(accountId)) { "Account is unavailable" }
        val id = BackupAccountId.associated(accountId)
        val cycle = newCycle(trigger)
        val tally = Tally()
        val runContext = sourceFactory.createRunContext(vaultKey)

        enabledSources().forEach { config ->
            checkpoint(cycle, ConsolidationPhase.RESTORE, config.id, id.value)
            val source = sourceFactory.create(config, runContext)
            ensureAccountAtSource(source, config, vaultKey, id, account, cycle, tally)
        }
        tally.toResult()
    }

    /** Checks one source for a single account, writes it when absent, then verifies that write. */
    private suspend fun ensureAccountAtSource(
        source: AccountBackupSource,
        config: BackupSourceConfig,
        vaultKey: Account,
        id: BackupAccountId,
        account: Account,
        cycle: ConsolidationCycle,
        tally: Tally,
    ) {
        markChecking(id, config)
        val remote = source.index(vaultKey).getOrElse { error ->
            // No pending entry is recorded: the account may already be backed up here, and the
            // next full consolidation re-checks this source anyway.
            tally.failures++
            markUnknown(id, config, error)
            return
        }
        if (id in remote.ids) {
            markPresent(id, config)
            return
        }

        checkpoint(cycle, ConsolidationPhase.BACKUP, config.id, id.value)
        markBackingUp(id, config, checked = true)
        source.write(vaultKey, id, account).fold(
            onSuccess = {
                tally.writes++
                markWriteConfirmed(id, config)
                verifyAccountWrite(source, config, vaultKey, id, tally)
            },
            onFailure = { error ->
                tally.failures++
                markWriteFailed(id, config, error)
                markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
            },
        )
    }

    /** Re-reads the source so an acknowledged-but-absent write is not reported as a backup. */
    private suspend fun verifyAccountWrite(
        source: AccountBackupSource,
        config: BackupSourceConfig,
        vaultKey: Account,
        id: BackupAccountId,
        tally: Tally,
    ) = source.index(vaultKey).fold(
        onSuccess = { verified ->
            if (id in verified.ids) {
                markPresent(id, config)
            } else {
                tally.failures++
                markWriteFailed(id, config, readBackMismatch(), checked = true)
                markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
            }
        },
        onFailure = { error ->
            tally.failures++
            markUnknown(id, config, error)
            markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
        },
    )

    // -----------------------------------------------------------------------------------
    // Full associated-account consolidation
    // -----------------------------------------------------------------------------------

    /**
     * Reconciles every associated account across every enabled source in two phases:
     *
     * - **Phase 1** indexes all sources and restores locally missing accounts.
     * - **Phase 2** writes the now-complete local set into the gaps found in phase 1.
     *
     * The phases never interleave, so an account recovered from one source is also repaired on
     * the others during the same run. Returns `null` when the run was skipped as unnecessary.
     */
    suspend fun consolidate(trigger: ConsolidationTrigger, force: Boolean = false): ConsolidationResult? = operationMutex.withLock {
        if (!force && isResultStillFresh()) return@withLock null
        // The Vault Key supplies credentials for associated-account sources, but is
        // reconciled only by backupVaultKey so the two identities cannot be mixed.
        val vaultKey = secureStorage.getVaultKey() ?: return@withLock null

        val cycle = newCycle(trigger)
        val sources = enabledSources()
        val runContext = sourceFactory.createRunContext(vaultKey)
        // Reuse one instance per source so source-specific sessions/caches can span the index
        // and repair phases without ever crossing endpoint boundaries.
        val sourceInstances = sources.associate { it.id to sourceFactory.create(it, runContext) }
        val tally = Tally()

        val snapshots = indexAndRestore(sources, sourceInstances, vaultKey, cycle, tally)
        refreshStatusesAfterRestores(sources, snapshots)
        sources.forEach { config ->
            // Sources whose index failed are skipped: absence could not be proven for them.
            val snapshot = snapshots[config.id]?.index ?: return@forEach
            repairSource(sourceInstances.getValue(config.id), config, snapshot, vaultKey, cycle, tally)
        }

        appStorage.markConsolidationCompleted(clock.millis())
        tally.toResult()
    }

    /** Skips a non-forced run when the last one is recent and nothing is waiting to be retried. */
    private suspend fun isResultStillFresh(): Boolean {
        val last = appStorage.lastConsolidationCompletedAtMillis.first() ?: return false
        val pending = appStorage.pendingSourceWrites.first()
        return clock.millis() - last < FRESHNESS_MILLIS && pending.isEmpty()
    }

    /** Phase 1: inspect every source and restore accounts that exist remotely but not locally. */
    private suspend fun indexAndRestore(
        sources: List<BackupSourceConfig>,
        sourceInstances: Map<String, AccountBackupSource>,
        vaultKey: Account,
        cycle: ConsolidationCycle,
        tally: Tally,
    ): Map<String, SourceSnapshot> {
        val snapshots = linkedMapOf<String, SourceSnapshot>()
        sources.forEach { config ->
            checkpoint(cycle, ConsolidationPhase.RESTORE, config.id, null)
            val source = sourceInstances.getValue(config.id)
            val knownBefore = associatedAccountInventory().keys
            knownBefore.forEach { markChecking(it, config) }
            source.index(vaultKey).fold(
                onSuccess = { remote ->
                    snapshots[config.id] = SourceSnapshot(index = remote)
                    knownBefore.forEach { markIndexed(it, config, present = it in remote.ids) }
                    if (config.restoreEnabled && config.trustedForRestore) {
                        restoreMissingAccounts(source, config, remote, knownBefore, vaultKey, tally)
                    }
                },
                onFailure = { error ->
                    tally.failures++
                    snapshots[config.id] = SourceSnapshot(error = error)
                    knownBefore.forEach { markUnknown(it, config, error) }
                },
            )
        }
        return snapshots
    }

    /**
     * Saves remote accounts that are absent locally, newest candidate first.
     *
     * Existing local accounts are never overwritten, and the Vault Key is never restored this
     * way: it only enters the app through onboarding, where the user supplies its passphrase.
     */
    private suspend fun restoreMissingAccounts(
        source: AccountBackupSource,
        config: BackupSourceConfig,
        remote: SourceIndex,
        knownIds: Set<BackupAccountId>,
        vaultKey: Account,
        tally: Tally,
    ) = remote.ids
        .filterNot { it.isVaultKey || it in knownIds }
        .forEach { id ->
            val candidates = remote.candidates[id].orEmpty().sortedByDescending { it.sortOrder }
            val recovered = candidates.firstNotNullOfOrNull { candidate ->
                source.restoreCandidate(vaultKey, candidate).getOrNull()
            }
            if (recovered != null) {
                secureStorage.addAccount(recovered)
                tally.restored++
                // The record demonstrably exists here, but nothing was backed up by this app.
                markIndexedPresent(id, config)
            }
        }

    /**
     * Re-states every source status against the complete local inventory.
     *
     * Accounts restored late in phase 1 were unknown while earlier sources were indexed, so
     * their status is derived again from the retained phase-1 snapshots before any repair runs.
     */
    private suspend fun refreshStatusesAfterRestores(
        sources: List<BackupSourceConfig>,
        snapshots: Map<String, SourceSnapshot>,
    ) {
        val inventory = associatedAccountInventory().keys
        sources.forEach { config ->
            val snapshot = snapshots[config.id]
            inventory.forEach { id ->
                val remote = snapshot?.index
                if (remote != null) markIndexed(id, config, present = id in remote.ids)
                else markUnknown(id, config, snapshot?.error)
            }
        }
    }

    /** Phase 2: write every local account the phase-1 snapshot lacked, then verify once. */
    private suspend fun repairSource(
        source: AccountBackupSource,
        config: BackupSourceConfig,
        snapshot: SourceIndex,
        vaultKey: Account,
        cycle: ConsolidationCycle,
        tally: Tally,
    ) {
        val known = snapshot.ids.toMutableSet()
        val acknowledgedWrites = mutableSetOf<BackupAccountId>()
        val failedWrites = mutableSetOf<BackupAccountId>()

        associatedAccountInventory().forEach { (id, account) ->
            if (id in known) return@forEach
            checkpoint(cycle, ConsolidationPhase.BACKUP, config.id, id.value)
            markBackingUp(id, config)
            source.write(vaultKey, id, account).fold(
                onSuccess = {
                    tally.writes++
                    known += id
                    acknowledgedWrites += id
                    markWriteConfirmed(id, config)
                },
                onFailure = { error ->
                    tally.failures++
                    failedWrites += id
                    markWriteFailed(id, config, error)
                    markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
                },
            )
        }
        verifySourceAfterWrites(source, config, vaultKey, acknowledgedWrites, failedWrites, tally)
    }

    /** Single read-back per source that turns acknowledged writes into confirmed backups. */
    private suspend fun verifySourceAfterWrites(
        source: AccountBackupSource,
        config: BackupSourceConfig,
        vaultKey: Account,
        acknowledgedWrites: Set<BackupAccountId>,
        failedWrites: Set<BackupAccountId>,
        tally: Tally,
    ) = source.index(vaultKey).fold(
        onSuccess = { verified ->
            associatedAccountInventory().keys.forEach { id ->
                when {
                    id in verified.ids -> markPresent(id, config)
                    id in failedWrites -> Unit // Preserve the write failure and its pending entry.
                    id in acknowledgedWrites -> {
                        // The source accepted the write but does not serve it back.
                        tally.failures++
                        markWriteFailed(id, config, readBackMismatch(), checked = true)
                        markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
                    }
                    else -> {
                        // It was in the retained phase-1 snapshot but disappeared before
                        // read-back. Record absence and force a later repair cycle.
                        markMissing(id, config)
                        markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
                    }
                }
            }
        },
        onFailure = { error ->
            // Writes were acknowledged but cannot be confirmed, so they stay retryable.
            tally.failures++
            acknowledgedWrites.forEach { id ->
                markUnknown(id, config, error)
                markPendingRetry(id, config, PendingWriteReason.CONSOLIDATION)
            }
        },
    )

    // -----------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------

    private suspend fun enabledSources() = sourceRepository.current().filter { it.enabled }

    /**
     * The accounts that general consolidation owns, keyed by their canonical backup ID.
     *
     * The Vault Key is deliberately excluded: it is published under a different Nostr identity
     * and is reconciled only by [backupVaultKey].
     */
    private fun associatedAccountInventory(): LinkedHashMap<BackupAccountId, Account> =
        linkedMapOf<BackupAccountId, Account>().apply {
            secureStorage.getAccounts().forEach { put(BackupAccountId.associated(it.createdAt), it) }
        }

    private fun newCycle(trigger: ConsolidationTrigger) =
        ConsolidationCycle(UUID.randomUUID().toString(), trigger, clock.millis())

    private fun readBackMismatch() =
        IllegalStateException("Source read-back did not confirm the acknowledged write")

    /** Records where a run currently is, so an interrupted cycle can be diagnosed later. */
    private suspend fun checkpoint(
        cycle: ConsolidationCycle,
        phase: ConsolidationPhase,
        sourceId: String?,
        accountId: String?,
    ) = appStorage.setConsolidationCheckpoint(
        ConsolidationCheckpoint(
            cycleId = cycle.id,
            trigger = cycle.trigger,
            phase = phase,
            currentSourceId = sourceId,
            currentAccountId = accountId,
            startedAtMillis = cycle.startedAtMillis,
            updatedAtMillis = clock.millis(),
        ),
    )

    /** Queues the account/source pair for a later retry attempt. */
    private suspend fun markPendingRetry(
        id: BackupAccountId,
        source: BackupSourceConfig,
        reason: PendingWriteReason,
    ) = appStorage.upsertPendingSourceWrite(
        PendingSourceWrite(id.value, source.id, reason, attemptCount = 1, lastAttemptAtMillis = clock.millis()),
    )

    // Named status writers below keep call sites readable and make the meaning of the
    // checked/successful flags explicit in one place instead of at every call.

    private suspend fun markChecking(id: BackupAccountId, source: BackupSourceConfig) =
        setStatus(id, source, AccountSourceBackupState.CHECKING)

    private suspend fun markBackingUp(id: BackupAccountId, source: BackupSourceConfig, checked: Boolean = false) =
        setStatus(id, source, AccountSourceBackupState.BACKING_UP, checked = checked)

    /**
     * The record is confirmed stored at the source as the result of this operation's own work,
     * so a successful-backup timestamp is recorded and any queued retry is cleared.
     */
    private suspend fun markPresent(id: BackupAccountId, source: BackupSourceConfig) {
        setStatus(id, source, AccountSourceBackupState.PRESENT, checked = true, successful = true)
        appStorage.clearPendingSourceWrite(id.value, source.id)
    }

    /**
     * The record was merely observed in an index listing. No successful-backup timestamp is
     * written and pending retries are left alone, because this operation stored nothing.
     */
    private suspend fun markIndexedPresent(id: BackupAccountId, source: BackupSourceConfig) =
        setStatus(id, source, AccountSourceBackupState.PRESENT, checked = true)

    private suspend fun markMissing(id: BackupAccountId, source: BackupSourceConfig) =
        setStatus(id, source, AccountSourceBackupState.MISSING, checked = true)

    /** Applies the outcome of a successful index call to one record. */
    private suspend fun markIndexed(id: BackupAccountId, source: BackupSourceConfig, present: Boolean) =
        if (present) markIndexedPresent(id, source) else markMissing(id, source)

    /** The source acknowledged a write that has not been read back yet. */
    private suspend fun markWriteConfirmed(id: BackupAccountId, source: BackupSourceConfig) {
        setStatus(id, source, AccountSourceBackupState.WRITE_CONFIRMED, successful = true)
        appStorage.clearPendingSourceWrite(id.value, source.id)
    }

    private suspend fun markWriteFailed(
        id: BackupAccountId,
        source: BackupSourceConfig,
        error: Throwable,
        checked: Boolean = false,
    ) = setStatus(id, source, AccountSourceBackupState.BACKUP_FAILED, checked = checked, error = error)

    /** The source could not be read conclusively, so absence must not be assumed. */
    private suspend fun markUnknown(id: BackupAccountId, source: BackupSourceConfig, error: Throwable?) =
        setStatus(id, source, AccountSourceBackupState.UNKNOWN, checked = true, error = error)

    /**
     * Persists one account/source status, preserving timestamps the current state does not set.
     *
     * @param checked the source answered conclusively during this operation.
     * @param successful the record is confirmed stored at the source.
     */
    private suspend fun setStatus(
        id: BackupAccountId,
        source: BackupSourceConfig,
        state: AccountSourceBackupState,
        checked: Boolean = false,
        successful: Boolean = false,
        error: Throwable? = null,
    ) {
        val now = clock.millis()
        val previous = appStorage.accountSourceStatuses.first()
            .firstOrNull { it.accountId == id.value && it.sourceId == source.id }
        appStorage.upsertAccountSourceStatus(
            AccountSourceStatus(
                accountId = id.value,
                sourceId = source.id,
                state = state,
                lastCheckedAtMillis = if (checked) now else previous?.lastCheckedAtMillis,
                lastSuccessfulBackupAtMillis = if (successful) now else previous?.lastSuccessfulBackupAtMillis,
                lastAttemptAtMillis = if (state in WRITE_ATTEMPT_STATES) now else previous?.lastAttemptAtMillis,
                errorCategory = error?.toCategory(),
            ),
        )
    }

    // -----------------------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------------------

    /** Identity of a single consolidation run, used for durable checkpoints. */
    private data class ConsolidationCycle(
        val id: String,
        val trigger: ConsolidationTrigger,
        val startedAtMillis: Long,
    )

    /** Phase-1 outcome for one source: either its retained index or the error that replaced it. */
    private data class SourceSnapshot(
        val index: SourceIndex? = null,
        val error: Throwable? = null,
    )

    /** Mutable counters accumulated while an operation runs. */
    private class Tally(var restored: Int = 0, var writes: Int = 0, var failures: Int = 0) {
        fun toResult() = ConsolidationResult(restored, writes, failures)
    }

    /** Per-source progress of a Vault Key backup, shaped for the onboarding UI. */
    data class VaultKeyBackupProgress(
        val sources: List<VaultKeyBackupSource>,
        val activeSourceIndex: Int,
        val completedSources: Int,
    ) {
        val activeSource: VaultKeyBackupSource get() = sources[activeSourceIndex]
        val totalSources: Int get() = sources.size
    }

    data class VaultKeyBackupSource(
        val label: String,
        val type: BackupSourceType,
    )

    /**
     * Outcome of [backupVaultKey]. Setup may only complete once [quorumReached] is true, which
     * requires at least one Nostr relay *and* one Brave Sync server to hold the Vault Key.
     */
    data class VaultKeyBackupResult(
        val relayQuorum: Boolean,
        val braveQuorum: Boolean,
        val successfulSourceIds: Set<String>,
        val failedSourceIds: Set<String>,
    ) {
        val quorumReached: Boolean get() = relayQuorum && braveQuorum
    }

    data class ConsolidationResult(val restored: Int, val writes: Int, val failures: Int)

    companion object {
        /** A non-forced [consolidate] run is skipped while the previous result is this recent. */
        const val FRESHNESS_MILLIS = 60 * 60 * 1000L

        /** States produced by attempting a write, which refresh `lastAttemptAtMillis`. */
        private val WRITE_ATTEMPT_STATES = setOf(
            AccountSourceBackupState.BACKING_UP,
            AccountSourceBackupState.BACKUP_FAILED,
            AccountSourceBackupState.WRITE_CONFIRMED,
        )
    }
}

/** Maps transport/validation failures to the coarse categories allowed in durable status. */
private fun Throwable.toCategory(): BackupErrorCategory = when (this) {
    is SocketTimeoutException -> BackupErrorCategory.TIMEOUT
    is IOException -> BackupErrorCategory.NETWORK_UNAVAILABLE
    is SecurityException -> BackupErrorCategory.AUTHENTICATION_FAILED
    is IllegalArgumentException -> BackupErrorCategory.REMOTE_PROTOCOL_ERROR
    is IllegalStateException -> BackupErrorCategory.REMOTE_REJECTED
    else -> BackupErrorCategory.UNKNOWN
}
