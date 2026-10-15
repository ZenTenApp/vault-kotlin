package app.zenten.vault.ui.onboarding.createvaultkey

import com.nulabinc.zxcvbn.Zxcvbn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers how zxcvbn's raw result is projected onto the model the details sheet renders.
 *
 * Uses the real estimator rather than a stub, because the point is the library's own
 * classification: which matches it reports for a given shape of passphrase, and what the
 * projection does with them.
 */
class PassphraseStrengthMappingTest {
    private val zxcvbn = Zxcvbn()

    private fun strengthOf(passphrase: String): PassphraseStrength =
        passphraseStrengthOf(zxcvbn.measure(passphrase))

    @Test
    fun classifies_a_dictionary_word_as_a_common_password() {
        val finding = strengthOf("qwertyuiop").findings.single()

        assertEquals(PassphraseFindingKind.CommonPassword, finding.kind)
        assertEquals("passwords", finding.dictionaryName)
        assertEquals(22, finding.dictionaryRank)
    }

    @Test
    fun classifies_l33t_and_reversed_words_by_their_transformation() {
        // Both are the word "password" underneath; naming the transformation is what makes the
        // finding actionable, so it wins over the generic "common password".
        assertEquals(PassphraseFindingKind.SubstitutedWord, strengthOf("p4ssw0rd").findings.first().kind)
        assertEquals(PassphraseFindingKind.ReversedWord, strengthOf("drowssap").findings.first().kind)
    }

    @Test
    fun classifies_the_other_structures_zxcvbn_recognizes() {
        assertEquals(PassphraseFindingKind.CommonWord, strengthOf("Correct1 horse!").findings.first().kind)
        assertEquals(PassphraseFindingKind.KeyboardPattern, strengthOf("asdfghjkl;").findings.first().kind)
        assertEquals(PassphraseFindingKind.RepeatedCharacters, strengthOf("abcabcabc").findings.first().kind)
        assertEquals(PassphraseFindingKind.PredictableSequence, strengthOf("abcdef").findings.first().kind)
        assertEquals(PassphraseFindingKind.Date, strengthOf("19/09/2021").findings.first().kind)
        assertTrue(
            strengthOf("mybirthday1990").findings.any { it.kind == PassphraseFindingKind.RecentYear },
        )
    }

    @Test
    fun keeps_every_match_in_passphrase_order_including_the_unexplained_stretch() {
        // zxcvbn explains this passphrase as one dictionary word plus a tail it could not explain.
        // Both are kept, in order, so the spans still account for the whole passphrase.
        val findings = strengthOf("Correct1 horse!").findings

        assertEquals(
            listOf(PassphraseFindingKind.CommonWord, PassphraseFindingKind.UnpredictableCharacters),
            findings.map { it.kind },
        )
        assertEquals(0, findings.first().startIndex)
        assertEquals(6, findings.first().endIndex)
        assertEquals(7, findings.last().startIndex)
        assertEquals(14, findings.last().endIndex)
    }

    @Test
    fun lists_each_match_separately_in_position_order_rather_than_by_cost() {
        // The trailing year is the cheapest thing here, yet it comes last because that is where it
        // sits in the passphrase: the list describes the decomposition, it is not a ranking.
        val findings = strengthOf("Correct1 horse!1990").findings

        assertEquals(
            listOf(
                PassphraseFindingKind.CommonWord,
                PassphraseFindingKind.UnpredictableCharacters,
                PassphraseFindingKind.RecentYear,
            ),
            findings.map { it.kind },
        )
        assertEquals(listOf(2280.0, 1.0E8, 50.0), findings.map { it.guesses })
        assertTrue(
            findings.map { it.startIndex }.zipWithNext().all { (earlier, later) -> earlier < later },
        )
    }

    @Test
    fun carries_the_per_pattern_details_the_sheet_names() {
        val keyboard = strengthOf("asdfghjkl;").findings.single()
        assertEquals("qwerty", keyboard.keyboardName)
        assertEquals(1, keyboard.keyboardTurns)

        val repeat = strengthOf("abcabcabc").findings.single()
        assertEquals(3, repeat.repeatCount)

        val sequence = strengthOf("abcdef").findings.single()
        assertEquals(true, sequence.sequenceAscending)
    }

    @Test
    fun maps_every_kind_back_to_the_zxcvbn_pattern_it_came_from() {
        val expected = mapOf(
            PassphraseFindingKind.CommonPassword to PassphraseFindingPattern.Dictionary,
            PassphraseFindingKind.CommonWord to PassphraseFindingPattern.Dictionary,
            PassphraseFindingKind.ReversedWord to PassphraseFindingPattern.Dictionary,
            PassphraseFindingKind.SubstitutedWord to PassphraseFindingPattern.Dictionary,
            PassphraseFindingKind.KeyboardPattern to PassphraseFindingPattern.Spatial,
            PassphraseFindingKind.RepeatedCharacters to PassphraseFindingPattern.Repeat,
            PassphraseFindingKind.PredictableSequence to PassphraseFindingPattern.Sequence,
            PassphraseFindingKind.Date to PassphraseFindingPattern.Date,
            PassphraseFindingKind.RecentYear to PassphraseFindingPattern.Regex,
            PassphraseFindingKind.UnpredictableCharacters to PassphraseFindingPattern.Bruteforce,
        )

        // Names over every kind, so a kind added later cannot quietly acquire the wrong label.
        assertEquals(expected, PassphraseFindingKind.entries.associateWith(::findingPattern))
    }

    @Test
    fun leaves_other_patterns_attributes_empty() {
        // zxcvbn leaves `ascending` false and `rank` zero on matches where they mean nothing, so
        // the projection has to gate on the pattern rather than pass the raw values through.
        val dictionary = strengthOf("qwertyuiop").findings.single()

        assertNull(dictionary.keyboardName)
        assertNull(dictionary.keyboardTurns)
        assertNull(dictionary.repeatCount)
        assertNull(dictionary.sequenceAscending)
    }

    @Test
    fun reports_the_span_and_length_of_each_match() {
        val finding = strengthOf("Correct1 horse!").findings.first()

        assertEquals(0, finding.startIndex)
        assertEquals(6, finding.endIndex)
        assertEquals(7, finding.characterCount)
    }

    @Test
    fun carries_the_matched_text_so_the_sheet_can_name_the_sequence() {
        // Two dictionary hits in one passphrase: the case a pattern and a span cannot tell apart
        // from any other six- and seven-character run.
        val findings = strengthOf("alice@1234567").findings

        assertEquals(listOf("alice@", "1234567"), findings.map { it.token })
        // "alice@" comes back as a l33t hit on "alicea" ("@" for "a"), and "1234567" is a plain
        // hit on the passwords list, so the two are told apart by kind as well as by text.
        assertEquals(
            listOf(PassphraseFindingKind.SubstitutedWord, PassphraseFindingKind.CommonPassword),
            findings.map { it.kind },
        )
    }

    @Test
    fun does_not_carry_the_deobfuscated_word_behind_a_substitution() {
        // The matched text is carried, but the word zxcvbn recovered from it is not: naming the
        // sequence only needs what the user actually typed. "passwords" is the dictionary name and
        // contains "password", so this uses a substitution whose word is not a substring of it.
        val projected = strengthOf("h3ll0").toString()

        assertTrue(projected.contains("h3ll0"), projected)
        assertFalse(projected.contains("hello"), projected)
    }

    @Test
    fun carries_the_two_crack_time_figures_the_sheet_compares() {
        val strength = strengthOf("Correct1 horse!")

        assertTrue(strength.offlineSlowHashSeconds > 0.0)
        assertTrue(strength.offlineFastHashSeconds > 0.0)
        // A fast hash is guessed far quicker than a slow one, which is the whole point of showing
        // the pair: the gap is the value of Vault's KDF.
        assertTrue(strength.offlineFastHashSeconds < strength.offlineSlowHashSeconds)
    }
}
