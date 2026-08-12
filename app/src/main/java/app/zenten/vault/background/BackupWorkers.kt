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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Compatibility path for account/pending work queued by older app versions. */
@HiltWorker
class AccountBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: AccountConsolidationEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        engine.consolidate(ConsolidationTrigger.MANUAL, force = true)
        Result.success()
    }.getOrElse { Result.retry() }
}

@HiltWorker
class AccountConsolidationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: AccountConsolidationEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        val trigger = inputData.getString(TRIGGER)?.let { runCatching { ConsolidationTrigger.valueOf(it) }.getOrNull() }
            ?: ConsolidationTrigger.PERIODIC
        engine.consolidate(trigger, force = trigger == ConsolidationTrigger.MANUAL)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object { const val TRIGGER = "trigger" }
}

@Singleton
class WorkManagerBackupWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupWorkScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleConsolidation() {
        val request = OneTimeWorkRequestBuilder<AccountConsolidationWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(AccountConsolidationWorker.TRIGGER, ConsolidationTrigger.MANUAL.name).build())
            .build()
        // Queue a follow-up cycle when another account is created while consolidation is running;
        // KEEP could discard that request after the running cycle had already captured inventory.
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_CONSOLIDATION, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<AccountConsolidationWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_CONSOLIDATION,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val IMMEDIATE_CONSOLIDATION = "account-consolidation-immediate"
        const val PERIODIC_CONSOLIDATION = "account-consolidation-periodic"
    }
}
