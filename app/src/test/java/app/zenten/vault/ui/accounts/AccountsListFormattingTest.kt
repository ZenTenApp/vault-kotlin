package app.zenten.vault.ui.accounts

import androidx.compose.ui.graphics.Color
import app.zenten.vault.data.model.Account
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.Base64

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
    fun `fingerprint keeps first and last four characters`() {
        val wire = ByteArray(51) { it.toByte() }
        val publicKeyLine = "ssh-ed25519 ${Base64.getEncoder().encodeToString(wire)} account"

        val shortened = accountFingerprint(publicKeyLine)

        assertEquals(11, shortened.length)
        assertEquals("...", shortened.substring(4, 7))
    }

    @Test
    fun `account color is stable and opaque`() {
        val first = accountCardColor(1_234_567_890_123_456L)

        assertEquals(first, accountCardColor(1_234_567_890_123_456L))
        assertEquals(1f, first.alpha)
        assertNotEquals(Color.Unspecified, first)
    }

    @Test
    fun `newest account is listed first`() {
        val accounts = listOf(
            Account(createdAt = 1_700_000_000_000_000L, zenTenKey = "older"),
            Account(createdAt = 1_800_000_000_000_000L, zenTenKey = "newer"),
        )

        assertEquals(
            listOf(1_800_000_000_000_000L, 1_700_000_000_000_000L),
            newestFirst(accounts).map { it.createdAt },
        )
    }
}
