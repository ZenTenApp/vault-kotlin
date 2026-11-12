package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the stranded-vault reset keeps the user's data while clearing the state that blocks
 * recovery.
 *
 * The ordering assertion matters more than it looks: the routing flags must be cleared before the
 * records are set aside. The gate outranks the router, so an interrupted run that cleared flags
 * but did not quarantine still shows the recovery screen — nothing is orphaned. The opposite order
 * would leave the gate reporting onboarding while `setup_complete` still claimed otherwise, which
 * is the empty-vault presentation this service exists to remove.
 */
class StrandedVaultResetServiceTest {
    private val secureStorage = mockk<SecureStorageService>()
    private val appStorage = mockk<AppStorageService>()
    private val service = StrandedVaultResetService(secureStorage, appStorage)

    @Test
    fun reset_clears_routing_flags_then_quarantines_records() = runTest {
        // Recorded explicitly rather than with verifyOrder: the flag-clearing call is suspending,
        // and verifyOrder's block is not a coroutine scope.
        val order = mutableListOf<String>()
        coEvery { appStorage.clearStateForStrandedVaultReset() } answers {
            order += "clearFlags"
        }
        every { secureStorage.quarantineUnreadableRecords() } answers {
            order += "quarantine"
            2
        }

        service.reset()

        assertEquals(listOf("clearFlags", "quarantine"), order)
    }

    @Test
    fun reset_reports_how_many_records_were_set_aside() = runTest {
        every { secureStorage.quarantineUnreadableRecords() } returns 3
        coJustRun { appStorage.clearStateForStrandedVaultReset() }

        assertEquals(3, service.reset())
    }

    /**
     * Never deleting is the non-negotiable property. `clearAll` exists for test teardown but must
     * not be reachable from a user-facing reset, because it would destroy the only local copy of a
     * key whose original device is gone.
     */
    @Test
    fun reset_never_clears_all_storage() = runTest {
        every { secureStorage.quarantineUnreadableRecords() } returns 1
        coJustRun { appStorage.clearStateForStrandedVaultReset() }

        service.reset()

        verify(exactly = 0) { secureStorage.clearAll() }
    }

    /**
     * A reset that cannot write flags must fail rather than report success, so the screen can keep
     * the user on the recovery path instead of sending them into a still-blocked app.
     */
    @Test
    fun reset_failure_propagates() = runTest {
        every { secureStorage.quarantineUnreadableRecords() } returns 1
        coEvery { appStorage.clearStateForStrandedVaultReset() } throws IllegalStateException("disk")

        val result = runCatching { service.reset() }

        assertTrue(result.isFailure)
    }
}
