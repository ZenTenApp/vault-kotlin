package app.zenten.vault.ui.availability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppAccessPolicyTest {
    @Test
    fun `allows navigation when server time is valid or unavailable`() {
        assertFalse(AppAccessPolicy.evaluate(RemoteOperationReadiness.READY).canUseApp)
        assertTrue(AppAccessPolicy.evaluate(RemoteOperationReadiness.READY).copy(hasCompletedCheck = true).canUseApp)
        assertTrue(AppAccessPolicy.evaluate(RemoteOperationReadiness.TIME_UNVERIFIABLE).copy(hasCompletedCheck = true).canUseApp)
    }

    @Test
    fun `blocks navigation for known invalid prerequisites`() {
        val offline = AppAccessPolicy.evaluate(RemoteOperationReadiness.NO_VALIDATED_INTERNET)
        val incorrectTime = AppAccessPolicy.evaluate(RemoteOperationReadiness.DEVICE_TIME_INCORRECT)

        assertFalse(offline.canUseApp)
        assertEquals(setOf(AppAccessBlocker.INTERNET_UNAVAILABLE), offline.blockers)
        assertFalse(incorrectTime.canUseApp)
        assertEquals(setOf(AppAccessBlocker.DEVICE_TIME_INCORRECT), incorrectTime.blockers)
    }
}
