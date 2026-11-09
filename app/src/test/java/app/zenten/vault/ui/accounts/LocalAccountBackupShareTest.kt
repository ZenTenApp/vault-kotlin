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
        assertEquals(account.createdAt.toString(), lines[3])
        assertEquals("Personal account", lines[4])
        assertEquals("Created on ${formatAccountCreatedAt(account.createdAt)}", lines[5])
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

        assertEquals("", lines[4])
        assertEquals(6, lines.size)
    }

    @Test
    fun vault_key_export_keeps_its_derived_npub_and_fixed_display_name() {
        val encodedKey = goldenEncryptedKey()
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = encodedKey,
        )

        val lines = LocalAccountBackupShare.vaultKeyExportText(account, GOLDEN_PASSPHRASE).lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        // Derived on demand from the golden key's seed; nothing is stored on the account.
        assertEquals(GOLDEN_NPUB, lines[3])
        assertEquals(account.createdAt.toString(), lines[4])
        assertEquals("VaultKey", lines[5])
        assertEquals("Created on ${formatAccountCreatedAt(account.createdAt)}", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun vault_key_export_fails_when_the_passphrase_does_not_decrypt_the_key() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
        )

        // A wrong passphrase cannot derive the npub, and an export without its identity line
        // would not match the key, so the attempt fails instead of producing partial text.
        assertThrows(IllegalArgumentException::class.java) {
            LocalAccountBackupShare.vaultKeyExportText(account, "wrong-passphrase".toCharArray())
        }
    }

    @Test
    fun clipboard_export_contains_only_single_line_pem() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
            accountName = "Personal account",
        )

        val lines = LocalAccountBackupShare.accountKeyPemText(account).lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        assertEquals(3, lines.size)
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
