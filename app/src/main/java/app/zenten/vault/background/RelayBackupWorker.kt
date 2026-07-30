package app.zenten.vault.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zenten.vault.domain.RelayBackupRetryScheduler
import app.zenten.vault.domain.RelayBackupRetryService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.concurrent.TimeUnit

@HiltWorker
class RelayBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val retryService: RelayBackupRetryService,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val summary = retryService.retryFailedBackups()
        // WorkManager retries only when work remains unsatisfied. Missing IDs are pruned by the service.
        return if (summary.attempted > summary.succeeded) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "relay-backup-retry"
        fun request() = OneTimeWorkRequestBuilder<RelayBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
    }
}

class WorkManagerRelayBackupRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : RelayBackupRetryScheduler {
    override fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RelayBackupWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            RelayBackupWorker.request(),
        )
    }
}
