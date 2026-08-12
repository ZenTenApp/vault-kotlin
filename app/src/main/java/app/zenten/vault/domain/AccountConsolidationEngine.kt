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

/** Runs every remote backup operation sequentially and maintains source-specific durable state. */
class AccountConsolidationEngine(
    private val sourceRepository: BackupSourceRepository,
    private val sourceFactory: BackupSourceFactory,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val operationMutex = Mutex()

    suspend fun backupMaster(master: Account): MasterBackupResult = operationMutex.withLock {
        val successes = mutableSetOf<String>()
        val failures = mutableSetOf<String>()
        enabledSources().forEach { config ->
            val source = sourceFactory.create(config)
            setStatus(BackupAccountId.Master, config, AccountSourceBackupState.CHECKING)
            val alreadyPresent = source.index(master).getOrNull()?.ids?.contains(BackupAccountId.Master) == true
            if (alreadyPresent) {
                successes += config.id
                setStatus(BackupAccountId.Master, config, AccountSourceBackupState.PRESENT, checked = true, successful = true)
                appStorage.clearPendingSourceWrite(BackupAccountId.MASTER_VALUE, config.id)
            } else {
                setStatus(BackupAccountId.Master, config, AccountSourceBackupState.BACKING_UP)
                source.write(master, BackupAccountId.Master, master).fold(
                    onSuccess = {
                        successes += config.id
                        setStatus(BackupAccountId.Master, config, AccountSourceBackupState.WRITE_CONFIRMED, successful = true)
                        appStorage.clearPendingSourceWrite(BackupAccountId.MASTER_VALUE, config.id)
                    },
                    onFailure = { error ->
                        failures += config.id
                        setStatus(BackupAccountId.Master, config, AccountSourceBackupState.BACKUP_FAILED, error = error)
                        appStorage.upsertPendingSourceWrite(
                            PendingSourceWrite(BackupAccountId.MASTER_VALUE, config.id, PendingWriteReason.MASTER_QUORUM, 1, clock.millis()),
                        )
                    },
                )
            }
        }
        val configs = enabledSources().associateBy { it.id }
        val relayQuorum = successes.any { configs[it]?.type == BackupSourceType.NOSTR_RELAY && configs[it]?.countsTowardQuorum == true }
        val braveQuorum = successes.any { configs[it]?.type == BackupSourceType.BRAVE_SYNC && configs[it]?.countsTowardQuorum == true }
        MasterBackupResult(relayQuorum, braveQuorum, successes, failures)
    }

    suspend fun backupAccount(accountId: Long): AccountBackupResult = operationMutex.withLock {
        val master = requireNotNull(secureStorage.getMasterAccount()) { "Master account is unavailable" }
        val account = requireNotNull(secureStorage.getAccountById(accountId)) { "Account is unavailable" }
        val id = BackupAccountId.associated(accountId)
        var succeeded = 0
        var failed = 0
        enabledSources().forEach { config ->
            val source = sourceFactory.create(config)
            setStatus(id, config, AccountSourceBackupState.BACKING_UP)
            source.write(master, id, account).fold(
                onSuccess = {
                    succeeded++
                    setStatus(id, config, AccountSourceBackupState.WRITE_CONFIRMED, successful = true)
                    appStorage.clearPendingSourceWrite(id.value, config.id)
                },
                onFailure = { error ->
                    failed++
                    setStatus(id, config, AccountSourceBackupState.BACKUP_FAILED, error = error)
                    appStorage.upsertPendingSourceWrite(PendingSourceWrite(id.value, config.id, PendingWriteReason.DIRECT_BACKUP, 1, clock.millis()))
                },
            )
        }
        AccountBackupResult(succeeded, failed)
    }

    suspend fun retryPending(): AccountBackupResult = operationMutex.withLock {
        val master = secureStorage.getMasterAccount() ?: return@withLock AccountBackupResult(0, 0)
        val configs = enabledSources().associateBy { it.id }
        var succeeded = 0
        var failed = 0
        appStorage.pendingSourceWrites.first().forEach { pending ->
            val config = configs[pending.sourceId] ?: return@forEach
            val id = BackupAccountId.parse(pending.accountId) ?: return@forEach
            val account = if (id.isMaster) master else id.createdAt?.let(secureStorage::getAccountById)
            if (account == null) {
                appStorage.clearPendingSourceWrite(id.value, config.id)
                return@forEach
            }
            setStatus(id, config, AccountSourceBackupState.BACKING_UP)
            sourceFactory.create(config).write(master, id, account).fold(
                onSuccess = {
                    succeeded++
                    setStatus(id, config, AccountSourceBackupState.WRITE_CONFIRMED, successful = true)
                    appStorage.clearPendingSourceWrite(id.value, config.id)
                },
                onFailure = { error ->
                    failed++
                    setStatus(id, config, AccountSourceBackupState.BACKUP_FAILED, error = error)
                    appStorage.upsertPendingSourceWrite(pending.copy(reason = PendingWriteReason.RETRY, attemptCount = pending.attemptCount + 1, lastAttemptAtMillis = clock.millis()))
                },
            )
        }
        AccountBackupResult(succeeded, failed)
    }

    suspend fun consolidate(trigger: ConsolidationTrigger, force: Boolean = false): ConsolidationResult? = operationMutex.withLock {
        val now = clock.millis()
        if (!force) {
            val last = appStorage.lastConsolidationCompletedAtMillis.first()
            val pending = appStorage.pendingSourceWrites.first()
            if (last != null && now - last < FRESHNESS_MILLIS && pending.isEmpty()) return@withLock null
        }
        val master = secureStorage.getMasterAccount() ?: return@withLock null
        val cycleId = UUID.randomUUID().toString()
        val started = clock.millis()
        val sources = enabledSources()
        val indexes = linkedMapOf<String, SourceIndex>()
        val indexFailures = linkedMapOf<String, Throwable>()
        var restored = 0
        var writes = 0
        var failures = 0

        // Phase 1: fully inspect and restore from each trusted source before any repair begins.
        sources.forEach { config ->
            checkpoint(cycleId, trigger, ConsolidationPhase.RESTORE, config.id, null, started)
            val source = sourceFactory.create(config)
            localInventory(master).keys.forEach { setStatus(it, config, AccountSourceBackupState.CHECKING) }
            source.index(master).fold(
                onSuccess = { index ->
                    indexes[config.id] = index
                    val localBefore = localInventory(master)
                    localBefore.keys.forEach { id ->
                        setStatus(id, config, if (id in index.ids) AccountSourceBackupState.PRESENT else AccountSourceBackupState.MISSING, checked = true)
                    }
                    if (config.restoreEnabled && config.trustedForRestore) {
                        index.ids.filterNot { it.isMaster || it in localBefore }.forEach { id ->
                            val candidates = index.candidates[id].orEmpty().sortedByDescending { it.sortOrder }
                            val recovered = candidates.firstNotNullOfOrNull { candidate -> source.restoreCandidate(master, candidate).getOrNull() }
                            if (recovered != null) {
                                secureStorage.addAccount(recovered)
                                restored++
                                setStatus(id, config, AccountSourceBackupState.PRESENT, checked = true)
                            }
                        }
                    }
                },
                onFailure = { error ->
                    failures++
                    indexFailures[config.id] = error
                    localInventory(master).keys.forEach { setStatus(it, config, AccountSourceBackupState.UNKNOWN, checked = true, error = error) }
                },
            )
        }

        // Backfill late discoveries against every retained phase-1 index before repair starts.
        val completeInventory = localInventory(master)
        sources.forEach { config ->
            val index = indexes[config.id]
            completeInventory.keys.forEach { id ->
                when {
                    index != null -> setStatus(id, config, if (id in index.ids) AccountSourceBackupState.PRESENT else AccountSourceBackupState.MISSING, checked = true)
                    else -> setStatus(id, config, AccountSourceBackupState.UNKNOWN, checked = true, error = indexFailures[config.id])
                }
            }
        }

        // Phase 2: use retained phase-1 indexes, then verify each destination once after writes.
        sources.forEach { config ->
            val initial = indexes[config.id] ?: return@forEach
            val source = sourceFactory.create(config)
            val known = initial.ids.toMutableSet()
            val acknowledgedWrites = mutableSetOf<BackupAccountId>()
            val failedWrites = mutableSetOf<BackupAccountId>()
            val inventory = localInventory(master)
            inventory.forEach accountLoop@{ (id, account) ->
                if (id in known) return@accountLoop
                checkpoint(cycleId, trigger, ConsolidationPhase.BACKUP, config.id, id.value, started)
                setStatus(id, config, AccountSourceBackupState.BACKING_UP)
                source.write(master, id, account).fold(
                    onSuccess = {
                        writes++
                        known += id
                        acknowledgedWrites += id
                        setStatus(id, config, AccountSourceBackupState.WRITE_CONFIRMED, successful = true)
                        appStorage.clearPendingSourceWrite(id.value, config.id)
                    },
                    onFailure = { error ->
                        failures++
                        failedWrites += id
                        setStatus(id, config, AccountSourceBackupState.BACKUP_FAILED, error = error)
                        appStorage.upsertPendingSourceWrite(PendingSourceWrite(id.value, config.id, PendingWriteReason.CONSOLIDATION, 1, clock.millis()))
                    },
                )
            }
            source.index(master).fold(
                onSuccess = { verified ->
                    localInventory(master).keys.forEach { id ->
                        when {
                            id in verified.ids -> {
                                setStatus(id, config, AccountSourceBackupState.PRESENT, checked = true, successful = true)
                                appStorage.clearPendingSourceWrite(id.value, config.id)
                            }
                            id in failedWrites -> Unit // Preserve the write failure and its pending entry.
                            id in acknowledgedWrites -> {
                                val error = IllegalStateException("Source read-back did not confirm the acknowledged write")
                                failures++
                                setStatus(id, config, AccountSourceBackupState.BACKUP_FAILED, checked = true, error = error)
                                appStorage.upsertPendingSourceWrite(
                                    PendingSourceWrite(id.value, config.id, PendingWriteReason.CONSOLIDATION, 1, clock.millis()),
                                )
                            }
                            else -> {
                                // It was in the retained phase-1 snapshot but disappeared before
                                // read-back. Record absence and force a later repair cycle.
                                setStatus(id, config, AccountSourceBackupState.MISSING, checked = true)
                                appStorage.upsertPendingSourceWrite(
                                    PendingSourceWrite(id.value, config.id, PendingWriteReason.CONSOLIDATION, 1, clock.millis()),
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    failures++
                    acknowledgedWrites.forEach { id ->
                        setStatus(id, config, AccountSourceBackupState.UNKNOWN, checked = true, error = error)
                        appStorage.upsertPendingSourceWrite(
                            PendingSourceWrite(id.value, config.id, PendingWriteReason.CONSOLIDATION, 1, clock.millis()),
                        )
                    }
                },
            )
        }
        appStorage.markConsolidationCompleted(clock.millis())
        ConsolidationResult(restored, writes, failures)
    }

    private suspend fun enabledSources() = sourceRepository.current().filter { it.enabled }

    private fun localInventory(master: Account): LinkedHashMap<BackupAccountId, Account> = linkedMapOf<BackupAccountId, Account>().apply {
        put(BackupAccountId.Master, master)
        secureStorage.getAccounts().forEach { put(BackupAccountId.associated(it.createdAt), it) }
    }

    private suspend fun checkpoint(
        cycleId: String,
        trigger: ConsolidationTrigger,
        phase: ConsolidationPhase,
        sourceId: String?,
        accountId: String?,
        started: Long,
    ) = appStorage.setConsolidationCheckpoint(
        ConsolidationCheckpoint(cycleId, trigger, phase, sourceId, accountId, started, clock.millis()),
    )

    private suspend fun setStatus(
        id: BackupAccountId,
        source: BackupSourceConfig,
        state: AccountSourceBackupState,
        checked: Boolean = false,
        successful: Boolean = false,
        error: Throwable? = null,
    ) {
        val now = clock.millis()
        val previous = appStorage.accountSourceStatuses.first().firstOrNull { it.accountId == id.value && it.sourceId == source.id }
        appStorage.upsertAccountSourceStatus(
            AccountSourceStatus(
                accountId = id.value,
                sourceId = source.id,
                state = state,
                lastCheckedAtMillis = if (checked) now else previous?.lastCheckedAtMillis,
                lastSuccessfulBackupAtMillis = if (successful) now else previous?.lastSuccessfulBackupAtMillis,
                lastAttemptAtMillis = if (state == AccountSourceBackupState.BACKING_UP || state == AccountSourceBackupState.BACKUP_FAILED || state == AccountSourceBackupState.WRITE_CONFIRMED) now else previous?.lastAttemptAtMillis,
                errorCategory = error?.toCategory(),
            ),
        )
    }

    data class MasterBackupResult(
        val relayQuorum: Boolean,
        val braveQuorum: Boolean,
        val successfulSourceIds: Set<String>,
        val failedSourceIds: Set<String>,
    ) {
        val quorumReached: Boolean get() = relayQuorum && braveQuorum
    }

    data class AccountBackupResult(val succeeded: Int, val failed: Int)
    data class ConsolidationResult(val restored: Int, val writes: Int, val failures: Int)

    companion object {
        const val FRESHNESS_MILLIS = 60 * 60 * 1000L
    }
}

private fun Throwable.toCategory(): BackupErrorCategory = when (this) {
    is SocketTimeoutException -> BackupErrorCategory.TIMEOUT
    is IOException -> BackupErrorCategory.NETWORK_UNAVAILABLE
    is SecurityException -> BackupErrorCategory.AUTHENTICATION_FAILED
    is IllegalArgumentException -> BackupErrorCategory.REMOTE_PROTOCOL_ERROR
    is IllegalStateException -> BackupErrorCategory.REMOTE_REJECTED
    else -> BackupErrorCategory.UNKNOWN
}
