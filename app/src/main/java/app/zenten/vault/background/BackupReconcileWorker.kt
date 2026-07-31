package app.zenten.vault.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zenten.vault.domain.BackupSyncService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit

@HiltWorker
class BackupReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncService: BackupSyncService,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        syncService.sync()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_WORK_NAME = "backup-reconciliation"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupReconcileWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
