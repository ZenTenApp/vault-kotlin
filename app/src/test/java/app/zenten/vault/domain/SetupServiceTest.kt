package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupServiceTest {
    @Test
    fun quorum_failure_retains_vault_key_and_leaves_setup_incomplete() = runTest {
        val fixture = Fixture()
        coEvery { fixture.engine.backupVaultKey(vaultKey, any(), any()) } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = true,
            braveQuorum = false,
            successfulSourceIds = setOf("relay"),
            failedSourceIds = setOf("brave"),
        )

        val result = fixture.service.performSetup(vaultKey)

        assertFalse(result.isSuccess)
        verify(exactly = 1) { fixture.secureStorage.addVaultKey(vaultKey) }
        verify(exactly = 0) { fixture.secureStorage.removeVaultKey() }
        coVerify(exactly = 1) { fixture.appStorage.clearSetupComplete() }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun generated_vault_key_is_persisted_and_all_source_backups_are_attempted_without_completing_setup() = runTest {
        val fixture = Fixture()
        coEvery { fixture.engine.backupVaultKey(vaultKey, any(), any()) } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = false,
            braveQuorum = false,
            successfulSourceIds = emptySet(),
            failedSourceIds = setOf("relay", "brave"),
        )

        val result = fixture.service.persistGeneratedVaultKeyAndBackup(vaultKey, createFlowRelayIdentity)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().quorumReached)
        verify(exactly = 1) { fixture.secureStorage.addVaultKey(vaultKey) }
        coVerify(exactly = 1) { fixture.appStorage.clearSetupComplete() }
        coVerify(exactly = 1) { fixture.appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.BACKUP_INTRO) }
        coVerify(exactly = 1) {
            fixture.engine.backupVaultKey(vaultKey.copy(nostr = createFlowRelayIdentity), any(), any())
        }
        // The quorum outcome is recorded even when it is negative, so the last wizard step
        // cannot complete setup on a backup that never landed.
        coVerify(exactly = 1) { fixture.appStorage.setVaultKeyQuorumReached(false) }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun generated_backup_records_a_reached_quorum_for_the_final_wizard_step() = runTest {
        val fixture = Fixture()
        // The create flow backs up under the `vkbckp` identity, not the stored key's own.
        coEvery {
            fixture.engine.backupVaultKey(vaultKey.copy(nostr = createFlowRelayIdentity), any(), any())
        } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = true,
            braveQuorum = true,
            successfulSourceIds = setOf("relay", "brave"),
            failedSourceIds = emptySet(),
        )

        val state = fixture.service.persistGeneratedVaultKeyAndBackup(vaultKey, createFlowRelayIdentity).getOrThrow()

        assertTrue(state.quorumReached)
        coVerify(exactly = 1) { fixture.appStorage.setVaultKeyQuorumReached(true) }
        // Completion itself belongs to the last wizard step, not the backup attempt.
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun generated_setup_completion_is_refused_when_no_quorum_was_reached() = runTest {
        val fixture = Fixture()
        every { fixture.appStorage.vaultKeyQuorumReached } returns flowOf(false)

        val result = fixture.service.completeGeneratedVaultKeySetup()

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("quorum") == true)
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun generated_setup_completion_succeeds_only_after_a_recorded_quorum() = runTest {
        val fixture = Fixture()
        every { fixture.appStorage.vaultKeyQuorumReached } returns flowOf(true)

        assertTrue(fixture.service.completeGeneratedVaultKeySetup().isSuccess)

        coVerify(exactly = 1) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun retrying_the_generated_backup_records_the_new_quorum_and_leaves_completion_to_the_wizard() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getVaultKey() } returns vaultKey
        coEvery { fixture.engine.backupVaultKey(vaultKey, any(), any()) } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = true,
            braveQuorum = false,
            successfulSourceIds = setOf("relay"),
            failedSourceIds = setOf("brave"),
        )

        val result = fixture.service.retryGeneratedVaultKeyBackup()

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Brave") == true)
        coVerify(exactly = 1) { fixture.appStorage.setVaultKeyQuorumReached(false) }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun retry_uses_retained_vault_key_and_completes_after_both_quorums() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getVaultKey() } returns vaultKey
        coEvery { fixture.engine.backupVaultKey(vaultKey, any(), any()) } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = true,
            braveQuorum = true,
            successfulSourceIds = setOf("relay", "brave"),
            failedSourceIds = emptySet(),
        )

        assertTrue(fixture.service.retryVaultKeyBackup().isSuccess)

        coVerify(exactly = 1) { fixture.appStorage.setSetupComplete() }
        verify(exactly = 1) { fixture.scheduler.scheduleConsolidation() }
    }

    @Test
    fun associated_account_is_saved_before_remote_work_is_scheduled() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getVaultKey() } returns vaultKey
        every { fixture.appStorage.setupComplete } returns flowOf(true)
        val account = Account(20, "associated", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))

        assertTrue(fixture.service.addAccount(vaultKey, account).isSuccess)

        verifyOrder {
            fixture.secureStorage.addAccount(account)
            fixture.scheduler.scheduleAccountConsolidation(account.createdAt)
        }
    }

    @Test
    fun associated_account_is_rejected_when_vault_key_backup_quorum_is_incomplete() = runTest {
        val fixture = Fixture()
        every { fixture.appStorage.setupComplete } returns flowOf(false)
        val account = Account(20, "associated", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))

        val result = fixture.service.addAccount(vaultKey, account)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("quorum") == true)
        verify(exactly = 0) { fixture.secureStorage.addAccount(any()) }
        verify(exactly = 0) { fixture.scheduler.scheduleAccountConsolidation(any()) }
    }

    @Test
    fun device_time_failure_blocks_setup_backup_and_retains_the_local_vault_key() = runTest {
        val fixture = Fixture()
        coEvery { fixture.precondition.check() } returns RemoteOperationReadiness.DEVICE_TIME_INCORRECT

        val result = fixture.service.performSetup(vaultKey)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("time", ignoreCase = true) == true)
        verify(exactly = 1) { fixture.secureStorage.addVaultKey(vaultKey) }
        coVerify(exactly = 0) { fixture.engine.backupVaultKey(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun unvalidated_internet_blocks_retry_backup_before_the_engine_runs() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getVaultKey() } returns vaultKey
        coEvery { fixture.precondition.check() } returns RemoteOperationReadiness.NO_VALIDATED_INTERNET

        val result = fixture.service.retryVaultKeyBackup()

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { fixture.engine.backupVaultKey(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun generated_vault_key_is_persisted_but_remote_backup_is_skipped_when_prerequisites_block() = runTest {
        val fixture = Fixture()
        coEvery { fixture.precondition.check() } returns RemoteOperationReadiness.DEVICE_TIME_INCORRECT

        val result = fixture.service.persistGeneratedVaultKeyAndBackup(vaultKey, createFlowRelayIdentity)

        assertTrue(result.isSuccess)
        // A skipped backup is no evidence of coverage, so it clears any earlier quorum claim.
        assertFalse(result.getOrThrow().quorumReached)
        verify(exactly = 1) { fixture.secureStorage.addVaultKey(vaultKey) }
        coVerify(exactly = 1) { fixture.appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.BACKUP_INTRO) }
        coVerify(exactly = 1) { fixture.appStorage.setVaultKeyQuorumReached(false) }
        coVerify(exactly = 0) { fixture.engine.backupVaultKey(any(), any(), any()) }
    }

    @Test
    fun time_unverifiable_is_not_treated_as_bad_time() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getVaultKey() } returns vaultKey
        coEvery { fixture.precondition.check() } returns RemoteOperationReadiness.TIME_UNVERIFIABLE
        coEvery { fixture.engine.backupVaultKey(vaultKey, any(), any()) } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = true,
            braveQuorum = true,
            successfulSourceIds = setOf("relay", "brave"),
            failedSourceIds = emptySet(),
        )

        assertTrue(fixture.service.retryVaultKeyBackup().isSuccess)

        coVerify(exactly = 1) { fixture.engine.backupVaultKey(vaultKey, any(), any()) }
    }

    private class Fixture {
        val secureStorage = mockk<SecureStorageService>()
        val appStorage = mockk<AppStorageService>()
        val engine = mockk<AccountConsolidationEngine>()
        val precondition = mockk<RemoteOperationPrecondition>()
        val scheduler = mockk<BackupWorkScheduler>()
        val session = VaultKeySession(secureStorage)
        val service = SetupService(secureStorage, appStorage, engine, precondition, session, scheduler)

        init {
            // The passphrase is memory-only; tests unlock the session the same way the flows do.
            runBlocking { session.establish("pass".toCharArray()) }
            coEvery { precondition.check() } returns RemoteOperationReadiness.READY
            every { secureStorage.addVaultKey(any()) } just runs
            every { secureStorage.removeVaultKey() } just runs
            every { secureStorage.addAccount(any()) } just runs
            every { scheduler.scheduleConsolidation() } just runs
            every { scheduler.scheduleAccountConsolidation(any()) } just runs
            coJustRun { appStorage.clearSetupComplete() }
            coJustRun { appStorage.setSetupComplete() }
            coJustRun { appStorage.setCreateVaultKeyStep(any()) }
            coJustRun { appStorage.setVaultKeyQuorumReached(any()) }
        }
    }

    companion object {
        val createFlowRelayIdentity = NostrKeyPair("02".repeat(32), "03".repeat(32))
        val vaultKey = Account(
            createdAt = 1,
            zenTenKey = "vaultKey",
            nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
        )
    }
}
