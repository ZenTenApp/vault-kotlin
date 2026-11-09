package app.zenten.vault.ui.vaultKey

import app.zenten.vault.data.kv.AccountSourceStatus
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.SetupService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultKeyDetailViewModelTest {
    @Test
    fun export_text_is_built_from_the_stored_key_and_the_given_passphrase() {
        val viewModel = viewModelWithStoredVaultKey()

        val text = requireNotNull(viewModel.vaultKeyForExport("phase1-test-passphrase"))

        // The npub is re-derived from the stored container's seed, not read from the account.
        assertTrue(text.lines().contains("npub1jnmxa9a3z7tkej876gc56h6lc79hys20amrrmug8m3qarr9xgglq788y5w"))
        assertTrue(text.lines().contains("VaultKey"))
    }

    @Test
    fun export_text_is_null_when_the_passphrase_does_not_decrypt_the_stored_key() {
        val viewModel = viewModelWithStoredVaultKey()

        // A partial export would be missing the npub line, so no text is produced at all.
        assertNull(viewModel.vaultKeyForExport("wrong-passphrase"))
    }

    @Test
    fun export_is_unavailable_when_no_vault_key_is_stored() {
        val viewModel = VaultKeyDetailViewModel(
            sourceRepository = mockk { every { sources } returns MutableStateFlow(emptyList()) },
            appStorage = mockk {
                every { accountSourceStatuses } returns MutableStateFlow(emptyList<AccountSourceStatus>())
                every { setupComplete } returns MutableStateFlow(false)
            },
            setupService = mockk(relaxed = true),
            scheduler = mockk(relaxed = true),
            secureStorage = mockk { every { getVaultKey() } returns null },
        )

        assertFalse(viewModel.canExportVaultKey())
        assertNull(viewModel.vaultKeyForExport("phase1-test-passphrase"))
    }

    private fun viewModelWithStoredVaultKey(): VaultKeyDetailViewModel =
        VaultKeyDetailViewModel(
            // The state flow is only assembled here; these flows are never collected by the test.
            sourceRepository = mockk { every { sources } returns MutableStateFlow(emptyList()) },
            appStorage = mockk {
                every { accountSourceStatuses } returns MutableStateFlow(emptyList<AccountSourceStatus>())
                every { setupComplete } returns MutableStateFlow(false)
            },
            setupService = mockk(relaxed = true),
            scheduler = mockk(relaxed = true),
            secureStorage = mockk {
                every { getVaultKey() } returns Account(
                    createdAt = 1_700_000_000_000_000L,
                    // Passphrase-protected golden container; its seed derives the npub below.
                    zenTenKey = goldenEncryptedKey(),
                )
            },
        )

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
}
