package app.zenten.vault.crypto

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class SeedifyTest {
    private val epoch = Instant.parse("2022-05-10T00:00:00Z")
    private val millisPerDay = 24L * 60 * 60 * 1_000

    /** Midnight UTC on [day] days after the Brave Sync epoch. */
    private fun day(day: Long): Instant = Instant.ofEpochMilli(epoch.toEpochMilli() + day * millisPerDay)

    @Test
    fun twenty_fifth_word_uses_the_last_wordlist_entry_on_day_2047() {
        assertEquals(Bip39Mnemonics.englishWordlist[2047], Seedify.braveSync25thWord(day(2047)))
    }

    @Test
    fun twenty_fifth_word_wraps_to_the_first_wordlist_entry_on_day_2048() {
        // Brave's GetWordByIndex wraps with `index % BIP39_WORDLIST_LEN`, so day 2048 is
        // "abandon" (index 0) rather than an out-of-bounds error.
        assertEquals(Bip39Mnemonics.englishWordlist[0], Seedify.braveSync25thWord(day(2048)))
        assertEquals("abandon", Seedify.braveSync25thWord(day(2048)))
    }

    @Test
    fun twenty_fifth_word_wraps_on_day_4096() {
        assertEquals(Bip39Mnemonics.englishWordlist[0], Seedify.braveSync25thWord(day(4096)))
    }

    @Test
    fun twenty_fifth_word_rolls_over_at_the_rounded_half_day() {
        // Math.round matches Brave's std::round, so day 2048 begins at 2027-12-17T12:00:00Z,
        // not at the following midnight. This pins the actual first-failure instant.
        assertEquals(Bip39Mnemonics.englishWordlist[2047], Seedify.braveSync25thWord(day(2047)))
        assertEquals(
            Bip39Mnemonics.englishWordlist[0],
            Seedify.braveSync25thWord(Instant.ofEpochMilli(epoch.toEpochMilli() + 2047 * millisPerDay + millisPerDay / 2)),
        )
    }

    @Test
    fun sync_code_past_the_wordlist_horizon_keeps_its_first_24_words() {
        val seed = ByteArray(32) { it.toByte() }
        val words = Seedify.toBraveSyncCode(seed, day(4096)).split(" ")

        assertEquals(25, words.size)
        assertEquals(Seedify.toVaultKeyBackupMnemonic(seed), words.take(24).joinToString(" "))
        assertEquals(Bip39Mnemonics.englishWordlist[0], words.last())
    }

    @Test
    fun twenty_fifth_word_rejects_dates_before_the_brave_epoch() {
        // Brave also rejects pre-epoch dates (kNotAfterEarlierThanEpoch); only the upper bound wraps.
        assertThrows(IllegalArgumentException::class.java) {
            Seedify.braveSync25thWord(epoch.minusSeconds(86_400))
        }
    }
}
