package app.zenten.vault.ui.vaultKey

import app.zenten.vault.data.kv.AccountSourceStatus
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.SetupService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultKeyDetailViewModelTest {
    @Test
    fun export_passphrase_is_verified_against_the_stored_vault_key() {
        val accountService = mockk<AccountService>()
        every { accountService.verifyVaultKeyPassphrase("pass") } returns Result.success(Unit)
        every { accountService.verifyVaultKeyPassphrase("wrong") } returns
            Result.failure(IllegalArgumentException("The passphrase does not match the vaultKey account"))

        val viewModel = VaultKeyDetailViewModel(
            // The state flow is only assembled here; these flows are never collected by the test.
            sourceRepository = mockk { every { sources } returns MutableStateFlow(emptyList()) },
            appStorage = mockk {
                every { accountSourceStatuses } returns MutableStateFlow(emptyList<AccountSourceStatus>())
                every { setupComplete } returns MutableStateFlow(false)
            },
            setupService = mockk(relaxed = true),
            scheduler = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            accountService = accountService,
        )

        assertTrue(viewModel.verifyExportPassphrase("pass"))
        assertFalse(viewModel.verifyExportPassphrase("wrong"))
    }
}
