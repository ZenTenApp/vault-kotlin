package app.zenten.vault.ui.onboarding.createvaultkey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Covers the passphrase strength meter's mapping rules.
 *
 * These are pure functions rather than composables so the meter's wording and fill can be
 * locked down without a Compose test host, matching how the creation screen's transcript
 * lines are tested.
 */
class CreateVaultKeyPassphraseScreenTest {
    @Test
    fun `meter lights one more segment per zxcvbn bucket`() {
        assertEquals(1, passphraseStrengthFilledSegments(0))
        assertEquals(2, passphraseStrengthFilledSegments(1))
        assertEquals(3, passphraseStrengthFilledSegments(2))
        assertEquals(4, passphraseStrengthFilledSegments(3))
        assertEquals(PassphraseStrengthSegmentCount, passphraseStrengthFilledSegments(4))
    }

    @Test
    fun `meter stays inside its track for a score outside the documented range`() {
        assertEquals(PassphraseStrengthSegmentCount, passphraseStrengthFilledSegments(9))
        assertEquals(1, passphraseStrengthFilledSegments(-1))
    }

    @Test
    fun `scores below the policy floor never read as strong`() {
        val belowFloor = (0 until VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE).map(::passphraseStrengthBand)

        assertEquals(
            listOf(
                PassphraseStrengthBand.VeryWeak,
                PassphraseStrengthBand.Weak,
                PassphraseStrengthBand.NotStrongEnough,
            ),
            belowFloor,
        )
        assertFalse(belowFloor.contains(PassphraseStrengthBand.Strong))
        assertFalse(belowFloor.contains(PassphraseStrengthBand.VeryStrong))
    }

    @Test
    fun `the policy floor is the first band that reads as approval`() {
        assertEquals(
            PassphraseStrengthBand.Strong,
            passphraseStrengthBand(VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE),
        )
        assertEquals(
            PassphraseStrengthBand.NotStrongEnough,
            passphraseStrengthBand(VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE - 1),
        )
        assertEquals(
            PassphraseStrengthBand.VeryStrong,
            passphraseStrengthBand(VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE + 1),
        )
    }
}
