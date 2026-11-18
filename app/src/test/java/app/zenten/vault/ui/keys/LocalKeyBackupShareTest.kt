package app.zenten.vault.ui.keys

import app.zenten.vault.data.model.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalKeyBackupShareTest {
    @Test
    fun prioritizes_supported_note_apps_then_orders_other_apps_by_label() {
        val targets = LocalKeyBackupShare.orderedTargets(
            listOf(
                target("Messenger", "example.messaging", "MessageActivity"),
                target("Obsidian", "md.obsidian", "ObsidianActivity"),
                target("Archive", "example.archive", "ArchiveActivity"),
                target("Keep", "com.google.android.keep", "KeepActivity"),
            ),
        )

        assertEquals(listOf("Keep", "Obsidian", "Archive", "Messenger"), targets.map { it.label })
    }

    @Test
    fun removes_only_duplicate_activities() {
        val first = target("Keep", "com.google.android.keep", "KeepActivity")
        val targets = LocalKeyBackupShare.orderedTargets(
            listOf(
                first,
                first,
                target("Keep alternate", "com.google.android.keep", "AlternateActivity"),
            ),
        )

        assertEquals(listOf("KeepActivity", "AlternateActivity"), targets.map { it.activityName })
    }

    @Test
    fun key_export_contains_single_line_pem_and_key_metadata_in_order() {
        val pem = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        val encodedKey = Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(pem)!!
            .groupValues[1]
            .replace("\\n", "\n")
        val key = Key(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = encodedKey,
            keyName = " Personal key ",
        )

        val lines = LocalKeyBackupShare.keyExportText(key).lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        assertEquals("unixTimestamp:${key.createdAt}", lines[3])
        assertEquals("label:Personal key", lines[4])
        assertEquals("createdAt:${formatKeyCreatedAt(key.createdAt)}", lines[5])
        assertEquals(6, lines.size)
        // The export must not carry the key's npub, which would link it to a Nostr identity.
        assertFalse(LocalKeyBackupShare.keyExportText(key).contains("npub"))
    }

    @Test
    fun nameless_key_export_writes_an_empty_display_name_line() {
        val key = Key(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
        )

        val lines = LocalKeyBackupShare.keyExportText(key).lines()

        assertEquals("label:", lines[4])
        assertEquals(6, lines.size)
    }

    @Test
    fun vault_key_export_uses_fixed_display_name_and_omits_npub() {
        val encodedKey = goldenEncryptedKey()
        val key = Key(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = encodedKey,
        )

        val text = LocalKeyBackupShare.vaultKeyExportText(key, GOLDEN_PASSPHRASE)
        val lines = text.lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        // The Vault Key's npub is deliberately not exported, same as associated keys.
        assertFalse(text.contains(GOLDEN_NPUB))
        assertEquals("unixTimestamp:${key.createdAt}", lines[3])
        assertEquals("label:VaultKey", lines[4])
        assertEquals("createdAt:${formatKeyCreatedAt(key.createdAt)}", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun vault_key_export_fails_when_the_passphrase_does_not_decrypt_the_key() {
        val key = Key(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
        )

        // A wrong passphrase cannot decrypt the key, so the attempt fails instead of producing text.
        assertThrows(IllegalArgumentException::class.java) {
            LocalKeyBackupShare.vaultKeyExportText(key, "wrong-passphrase".toCharArray())
        }
    }

    @Test
    fun clipboard_and_qr_payload_is_the_same_text_as_the_notes_export() {
        val key = Key(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
            keyName = "Personal key",
        )

        // The clipboard and QR code carry exactly the notes export, metadata included.
        val notesText = LocalKeyBackupShare.keyExportText(key)
        assertEquals("label:Personal key", notesText.lines()[4])
        assertFalse(notesText.contains("npub"))
    }

    private fun goldenEncryptedKey(): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
            .let { json ->
                Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                    .find(json)!!
                    .groupValues[1]
                    .replace("\\n", "\n")
            }

    private fun target(label: String, packageName: String, activityName: String) = TestTarget(
        label = label,
        packageName = packageName,
        activityName = activityName,
    )

    private data class TestTarget(
        override val label: String,
        override val packageName: String,
        override val activityName: String,
    ) : LocalKeyBackupShare.Target

    private companion object {
        /** Passphrase protecting the golden `encryptedEd25519Pem` vector. */
        val GOLDEN_PASSPHRASE = "phase1-test-passphrase".toCharArray()

        /** NIP-06 npub of the golden key's seed, from `crypto-vectors.json`. */
        const val GOLDEN_NPUB = "npub1jnmxa9a3z7tkej876gc56h6lc79hys20amrrmug8m3qarr9xgglq788y5w"
    }
}
