package app.zenten.vault.ui.keys

import app.zenten.vault.data.model.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeysListFormattingTest {
    @Test
    fun `key ID is grouped like a card number`() {
        assertEquals(listOf("1234", "5678", "9012", "3456"), keyIdChunks(1_234_567_890_123_456L))
        assertEquals("1234 5678 9012 3456", formatKeyId(1_234_567_890_123_456L))
    }

    @Test
    fun `microsecond key ID is formatted as a date`() {
        val formatted = formatKeyCreatedAt(1_700_000_000_000_000L)

        assertNotEquals("1700000000000000", formatted)
    }

    @Test
    fun `detail date includes the time while the list date is date-only`() {
        val timestamp = 1_700_000_000_000_000L

        // The full detail format always carries a clock time; the compact list format must not.
        assertTrue(formatKeyCreatedAt(timestamp).contains(":"))
        assertTrue(!formatKeyCreatedDate(timestamp).contains(":"))
    }

    @Test
    fun `stored encrypted SSH key is formatted as a fingerprint for key details`() {
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
    fun `stored encrypted SSH key is formatted as KDF rounds for key details`() {
        val pem = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        val encodedKey = Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(pem)!!
            .groupValues[1]
            .replace("\\n", "\n")

        assertEquals("128", formatKdfRounds(encodedKey))
        assertEquals("Unavailable", formatKdfRounds(null))
        assertEquals("Unavailable", formatKdfRounds("invalid"))
    }

    @Test
    fun `card identity is the trimmed stored key name`() {
        assertEquals("Stored name", keyCardIdentity(key(" Stored name ")))
    }

    @Test
    fun `long key name is returned in full for layout ellipsis`() {
        assertEquals("12345678901234567", keyCardIdentity(key("12345678901234567")))
    }

    @Test
    fun `missing or blank key name renders as empty rather than the npub`() {
        // The card must not present an associated key as a Nostr identity.
        assertEquals("", keyCardIdentity(key("  ")))
        assertEquals("", keyCardIdentity(key(null)))
    }

    @Test
    fun `keys created on different days in a month share a group`() {
        val keys = listOf(
            Key(createdAt = 1_700_444_800_000_000L, zenTenKey = "older"),
            Key(createdAt = 1_700_876_800_000_000L, zenTenKey = "newer"),
        )

        val grouped = keysByMonth(keys)

        assertEquals(1, grouped.size)
        assertEquals(
            listOf(1_700_876_800_000_000L, 1_700_444_800_000_000L),
            grouped.single().second.map { it.createdAt },
        )
    }

    @Test
    fun `newest key is listed first`() {
        val keys = listOf(
            Key(createdAt = 1_700_000_000_000_000L, zenTenKey = "older"),
            Key(createdAt = 1_800_000_000_000_000L, zenTenKey = "newer"),
        )

        assertEquals(
            listOf(1_800_000_000_000_000L, 1_700_000_000_000_000L),
            newestFirst(keys).map { it.createdAt },
        )
    }

    private fun key(keyName: String?) = Key(
        createdAt = 1L,
        zenTenKey = "key",
        keyName = keyName,
    )
}
