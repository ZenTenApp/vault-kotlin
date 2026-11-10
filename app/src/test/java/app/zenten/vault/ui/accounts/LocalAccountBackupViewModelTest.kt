package app.zenten.vault.ui.accounts

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Base64

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
        val other = SshKeyCodec.generateEncrypted(
            1_700_000_000_000_000L,
            "other".toCharArray(),
            rounds = SshKeyCodec.MIN_BCRYPT_PBKDF_ROUNDS,
        )
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

    @Test
    fun reports_the_declared_rounds_for_a_valid_key_outside_the_accepted_band() = runTest {
        val viewModel = viewModelFor(accountWith("pass"))
        // A real container re-labelled with an out-of-band work factor: structurally valid, so
        // the generic "unsupported key" message would misdescribe it.
        val outOfBand = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(
                SshKeyCodec.normalizeToSingleLineBase64(
                    SshKeyCodec.generateEncrypted(
                        1_700_000_000_000_000L,
                        "pass".toCharArray(),
                        rounds = SshKeyCodec.MIN_BCRYPT_PBKDF_ROUNDS,
                    ).privateKeyPem,
                ),
            ).withRounds(16),
        )

        val result = viewModel.confirmPastedAccountKey(outOfBand)

        assertEquals(AccountBackupConfirmationResult.UnsupportedKdfRounds(16), result)
    }

    private fun accountWith(passphrase: String): Account {
        // Generated at the floor rather than a fast test-only value: generation now refuses
        // anything below MIN_BCRYPT_PBKDF_ROUNDS, so a weaker fixture cannot be built at all.
        val generated = SshKeyCodec.generateEncrypted(
            1_700_000_000_000_000L,
            passphrase.toCharArray(),
            rounds = SshKeyCodec.MIN_BCRYPT_PBKDF_ROUNDS,
        )
        return Account(
            createdAt = generated.createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),
            accountName = "Work account",
        )
    }

    private fun viewModelFor(account: Account): LocalAccountBackupViewModel {
        val accountService = mockk<AccountService>()
        every { accountService.getAccount(account.createdAt) } returns account
        val viewModel = LocalAccountBackupViewModel(
            accountService,
            mockk<AppStorageService>(relaxed = true),
        )
        viewModel.load(account.createdAt)
        return viewModel
    }

    /** Rewrites only the plaintext four-byte bcrypt work factor inside an OpenSSH-v1 container. */
    private fun ByteArray.withRounds(rounds: Int): ByteArray {
        val magicSize = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII).size
        var offset = magicSize
        repeat(2) { offset += 4 + ByteBuffer.wrap(this, offset, 4).int } // cipher name, KDF name
        val optionsStart = offset + 4
        val roundsOffset = optionsStart + 4 + ByteBuffer.wrap(this, optionsStart, 4).int // skip the salt
        return copyOf().also { ByteBuffer.wrap(it).putInt(roundsOffset, rounds) }
    }
}
