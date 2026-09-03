package app.zenten.vault.ui.accounts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupStatusFormattingTest {
    @Test
    fun `backup time is displayed in compact relative units`() {
        val now = 10_000_000_000L

        assertEquals("30 min ago", formatRelativeTime(now - 30 * 60_000L, now))
        assertEquals("5d ago", formatRelativeTime(now - 5 * 24 * 60 * 60_000L, now))
        assertEquals("1w ago", formatRelativeTime(now - 7 * 24 * 60 * 60_000L, now))
        assertEquals("3w ago", formatRelativeTime(now - 21 * 24 * 60 * 60_000L, now))
        assertEquals("2m ago", formatRelativeTime(now - 8 * 7 * 24 * 60 * 60_000L, now))
        assertEquals("2y ago", formatRelativeTime(now - 24 * 4 * 7 * 24 * 60 * 60_000L, now))
    }
}
