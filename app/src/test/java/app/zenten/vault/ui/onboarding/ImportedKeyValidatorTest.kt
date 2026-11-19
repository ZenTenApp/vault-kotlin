package app.zenten.vault.ui.onboarding

import app.zenten.vault.R
import app.zenten.vault.data.model.Key
import app.zenten.vault.ui.keys.LocalKeyBackupShare
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportedKeyValidatorTest {
    private fun errorOf(text: String): Int =
        (ImportedKeyValidator.validate(text) as ImportedKeyValidation.Invalid).error.messageRes

    @Test
    fun blank_text_is_rejected_as_empty() {
        assertEquals(R.string.ssh_setup_clipboard_empty_error, errorOf("  \n"))
    }

    @Test
    fun unrelated_text_is_rejected_as_invalid() {
        assertEquals(R.string.ssh_setup_clipboard_invalid_error, errorOf("https://example.com"))
    }

    @Test
    fun associated_key_export_is_rejected() {
        val export = LocalKeyBackupShare.keyExportText(
            Key(createdAt = 1_700_000_000_000_000L, zenTenKey = goldenEncryptedKey(), keyName = "Personal key"),
        )
        assertEquals(R.string.ssh_setup_clipboard_associated_key_error, errorOf(export))
    }

    @Test
    fun valid_key_is_accepted_and_normalized() {
        val result = ImportedKeyValidator.validate(goldenEncryptedKey())
        assertTrue(result is ImportedKeyValidation.Valid)
    }

    /** Encrypted Ed25519 key from the golden crypto vectors, the same fixture the export tests use. */
    private fun goldenEncryptedKey(): String {
        val json = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        return Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(json)!!
            .groupValues[1]
            .replace("\\n", "\n")
    }
}
