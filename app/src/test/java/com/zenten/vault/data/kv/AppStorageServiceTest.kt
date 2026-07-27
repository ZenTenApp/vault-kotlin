package com.zenten.vault.data.kv

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zenten.vault.data.model.AppServerConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AppStorageServiceTest {
    @Test
    fun persists_non_secret_settings_and_deduplicates_failed_backup_ids() = runTest {
        val directory = Files.createTempDirectory("vault-datastore").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "settings.preferences_pb") }
            val service = AppStorageService(store)

            assertFalse(service.setupComplete.first())
            assertNull(service.appServerConfig.first())
            assertEquals(emptyList<Long>(), service.failedBackupAccountIds.first())

            service.setSetupComplete()
            service.setAppServerConfig(AppServerConfig(listOf("wss://relay.example")))
            service.setFailedBackupAccountIds(listOf(9, 3, 9))

            assertTrue(service.setupComplete.first())
            assertEquals(listOf("wss://relay.example"), service.appServerConfig.first()!!.relays)
            assertEquals(listOf(9L, 3L), service.failedBackupAccountIds.first())

            service.clearSetupComplete()
            service.clearFailedBackupAccountIds()
            assertFalse(service.setupComplete.first())
            assertEquals(emptyList<Long>(), service.failedBackupAccountIds.first())
        } finally {
            directory.deleteRecursively()
        }
    }
}
