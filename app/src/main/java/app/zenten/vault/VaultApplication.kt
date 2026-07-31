package app.zenten.vault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.zenten.vault.background.BackupReconcileWorker
import app.zenten.vault.domain.BackupSyncService
import app.zenten.vault.domain.StartupRetryScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VaultApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var startupRetryScheduler: StartupRetryScheduler
    @Inject lateinit var backupSyncService: BackupSyncService

    override fun onCreate() {
        super.onCreate()
        BackupReconcileWorker.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            startupRetryScheduler.runStartupRetries()
            backupSyncService.sync()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
