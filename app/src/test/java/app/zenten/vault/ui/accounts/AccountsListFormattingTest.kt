package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountsListFormattingTest {
    @Test
    fun `account ID is grouped like a card number`() {
        assertEquals(listOf("1234", "5678", "9012", "3456"), accountIdChunks(1_234_567_890_123_456L))
        assertEquals("1234 5678 9012 3456", formatAccountId(1_234_567_890_123_456L))
    }

    @Test
    fun `microsecond account ID is formatted as a date`() {
        val formatted = formatAccountCreatedAt(1_700_000_000_000_000L)

        assertNotEquals("1700000000000000", formatted)
    }

    @Test
    fun `detail date includes the time while the list date is date-only`() {
        val timestamp = 1_700_000_000_000_000L

        // The full detail format always carries a clock time; the compact list format must not.
        assertTrue(formatAccountCreatedAt(timestamp).contains(":"))
        assertTrue(!formatAccountCreatedDate(timestamp).contains(":"))
    }

    @Test
    fun `Nostr public key is formatted as a full npub`() {
        val npub = formatNpub("00".repeat(32))

        assertTrue(npub.matches(Regex("npub1[a-z0-9]+")))
    }

    @Test
    fun `stored encrypted SSH key is formatted as a fingerprint for account details`() {
        val pem = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        val encodedKey = Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(pem)!!
            .groupValues[1]
            .replace("\\n", "\n")

        assertEquals("SHA256:tX0ZrsNLIB/ZlRK3vy/HsWIIkyBNhYhCSGmtqtxJcWo", formatSshFingerprint(encodedKey))
        assertEquals("Unavailable", formatSshFingerprint(null))
        assertEquals("Unavailable", formatSshFingerprint("invalid"))
    }

    @Test
    fun `stored encrypted SSH key is formatted as a public key line without a comment`() {
        val pem = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        val encodedKey = Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(pem)!!
            .groupValues[1]
            .replace("\\n", "\n")

        assertEquals(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMlBKiSzOJLCh5/M3g7uPNG/q8mnI5b0KcZeCRSNVqMc",
            formatSshPublicKey(encodedKey, comment = ""),
        )
        assertEquals("Unavailable", formatSshPublicKey(null, comment = ""))
        assertEquals("Unavailable", formatSshPublicKey("invalid", comment = ""))
    }

    @Test
    fun `stored encrypted SSH key is formatted as KDF rounds for account details`() {
        val pem = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        val encodedKey = Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(pem)!!
            .groupValues[1]
            .replace("\\n", "\n")

        assertEquals("1", formatKdfRounds(encodedKey))
        assertEquals("Unavailable", formatKdfRounds(null))
        assertEquals("Unavailable", formatKdfRounds("invalid"))
    }

    @Test
    fun `card identity is the trimmed stored account name`() {
        assertEquals("Stored name", accountCardIdentity(account(" Stored name ")))
    }

    @Test
    fun `long account name is returned in full for layout ellipsis`() {
        assertEquals("12345678901234567", accountCardIdentity(account("12345678901234567")))
    }

    @Test
    fun `missing or blank account name renders as empty rather than the npub`() {
        // The card must not present an associated account as a Nostr identity.
        assertEquals("", accountCardIdentity(account("  ")))
        assertEquals("", accountCardIdentity(account(null)))
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

    private fun account(accountName: String?) = Account(
        createdAt = 1L,
        zenTenKey = "key",

        nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
        accountName = accountName,
    )
}
