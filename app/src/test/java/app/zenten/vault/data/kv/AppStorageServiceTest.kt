package app.zenten.vault.data.kv

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.domain.BackupSourceConfig
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.domain.SourceAuthentication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AppStorageServiceTest {
    @Test
    fun pending_imported_vault_key_backup_is_durable_and_cleared_by_every_setup_terminal() = runTest {
        val directory = Files.createTempDirectory("vault-datastore").toFile()
        try {
            val service = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            assertFalse(service.importedVaultKeySetupPending.first())

            // Persisted in DataStore, so a later process reads the same pending state.
            service.requireImportedVaultKeyBackup()
            assertTrue(service.importedVaultKeySetupPending.first())

            service.setSetupComplete()
            assertFalse(service.importedVaultKeySetupPending.first())

            // Restarting setup drops the previous attempt's pending state with its quorum claim.
            service.requireImportedVaultKeyBackup()
            assertTrue(service.importedVaultKeySetupPending.first())
            service.clearSetupComplete()
            assertFalse(service.importedVaultKeySetupPending.first())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * The stranded-vault reset must clear every flag that outranks onboarding, and keep the
     * device-local configuration and history it has no reason to drop.
     *
     * Written against a real DataStore rather than a mock so the actual key list is exercised.
     * The previous reset was only tested through a mocked `AppStorageService`, which meant the
     * one thing that could be wrong — which keys are removed — was never asserted.
     */
    @Test
    fun stranded_vault_reset_clears_routing_state_and_keeps_device_local_history() = runTest {
        val directory = Files.createTempDirectory("vault-datastore").toFile()
        try {
            val service = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            service.setSetupComplete()
            service.setVaultKeyQuorumReached(true)
            service.requireImportedVaultKeyBackup()
            service.setCreateVaultKeyStep(CreateVaultKeyStep.CONFIRM_BACKUP)
            service.setSecretCombinationsViewStep(SecretCombinationsViewStep.SHOW)
            service.setVaultKeyPasteGuard(attempts = 2, blockedUntilMillis = 99_000L)
            service.requireAccountLocalBackup(10L)
            service.markAccountLocallyBackedUp(20L)

            // Device-local history that must survive: it stays valid after recovery.
            val source = BackupSourceConfig(
                "custom", "custom", BackupSourceType.NOSTR_RELAY, "wss://relay.example", 5,
                authentication = SourceAuthentication.NIP42_OPTIONAL,
            )
            service.setCustomBackupSources(listOf(source))
            service.upsertAccountSourceStatus(
                AccountSourceStatus("10", "custom", AccountSourceBackupState.BACKUP_FAILED),
            )
            service.markConsolidationCompleted(42L)

            service.clearStateForStrandedVaultReset()

            assertFalse(service.setupComplete.first())
            assertFalse(service.vaultKeyQuorumReached.first())
            assertFalse(service.importedVaultKeySetupPending.first())
            assertNull(service.createVaultKeyStep.first())
            // The review marker is checked before setup completion by the launch router, so it
            // would otherwise reopen the review flow instead of reaching onboarding.
            assertNull(service.secretCombinationsViewStep.first())
            assertEquals(0, service.vaultKeyPasteAttempts.first())
            assertNull(service.vaultKeyPasteBlockedUntilMillis.first())
            assertTrue(service.pendingLocalBackupAccountIds.first().isEmpty())
            assertTrue(service.locallyBackedUpAccountIds.first().isEmpty())

            // Device-local configuration and history stay valid after recovery, so they are kept.
            assertEquals(listOf(source), service.customBackupSources.first())
            assertEquals(1, service.accountSourceStatuses.first().size)
            assertEquals(42L, service.lastConsolidationCompletedAtMillis.first())
        } finally {
            directory.deleteRecursively()
        }
    }

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
            // The default round count is owned by SshKeyCodec; reference it so this
            // persistence test does not silently pin a stale value.
            assertEquals(SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS, service.sshKdfRounds.first())
            service.setSshKdfRounds(256)
            assertEquals(256, service.sshKdfRounds.first())
            // A value below the generation floor is no longer selectable, so it must be refused
            // outright rather than persisted and later clamped to a different number.
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.setSshKdfRounds(64) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.setSshKdfRounds(16_384) } }
            service.setCreateVaultKeyStep(CreateVaultKeyStep.BACKUP_INTRO)
            assertEquals(CreateVaultKeyStep.BACKUP_INTRO, service.createVaultKeyStep.first())
            service.setCreateVaultKeyStep(CreateVaultKeyStep.CONFIRM_BACKUP)
            assertEquals(CreateVaultKeyStep.CONFIRM_BACKUP, service.createVaultKeyStep.first())
            service.setCreateVaultKeyStep(CreateVaultKeyStep.SECRET_COMBINATIONS)
            assertEquals(CreateVaultKeyStep.SECRET_COMBINATIONS, service.createVaultKeyStep.first())
            service.setVaultKeyQuorumReached(true)
            service.setSetupComplete()
            service.setCustomBackupSources(listOf(source))
            service.upsertPendingSourceWrite(PendingSourceWrite("10", "custom", PendingWriteReason.DIRECT_BACKUP))
            service.upsertAccountSourceStatus(AccountSourceStatus("10", "custom", AccountSourceBackupState.BACKUP_FAILED))
            service.markAccountLocallyBackedUp(10L)
            service.requireAccountLocalBackup(20L)

            assertTrue(service.setupComplete.first())
            assertTrue(service.vaultKeyQuorumReached.first())
            assertEquals(null, service.createVaultKeyStep.first())
            assertEquals(listOf(source), service.customBackupSources.first())
            assertEquals(1, service.pendingSourceWrites.first().size)
            assertEquals(AccountSourceBackupState.BACKUP_FAILED, service.accountSourceStatuses.first().single().state)
            assertEquals(setOf("10"), service.locallyBackedUpAccountIds.first())
            assertEquals(setOf("20"), service.pendingLocalBackupAccountIds.first())
            service.markAccountLocallyBackedUp(20L)
            assertTrue(service.pendingLocalBackupAccountIds.first().isEmpty())

            service.clearPendingSourceWrite("10", "custom")
            assertTrue(service.pendingSourceWrites.first().isEmpty())

            // Restarting setup must not inherit the previous attempt's backup claim.
            service.clearSetupComplete()
            assertFalse(service.vaultKeyQuorumReached.first())
        } finally {
            directory.deleteRecursively()
        }
    }
}
