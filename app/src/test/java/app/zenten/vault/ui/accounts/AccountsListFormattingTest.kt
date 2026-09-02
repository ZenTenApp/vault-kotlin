package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AccountsListFormattingTest {
    @Test
    fun `account ID is grouped like a card number`() {
        assertEquals("1234 5678 9012 3456", formatAccountId(1_234_567_890_123_456L))
    }

    @Test
    fun `microsecond account ID is formatted as a date`() {
        val formatted = formatAccountCreatedAt(1_700_000_000_000_000L)

        assertNotEquals("1700000000000000", formatted)
    }

    @Test
    fun `accounts created on different days in a month share a group`() {
        val accounts = listOf(
            Account(createdAt = 1_700_444_800_000_000L, zenTenKey = "older", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32))),
            Account(createdAt = 1_700_876_800_000_000L, zenTenKey = "newer", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32))),
        )

        val grouped = accountsByMonth(accounts)

        assertEquals(1, grouped.size)
        assertEquals(
            listOf(1_700_876_800_000_000L, 1_700_444_800_000_000L),
            grouped.single().second.map { it.createdAt },
        )
    }

    @Test
    fun `newest account is listed first`() {
        val accounts = listOf(
            Account(createdAt = 1_700_000_000_000_000L, zenTenKey = "older", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32))),
            Account(createdAt = 1_800_000_000_000_000L, zenTenKey = "newer", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32))),
        )

        assertEquals(
            listOf(1_800_000_000_000_000L, 1_700_000_000_000_000L),
            newestFirst(accounts).map { it.createdAt },
        )
    }
}
