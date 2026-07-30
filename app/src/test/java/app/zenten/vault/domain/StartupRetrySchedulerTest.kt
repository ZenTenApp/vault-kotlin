package app.zenten.vault.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.data.kv.AppStorageService
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class StartupRetrySchedulerTest {
    @Test
    fun schedules_only_when_failed_backup_ids_exist() = runTest {
        fixture { storage, scheduler ->
            scheduler.runStartupRetries()
            assertEquals(0, schedulerDelegate.schedules)

            storage.setFailedBackupAccountIds(listOf(42))
            scheduler.runStartupRetries()
            assertEquals(1, schedulerDelegate.schedules)
        }
    }

    private lateinit var schedulerDelegate: CountingScheduler

    private suspend fun TestScope.fixture(block: suspend (AppStorageService, StartupRetryScheduler) -> Unit) {
        val directory = Files.createTempDirectory("vault-startup-retry").toFile()
        try {
            val storage = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            schedulerDelegate = CountingScheduler()
            block(storage, StartupRetryScheduler(storage, schedulerDelegate))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class CountingScheduler : RelayBackupRetryScheduler {
        var schedules = 0
        override fun schedule() { schedules++ }
    }
}
