package app.zenten.vault.data.kv

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.domain.BackupSourceConfig
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.domain.SourceAuthentication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AppStorageServiceTest {
    @Test
    fun persists_source_specific_non_secret_state() = runTest {
        val directory = Files.createTempDirectory("vault-datastore").toFile()
        try {
            val service = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            val source = BackupSourceConfig(
                "custom", "custom", BackupSourceType.NOSTR_RELAY, "wss://relay.example", 5,
                authentication = SourceAuthentication.NIP42_OPTIONAL,
            )
            assertFalse(service.setupComplete.first())
            service.setCreateMasterKeyStep(CreateMasterKeyStep.BACKUP_INTRO)
            assertEquals(CreateMasterKeyStep.BACKUP_INTRO, service.createMasterKeyStep.first())
            service.setSetupComplete()
            service.setCustomBackupSources(listOf(source))
            service.upsertPendingSourceWrite(PendingSourceWrite("10", "custom", PendingWriteReason.DIRECT_BACKUP))
            service.upsertAccountSourceStatus(AccountSourceStatus("10", "custom", AccountSourceBackupState.BACKUP_FAILED))
            service.markAccountLocallyBackedUp(10L)
            service.requireAccountLocalBackup(20L)
            service.upsertCachedAccountProfiles(listOf(CachedAccountProfile(10L, "Profile", 100L)))

            assertTrue(service.setupComplete.first())
            assertEquals(null, service.createMasterKeyStep.first())
            assertEquals(listOf(source), service.customBackupSources.first())
            assertEquals(1, service.pendingSourceWrites.first().size)
            assertEquals(AccountSourceBackupState.BACKUP_FAILED, service.accountSourceStatuses.first().single().state)
            assertEquals(setOf("10"), service.locallyBackedUpAccountIds.first())
            assertEquals(setOf("20"), service.pendingLocalBackupAccountIds.first())
            assertEquals(CachedAccountProfile(10L, "Profile", 100L), service.cachedAccountProfiles.first().single())
            service.markAccountLocallyBackedUp(20L)
            assertTrue(service.pendingLocalBackupAccountIds.first().isEmpty())

            service.clearPendingSourceWrite("10", "custom")
            assertTrue(service.pendingSourceWrites.first().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
