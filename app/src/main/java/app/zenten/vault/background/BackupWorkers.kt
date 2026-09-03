package app.zenten.vault.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.BackupWorkScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles either one associated account or the entire vault.
 *
 * The optional account ID selects targeted work. Without it, this worker runs a full-vault
 * cycle, using the optional trigger to record why that cycle started.
 */
@HiltWorker
class AccountConsolidationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: AccountConsolidationEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val accountId = inputData.getLong(ACCOUNT_ID, NO_ACCOUNT_ID)
        if (accountId != NO_ACCOUNT_ID) {
            // Targeted cycles avoid changing statuses or records for unrelated accounts.
            engine.consolidateAccount(accountId)
        } else {
            val trigger = inputData.getString(TRIGGER)
                ?.let { triggerName -> runCatching { ConsolidationTrigger.valueOf(triggerName) }.getOrNull() }
                ?: ConsolidationTrigger.PERIODIC
            engine.consolidate(trigger, force = trigger == ConsolidationTrigger.MANUAL)
        }
        Result.success()
    } catch (exception: CancellationException) {
        // WorkManager deliberately stops work when replacing or cancelling it.
        throw exception
    } catch (_: Exception) {
        // The engine records recoverable source-level failures itself; an escaped exception
        // means this whole request did not finish and should be retried with backoff.
        Result.retry()
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
        const val TRIGGER = "trigger"
        private const val NO_ACCOUNT_ID = Long.MIN_VALUE
    }
}

@Singleton
class WorkManagerBackupWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
) : BackupWorkScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

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

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<AccountConsolidationWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_CONSOLIDATION,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun oneTimeRequest(inputData: Data) = OneTimeWorkRequestBuilder<AccountConsolidationWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setInputData(inputData)
        .build()

    companion object {
        const val IMMEDIATE_CONSOLIDATION = "account-consolidation-immediate"
        const val PERIODIC_CONSOLIDATION = "account-consolidation-periodic"
        const val ACCOUNT_CONSOLIDATION_PREFIX = "account-consolidation-"
    }
}
