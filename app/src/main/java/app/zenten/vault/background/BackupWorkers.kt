package app.zenten.vault.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.VaultKeySession
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles either one associated account or all of them.
 *
 * The optional account ID selects targeted work. Without it, this worker runs a full
 * associated-account cycle, using the optional trigger to record why that cycle started.
 * Neither path touches the Vault Key record, which has its own flow in
 * [AccountConsolidationEngine.consolidateVaultKey].
 *
 * The VaultKey passphrase is read from the in-memory [VaultKeySession] at the start of the
 * run. It is never placed in the worker's input data, which WorkManager persists to disk.
 * If the process was restarted by WorkManager, the session is empty and the job fails closed;
 * the next unlocked app launch re-enqueues it.
 */
@HiltWorker
class AccountConsolidationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: AccountConsolidationEngine,
    private val remoteOperationPrecondition: RemoteOperationPrecondition,
    private val vaultKeySession: VaultKeySession,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val passphrase = vaultKeySession.snapshot() ?: return Result.failure()
        return try {
            when (remoteOperationPrecondition.check()) {
                RemoteOperationReadiness.READY,
                // A time-source outage does not prove that the device clock is incorrect.
                RemoteOperationReadiness.TIME_UNVERIFIABLE -> Unit
                RemoteOperationReadiness.NO_VALIDATED_INTERNET,
                // Never generate timestamped backup/authentication data with known bad time.
                RemoteOperationReadiness.DEVICE_TIME_INCORRECT -> return Result.retry()
            }

            val accountId = inputData.getLong(ACCOUNT_ID, NO_ACCOUNT_ID)
            if (accountId != NO_ACCOUNT_ID) {
                // Targeted cycles avoid changing statuses or records for unrelated accounts.
                engine.consolidateAccount(accountId, passphrase)
            } else {
                val trigger = inputData.getString(TRIGGER)
                    ?.let { triggerName -> runCatching { ConsolidationTrigger.valueOf(triggerName) }.getOrNull() }
                    ?: ConsolidationTrigger.STARTUP
                // A manual request is an explicit user action, so it bypasses the freshness check.
                engine.consolidate(passphrase, trigger, force = trigger == ConsolidationTrigger.MANUAL)
            }
            Result.success()
        } catch (exception: CancellationException) {
            // WorkManager deliberately stops work when replacing or cancelling it.
            throw exception
        } catch (_: Exception) {
            // The engine records recoverable source-level failures itself; an escaped exception
            // means this whole request did not finish and should be retried with backoff.
            Result.retry()
        } finally {
            passphrase.fill('\u0000')
        }
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
        const val TRIGGER = "trigger"
        private const val NO_ACCOUNT_ID = Long.MIN_VALUE
    }
}

/**
 * Reconciles the Vault Key record only.
 *
 * This is a separate worker from [AccountConsolidationWorker] because the Vault Key is published
 * under a different Nostr identity and must be retried on its own schedule. It does nothing when
 * no Vault Key is stored yet. Like the account worker, it takes its passphrase from the
 * in-memory session and fails closed when the session is locked.
 */
@HiltWorker
class VaultKeyConsolidationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: AccountConsolidationEngine,
    private val remoteOperationPrecondition: RemoteOperationPrecondition,
    private val vaultKeySession: VaultKeySession,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val passphrase = vaultKeySession.snapshot() ?: return Result.failure()
        return try {
            when (remoteOperationPrecondition.check()) {
                RemoteOperationReadiness.READY,
                // A time-source outage does not prove that the device clock is incorrect.
                RemoteOperationReadiness.TIME_UNVERIFIABLE -> Unit
                RemoteOperationReadiness.NO_VALIDATED_INTERNET,
                // Never generate timestamped backup/authentication data with known bad time.
                RemoteOperationReadiness.DEVICE_TIME_INCORRECT -> return Result.retry()
            }
            engine.consolidateVaultKey(passphrase, force = inputData.getBoolean(FORCE, false))
            Result.success()
        } catch (exception: CancellationException) {
            // WorkManager deliberately stops work when replacing or cancelling it.
            throw exception
        } catch (_: Exception) {
            // The engine records recoverable source-level failures itself; an escaped exception
            // means this whole request did not finish and should be retried with backoff.
            Result.retry()
        } finally {
            passphrase.fill('\u0000')
        }
    }

    companion object {
        const val FORCE = "force"
    }
}

/**
 * Enqueues one-time consolidation jobs.
 *
 * There is deliberately no periodic scheduling: without a stored passphrase, background work
 * can only run while an in-memory session is unlocked, so the app triggers a cycle after each
 * successful unlock and on explicit user actions.
 */
@Singleton
class WorkManagerBackupWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val vaultKeySession: VaultKeySession,
) : BackupWorkScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Runs the Vault Key and account cycles once after an unlock; freshness still applies. */
    override fun scheduleStartupConsolidation() {
        if (!vaultKeySession.isUnlocked.value) return
        val accountRequest = oneTimeRequest(
            Data.Builder().putString(AccountConsolidationWorker.TRIGGER, ConsolidationTrigger.STARTUP.name).build(),
        )
        workManager.enqueueUniqueWork(IMMEDIATE_CONSOLIDATION, ExistingWorkPolicy.APPEND_OR_REPLACE, accountRequest)
        workManager.enqueueUniqueWork(
            IMMEDIATE_VAULT_KEY_CONSOLIDATION,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            vaultKeyRequest(force = false),
        )
    }

    override fun scheduleConsolidation() {
        val request = oneTimeRequest(
            Data.Builder().putString(AccountConsolidationWorker.TRIGGER, ConsolidationTrigger.MANUAL.name).build(),
        )
        // Queue a follow-up cycle when another account is created while consolidation is running;
        // KEEP could discard that request after the running cycle had already captured inventory.
        workManager.enqueueUniqueWork(IMMEDIATE_CONSOLIDATION, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun scheduleAccountConsolidation(accountId: Long) {
        val request = oneTimeRequest(
            Data.Builder().putLong(AccountConsolidationWorker.ACCOUNT_ID, accountId).build(),
        )
        workManager.enqueueUniqueWork(
            "$ACCOUNT_CONSOLIDATION_PREFIX$accountId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /** Runs a Vault Key cycle now, bypassing freshness because the user asked for it. */
    override fun scheduleVaultKeyConsolidation() {
        workManager.enqueueUniqueWork(
            IMMEDIATE_VAULT_KEY_CONSOLIDATION,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            vaultKeyRequest(force = true),
        )
    }

    private fun oneTimeRequest(inputData: Data) = OneTimeWorkRequestBuilder<AccountConsolidationWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setInputData(inputData)
        .build()

    private fun vaultKeyRequest(force: Boolean) = OneTimeWorkRequestBuilder<VaultKeyConsolidationWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setInputData(Data.Builder().putBoolean(VaultKeyConsolidationWorker.FORCE, force).build())
        .build()

    companion object {
        const val IMMEDIATE_CONSOLIDATION = "account-consolidation-immediate"
        const val ACCOUNT_CONSOLIDATION_PREFIX = "account-consolidation-"
        const val IMMEDIATE_VAULT_KEY_CONSOLIDATION = "vault-key-consolidation-immediate"
    }
}
