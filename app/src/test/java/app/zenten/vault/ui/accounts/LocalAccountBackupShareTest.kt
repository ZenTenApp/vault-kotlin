package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAccountBackupShareTest {
    @Test
    fun prioritizes_supported_note_apps_then_orders_other_apps_by_label() {
        val targets = LocalAccountBackupShare.orderedTargets(
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
        val targets = LocalAccountBackupShare.orderedTargets(
            listOf(
                first,
                first,
                target("Keep alternate", "com.google.android.keep", "AlternateActivity"),
            ),
        )

        assertEquals(listOf("KeepActivity", "AlternateActivity"), targets.map { it.activityName })
    }

    @Test
    fun account_export_contains_single_line_pem_and_account_metadata_in_order() {
        val pem = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        val encodedKey = Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(pem)!!
            .groupValues[1]
            .replace("\\n", "\n")
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = encodedKey,
            accountName = " Personal account ",
        )

        val lines = LocalAccountBackupShare.accountExportText(account).lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        assertEquals("unixTimestamp:${account.createdAt}", lines[3])
        assertEquals("label:Personal account", lines[4])
        assertEquals("createdAt:${formatAccountCreatedAt(account.createdAt)}", lines[5])
        assertEquals(6, lines.size)
        // The export must not carry the account's npub, which would link it to a Nostr identity.
        assertFalse(LocalAccountBackupShare.accountExportText(account).contains("npub"))
    }

    @Test
    fun nameless_account_export_writes_an_empty_display_name_line() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
        )

        val lines = LocalAccountBackupShare.accountExportText(account).lines()

        assertEquals("label:", lines[4])
        assertEquals(6, lines.size)
    }

    @Test
    fun vault_key_export_uses_fixed_display_name_and_omits_npub() {
        val encodedKey = goldenEncryptedKey()
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = encodedKey,
        )

        val text = LocalAccountBackupShare.vaultKeyExportText(account, GOLDEN_PASSPHRASE)
        val lines = text.lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        // The Vault Key's npub is deliberately not exported, same as associated accounts.
        assertFalse(text.contains(GOLDEN_NPUB))
        assertEquals("unixTimestamp:${account.createdAt}", lines[3])
        assertEquals("label:VaultKey", lines[4])
        assertEquals("createdAt:${formatAccountCreatedAt(account.createdAt)}", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun vault_key_export_fails_when_the_passphrase_does_not_decrypt_the_key() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
        )

        // A wrong passphrase cannot decrypt the key, so the attempt fails instead of producing text.
        assertThrows(IllegalArgumentException::class.java) {
            LocalAccountBackupShare.vaultKeyExportText(account, "wrong-passphrase".toCharArray())
        }
    }

    @Test
    fun clipboard_and_qr_payload_is_the_same_text_as_the_notes_export() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
            accountName = "Personal account",
        )

        // The clipboard and QR code carry exactly the notes export, metadata included.
        val notesText = LocalAccountBackupShare.accountExportText(account)
        assertEquals("label:Personal account", notesText.lines()[4])
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
    ) : LocalAccountBackupShare.Target

    private companion object {
        /** Passphrase protecting the golden `encryptedEd25519Pem` vector. */
        val GOLDEN_PASSPHRASE = "phase1-test-passphrase".toCharArray()

        /** NIP-06 npub of the golden key's seed, from `crypto-vectors.json`. */
        const val GOLDEN_NPUB = "npub1jnmxa9a3z7tkej876gc56h6lc79hys20amrrmug8m3qarr9xgglq788y5w"
    }
}
