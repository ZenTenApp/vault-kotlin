package app.zenten.vault.ui.onboarding.createvaultkey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers how raw numbers become readable text.
 *
 * These are pure functions rather than composables so the units and the rounding can be pinned
 * down without a Compose test host; the wording and plurals themselves live in `strings.xml`.
 */
class PassphraseStrengthFormattingTest {
    @Test
    fun picks_the_largest_unit_that_still_reads_as_a_whole_number() {
        assertEquals(CrackTime(0, CrackTimeUnit.LessThanASecond), crackTimeOf(0.4))
        assertEquals(CrackTime(45, CrackTimeUnit.Seconds), crackTimeOf(45.0))
        assertEquals(CrackTime(10, CrackTimeUnit.Minutes), crackTimeOf(600.0))
        assertEquals(CrackTime(2, CrackTimeUnit.Hours), crackTimeOf(7_200.0))
        assertEquals(CrackTime(3, CrackTimeUnit.Days), crackTimeOf(259_200.0))
        assertEquals(CrackTime(2, CrackTimeUnit.Months), crackTimeOf(5_259_600.0))
        assertEquals(CrackTime(5, CrackTimeUnit.Years), crackTimeOf(157_788_000.0))
    }

    @Test
    fun caps_at_centuries_rather_than_printing_an_unreadable_year_count() {
        assertEquals(CrackTime(0, CrackTimeUnit.Centuries), crackTimeOf(1.0e15))
    }

    @Test
    fun renders_small_guess_counts_plainly_and_large_ones_in_scientific_notation() {
        assertEquals("378", formatGuessCount(378.0))
        assertEquals("2280", formatGuessCount(2_280.0))
        assertEquals("4.6 × 10^11", formatGuessCount(4.56e11))
    }
}
