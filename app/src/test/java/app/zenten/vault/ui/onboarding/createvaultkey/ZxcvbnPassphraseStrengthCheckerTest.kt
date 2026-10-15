package app.zenten.vault.ui.onboarding.createvaultkey

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks in the estimator behaviour the VaultKey policy depends on.
 *
 * Assertions compare against [VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE] rather
 * than fixed guess counts, so a legitimate zxcvbn dictionary update does not break the
 * suite for the wrong reason while a regression in what the policy accepts still does.
 */
class ZxcvbnPassphraseStrengthCheckerTest {
    private val checker = ZxcvbnPassphraseStrengthChecker()

    @Test
    fun scores_predictable_mutations_of_common_passwords_below_the_policy_floor() = runTest {
        // These are precisely the shapes a character-class rule accepts and even pushes
        // users toward: a dictionary word with the case, digit, and symbol it demands.
        val predictable = listOf(
            "Password1!",
            "P@ssw0rd",
            "Qwerty123!",
            "iloveyou1234",
            "dragon1234567",
        )

        predictable.forEach { passphrase ->
            val strength = checker.measure(passphrase)

            assertTrue(
                strength.score < VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE,
                "\"$passphrase\" scored ${strength.score}, expected below the policy floor",
            )
            // A real measurement was taken rather than a placeholder value.
            assertTrue(strength.offlineSlowHashSeconds > 0.0, "\"$passphrase\" produced no crack-time estimate")
        }
    }

    @Test
    fun scores_long_multi_word_passphrases_at_or_above_the_policy_floor() = runTest {
        val accepted = listOf(
            "correcthorsebatterystaple",
            "Correct1 horse!",
            "First1 passphrase!",
        )

        accepted.forEach { passphrase ->
            val strength = checker.measure(passphrase)

            assertTrue(
                strength.score >= VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE,
                "\"$passphrase\" scored ${strength.score}, expected at or above the policy floor",
            )
        }
    }

    @Test
    fun penalises_product_names_so_a_weak_tail_cannot_pass() = runTest {
        // Without the product-term hint this scores in the top bucket, because "vaultkey"
        // is not a famous password. It is guessable for *this* product, so the policy must
        // see it as such: the length rule alone would otherwise accept it.
        val strength = checker.measure("vaultkey1234!")

        assertTrue(
            strength.score < VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE,
            "product-name passphrase scored ${strength.score}, expected below the policy floor",
        )
        assertTrue(VaultKeyPassphrasePolicy.hasMinimumLength("vaultkey1234!"))
    }

    @Test
    fun refuses_input_beyond_the_measurement_bound_instead_of_approximating_it() = runTest {
        // Long adversarial input must fail closed: measuring only a prefix could report a
        // stronger score than the whole string deserves, and the rule must never be
        // satisfied by accident.
        val enormous = "Correct1 horse!".repeat(30)
        assertTrue(enormous.length > 256)

        val strength = checker.measure(enormous)

        assertEquals(0, strength.score)
        assertTrue(strength.score < VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE)
    }

    @Test
    fun leaves_no_plaintext_in_the_state_it_returns() = runTest {
        val passphrase = "Correct1 horse!"

        val strength = checker.measure(passphrase)

        assertTrue(passphrase !in strength.toString())
    }
}
