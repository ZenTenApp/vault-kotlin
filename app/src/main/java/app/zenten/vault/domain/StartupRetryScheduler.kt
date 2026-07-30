package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import kotlinx.coroutines.flow.first

/** Schedules unique background retry work only when durable relay failures exist. */
class StartupRetryScheduler(
    private val appStorage: AppStorageService,
    private val retryScheduler: RelayBackupRetryScheduler,
) {
    suspend fun runStartupRetries() {
        if (appStorage.failedBackupAccountIds.first().isNotEmpty()) retryScheduler.schedule()
    }
}
