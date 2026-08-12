package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupServiceTest {
    @Test
    fun quorum_failure_retains_master_and_leaves_setup_incomplete() = runTest {
        val fixture = Fixture()
        coEvery { fixture.engine.backupMaster(master) } returns AccountConsolidationEngine.MasterBackupResult(
            relayQuorum = true,
            braveQuorum = false,
            successfulSourceIds = setOf("relay"),
            failedSourceIds = setOf("brave"),
        )

        val result = fixture.service.performSetup(master)

        assertFalse(result.isSuccess)
        verify(exactly = 1) { fixture.secureStorage.addMasterAccount(master) }
        verify(exactly = 0) { fixture.secureStorage.removeMasterAccount() }
        coVerify(exactly = 1) { fixture.appStorage.clearSetupComplete() }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
    }

    @Test
    fun retry_uses_retained_master_and_completes_after_both_quorums() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getMasterAccount() } returns master
        coEvery { fixture.engine.backupMaster(master) } returns AccountConsolidationEngine.MasterBackupResult(
            relayQuorum = true,
            braveQuorum = true,
            successfulSourceIds = setOf("relay", "brave"),
            failedSourceIds = emptySet(),
        )

        assertTrue(fixture.service.retryMasterBackup().isSuccess)

        coVerify(exactly = 1) { fixture.appStorage.setSetupComplete() }
        verify(exactly = 1) { fixture.scheduler.scheduleConsolidation() }
    }

    @Test
    fun associated_account_is_saved_before_remote_work_is_scheduled() = runTest {
        val fixture = Fixture()
        every { fixture.secureStorage.getMasterAccount() } returns master
        every { fixture.appStorage.setupComplete } returns flowOf(true)
        val account = Account(20, "associated", "pass")

        assertTrue(fixture.service.addAccount(master, account).isSuccess)

        verifyOrder {
            fixture.secureStorage.addAccount(account)
            fixture.scheduler.scheduleConsolidation()
        }
    }

    @Test
    fun associated_account_is_rejected_when_master_backup_quorum_is_incomplete() = runTest {
        val fixture = Fixture()
        every { fixture.appStorage.setupComplete } returns flowOf(false)
        val account = Account(20, "associated", "pass")

        val result = fixture.service.addAccount(master, account)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("quorum") == true)
        verify(exactly = 0) { fixture.secureStorage.addAccount(any()) }
        verify(exactly = 0) { fixture.scheduler.scheduleConsolidation() }
    }

    private class Fixture {
        val secureStorage = mockk<SecureStorageService>()
        val appStorage = mockk<AppStorageService>()
        val engine = mockk<AccountConsolidationEngine>()
        val scheduler = mockk<BackupWorkScheduler>()
        val service = SetupService(secureStorage, appStorage, engine, scheduler)

        init {
            every { secureStorage.addMasterAccount(any()) } just runs
            every { secureStorage.removeMasterAccount() } just runs
            every { secureStorage.addAccount(any()) } just runs
            every { scheduler.scheduleConsolidation() } just runs
            coJustRun { appStorage.clearSetupComplete() }
            coJustRun { appStorage.setSetupComplete() }
        }
    }

    companion object {
        val master = Account(
            createdAt = 1,
            zenTenKey = "master",
            zenTenPass = "pass",
            nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
        )
    }
}
