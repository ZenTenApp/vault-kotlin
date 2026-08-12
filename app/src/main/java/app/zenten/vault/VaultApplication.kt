package app.zenten.vault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.zenten.vault.background.WorkManagerBackupWorkScheduler
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.domain.AccountConsolidationEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VaultApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: WorkManagerBackupWorkScheduler
    @Inject lateinit var engine: AccountConsolidationEngine
    @Inject lateinit var appStorage: AppStorageService

    override fun onCreate() {
        super.onCreate()
        scheduler.schedulePeriodic()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            appStorage.clearLegacyBackupState()
            engine.consolidate(ConsolidationTrigger.STARTUP)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
