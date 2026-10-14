package app.zenten.vault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.zenten.vault.background.WorkManagerBackupWorkScheduler
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
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
    @Inject lateinit var remoteOperationPrecondition: RemoteOperationPrecondition

    override fun onCreate() {
        super.onCreate()
        scheduler.schedulePeriodic()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            appStorage.clearLegacyBackupState()
            // The Vault Key and associated accounts are reconciled separately because they are
            // published under different Nostr identities. Neither is forced: both skip work
            // when their own previous cycle is still fresh. They run only when the shared
            // prerequisite policy permits timestamped remote data; otherwise the periodic
            // workers retry once connectivity and the clock are valid.
            when (remoteOperationPrecondition.check()) {
                RemoteOperationReadiness.READY,
                RemoteOperationReadiness.TIME_UNVERIFIABLE -> {
                    engine.consolidateVaultKey()
                    engine.consolidate(ConsolidationTrigger.STARTUP)
                }
                RemoteOperationReadiness.NO_VALIDATED_INTERNET,
                RemoteOperationReadiness.DEVICE_TIME_INCORRECT -> Unit
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
