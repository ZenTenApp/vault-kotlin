package app.zenten.vault.ui.accounts

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.ProfilePublicationScheduler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalAccountBackupViewModelTest {
    @Test
    fun confirms_the_exported_account_key_when_pasted_back() = runTest {
        val account = accountWith("pass")
        val viewModel = viewModelFor(account)

        val result = viewModel.confirmPastedAccountKey(LocalAccountBackupShare.accountExportText(account))

        assertEquals(AccountBackupConfirmationResult.MATCHED, result)
    }

    @Test
    fun rejects_a_different_valid_key_as_mismatched() = runTest {
        val account = accountWith("pass")
        val other = SshKeyCodec.generateEncrypted(1_700_000_000_000_000L, "other".toCharArray(), rounds = 16)
        val viewModel = viewModelFor(account)

        val result = viewModel.confirmPastedAccountKey(other.privateKeyPem)

        assertEquals(AccountBackupConfirmationResult.MISMATCHED, result)
    }

    @Test
    fun rejects_unparseable_paste_as_invalid() = runTest {
        val viewModel = viewModelFor(accountWith("pass"))

        val result = viewModel.confirmPastedAccountKey("not a key")

        assertEquals(AccountBackupConfirmationResult.INVALID, result)
    }

    @Test
    fun rejects_oversized_clipboard_paste_without_decoding() = runTest {
        val viewModel = viewModelFor(accountWith("pass"))
        val oversized = "A".repeat(SshKeyCodec.MAX_SERIALIZED_KEY_CHARS + 1)

        val result = viewModel.confirmPastedAccountKey(oversized)

        assertEquals(AccountBackupConfirmationResult.INVALID, result)
    }

    private fun accountWith(passphrase: String): Account {
        val generated = SshKeyCodec.generateEncrypted(1_700_000_000_000_000L, passphrase.toCharArray(), rounds = 16)
        return Account(
            createdAt = generated.createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),
            zenTenPass = passphrase,
            nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)),
            accountName = "Work account",
        )
    }

    private fun viewModelFor(account: Account): LocalAccountBackupViewModel {
        val accountService = mockk<AccountService>()
        every { accountService.getAccount(account.createdAt) } returns account
        val viewModel = LocalAccountBackupViewModel(
            accountService,
            mockk<AppStorageService>(relaxed = true),
            mockk<ProfilePublicationScheduler>(relaxed = true),
        )
        viewModel.load(account.createdAt)
        return viewModel
    }
}
