package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import org.junit.jupiter.api.Assertions.assertEquals
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

            nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
            accountName = " Personal account ",
        )

        val lines = LocalAccountBackupShare.accountExportText(account).lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        assertEquals(formatNpub(account.nostr.pubkey), lines[3])
        assertEquals(account.createdAt.toString(), lines[4])
        assertEquals("Personal account", lines[5])
        assertEquals("Created on ${formatAccountCreatedAt(account.createdAt)}", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun vault_key_export_uses_fixed_display_name_and_same_line_order() {
        val encodedKey = goldenEncryptedKey()
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = encodedKey,

            nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
        )

        val lines = LocalAccountBackupShare.vaultKeyExportText(account).lines()

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines[0])
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines[2])
        assertTrue(lines[1].matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        assertEquals(formatNpub(account.nostr.pubkey), lines[3])
        assertEquals(account.createdAt.toString(), lines[4])
        assertEquals("VaultKey", lines[5])
        assertEquals("Created on ${formatAccountCreatedAt(account.createdAt)}", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun clipboard_export_contains_only_single_line_pem() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),

            nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
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
}
