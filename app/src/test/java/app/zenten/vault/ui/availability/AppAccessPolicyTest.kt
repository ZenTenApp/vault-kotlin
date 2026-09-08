package app.zenten.vault.ui.availability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppAccessPolicyTest {
    @Test
    fun `allows the app only with verified internet and automatic time`() {
        assertTrue(AppAccessPolicy.evaluate(hasValidatedInternet = true, automaticTimeEnabled = true).canUseApp)
    }

    @Test
    fun `reports every unmet access requirement`() {
        val state = AppAccessPolicy.evaluate(hasValidatedInternet = false, automaticTimeEnabled = false)

        assertFalse(state.canUseApp)
        assertEquals(
            setOf(AppAccessBlocker.INTERNET_UNAVAILABLE, AppAccessBlocker.AUTOMATIC_TIME_DISABLED),
            state.blockers,
        )
    }
}
