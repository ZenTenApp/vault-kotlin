package app.zenten.vault.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountIdTest {
    @Test
    fun fills_the_sub_millisecond_digits_with_a_random_nonzero_tail() {
        val nowMillis = 1_762_300_000_000L

        val id = newAccountCreatedAt(nowMillis)

        // The ID stays anchored to the supplied clock so it remains a sortable creation time…
        assertEquals(nowMillis, id / 1_000L)
        // …while the last three digits are never left as the predictable `000` tail.
        assertTrue(id % 1_000L in 1L..999L)
    }

    @Test
    fun same_millisecond_allocations_do_not_all_repeat_the_same_id() {
        val nowMillis = 1_762_300_000_000L

        val ids = List(64) { newAccountCreatedAt(nowMillis) }

        // The tail is jitter, not a uniqueness guarantee, but identical IDs across 64 draws
        // would mean the random component was not applied at all.
        assertTrue(ids.toSet().size > 1)
    }
}
