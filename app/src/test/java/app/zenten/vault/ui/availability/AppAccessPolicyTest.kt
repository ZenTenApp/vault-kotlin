package app.zenten.vault.ui.availability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppAccessPolicyTest {
    @Test
    fun `allows the app when verified internet and server time agree`() {
        assertTrue(AppAccessPolicy.evaluate(hasValidatedInternet = true, clockSkewMillis = 120_000).canUseApp)
    }

    @Test
    fun `blocks a clock more than two minutes from server time`() {
        val state = AppAccessPolicy.evaluate(hasValidatedInternet = true, clockSkewMillis = 120_001)

        assertFalse(state.canUseApp)
        assertEquals(setOf(AppAccessBlocker.DEVICE_TIME_INCORRECT), state.blockers)
    }

    @Test
    fun `reports network and clock failures together`() {
        val state = AppAccessPolicy.evaluate(hasValidatedInternet = false, clockSkewMillis = -120_001)

        assertFalse(state.canUseApp)
        assertEquals(
            setOf(AppAccessBlocker.INTERNET_UNAVAILABLE, AppAccessBlocker.DEVICE_TIME_INCORRECT),
            state.blockers,
        )
    }
}
