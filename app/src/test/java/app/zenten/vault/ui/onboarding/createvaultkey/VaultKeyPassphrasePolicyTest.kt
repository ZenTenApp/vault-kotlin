package app.zenten.vault.ui.onboarding.createvaultkey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultKeyPassphrasePolicyTest {
    private fun strength(score: Int) = passphraseStrengthForScore(score)

    @Test
    fun rejects_input_below_the_length_floor_however_strong_it_would_score() {
        val tooShort = "a".repeat(VaultKeyPassphrasePolicy.MINIMUM_LENGTH - 1)

        val requirements = VaultKeyPassphrasePolicy.evaluate(tooShort, strength(4))

        assertFalse(requirements.hasMinimumLength)
        assertFalse(requirements.allSatisfied)
        assertFalse(VaultKeyPassphrasePolicy.isValid(tooShort, strength(4)))
    }

    @Test
    fun rejects_a_long_but_guessable_passphrase() {
        val guessable = "vaultkey1234!"

        val requirements = VaultKeyPassphrasePolicy.evaluate(guessable, strength(2))

        assertTrue(requirements.hasMinimumLength)
        assertFalse(requirements.hasSufficientStrength)
        assertFalse(requirements.allSatisfied)
    }

    @Test
    fun rejects_a_strength_score_one_bucket_below_the_floor() {
        val justBelow = strength(VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE - 1)

        assertFalse(VaultKeyPassphrasePolicy.isValid("a".repeat(20), justBelow))
    }

    @Test
    fun treats_an_unmeasured_passphrase_as_not_yet_strong_enough() {
        val pending = "correct horse battery"

        val requirements = VaultKeyPassphrasePolicy.evaluate(pending, strength = null)

        // The length rule can already be satisfied while the estimator is still running.
        assertTrue(requirements.hasMinimumLength)
        assertFalse(requirements.hasSufficientStrength)
        assertFalse(requirements.allSatisfied)
    }

    @Test
    fun accepts_a_passphrase_that_meets_both_rules_at_their_boundaries() {
        val atLengthFloor = "a".repeat(VaultKeyPassphrasePolicy.MINIMUM_LENGTH)
        val atStrengthFloor = strength(VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE)

        assertTrue(VaultKeyPassphrasePolicy.isValid(atLengthFloor, atStrengthFloor))
    }

    @Test
    fun does_not_require_character_class_composition() {
        // A long passphrase carrying no digit or symbol must be able to satisfy the
        // policy. The composition rules that used to reject it are exactly what pushed
        // users toward the predictable mutations a strength estimator catches.
        val allLowercase = "correcthorsebatterystaple"

        assertTrue(
            VaultKeyPassphrasePolicy.isValid(
                allLowercase,
                strength(VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE),
            ),
        )
    }

    @Test
    fun applies_the_length_floor_without_measuring() {
        assertEquals(
            VaultKeyPassphrasePolicy.MINIMUM_LENGTH,
            "a".repeat(VaultKeyPassphrasePolicy.MINIMUM_LENGTH).length,
        )
        assertTrue(VaultKeyPassphrasePolicy.hasMinimumLength("a".repeat(VaultKeyPassphrasePolicy.MINIMUM_LENGTH)))
        assertFalse(VaultKeyPassphrasePolicy.hasMinimumLength("a".repeat(VaultKeyPassphrasePolicy.MINIMUM_LENGTH - 1)))
    }
}
