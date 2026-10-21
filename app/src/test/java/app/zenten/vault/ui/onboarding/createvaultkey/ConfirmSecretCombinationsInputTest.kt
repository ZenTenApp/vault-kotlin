package app.zenten.vault.ui.onboarding.createvaultkey

import app.zenten.vault.ui.components.isCombinationSegmentIncorrect
import app.zenten.vault.ui.components.sanitizeCombinationSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmSecretCombinationsInputTest {

    @Test
    fun sanitize_keepsOnlyFourDigits() {
        assertEquals("1234", sanitizeCombinationSegment("1a23-45"))
    }

    @Test
    fun partialExpectedPrefix_isNotAnError() {
        assertFalse(isCombinationSegmentIncorrect(expected = 1234, value = ""))
        assertFalse(isCombinationSegmentIncorrect(expected = 1234, value = "1"))
        assertFalse(isCombinationSegmentIncorrect(expected = 1234, value = "1234"))
    }

    @Test
    fun divergingValue_isAnError() {
        assertTrue(isCombinationSegmentIncorrect(expected = 1234, value = "2"))
        assertTrue(isCombinationSegmentIncorrect(expected = 1234, value = "1235"))
    }
}
