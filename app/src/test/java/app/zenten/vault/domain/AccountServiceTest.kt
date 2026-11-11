package app.zenten.vault.domain

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class AccountServiceTest {
    @Test
    fun create_associated_account_generates_microsecond_id_comment_and_persists_through_setup() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Account(1, encryptedKey(VAULT_PASSPHRASE))
        every { storage.getAccounts() } returns emptyList()
        coJustRun { setup.addAccount(any(), any()) }

        val created = AccountService(storage, setup, appStorage())
            .createAssociatedAccount(VAULT_PASSPHRASE, "Work account")
            .getOrThrow()

        assertTrue(created.createdAt > 1_000_000_000_000_000L)
        assertTrue(created.createdAt % 1_000L in 1L..999L)
        assertEquals(created.createdAt.toString(), SshKeyCodec.parse(created.zenTenKey, VAULT_PASSPHRASE.toCharArray()).comment)
        assertTrue(SshKeyCodec.parse(created.zenTenKey, VAULT_PASSPHRASE.toCharArray()).encrypted)
        assertEquals("Work account", created.accountName)
        coVerify(exactly = 1) { setup.addAccount(any(), created) }
    }

    @Test
    fun create_associated_account_rejects_a_nonmatching_vault_key_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Account(1, encryptedKey(VAULT_PASSPHRASE))

        val result = AccountService(storage, setup, appStorage()).createAssociatedAccount("wrong-passphrase", "Work account")

        assertFalse(result.isSuccess)
        assertEquals("The passphrase does not match the vaultKey account", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }

    @Test
    fun verify_account_passphrase_accepts_that_accounts_own_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        every { storage.getAccountById(42L) } returns Account(42L, encryptedKey("account-passphrase"))

        val result = AccountService(storage, mockk<SetupService>(), appStorage()).verifyAccountPassphrase(42L, "account-passphrase")

        assertTrue(result.isSuccess)
    }

    @Test
    fun verify_account_passphrase_rejects_a_different_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        every { storage.getAccountById(42L) } returns Account(42L, encryptedKey("account-passphrase"))

        val result = AccountService(storage, mockk<SetupService>(), appStorage()).verifyAccountPassphrase(42L, "wrong-passphrase")

        assertFalse(result.isSuccess)
        assertEquals("The passphrase does not match this account", result.exceptionOrNull()?.message)
    }

    @Test
    fun verify_account_passphrase_reports_an_unavailable_account() = runTest {
        val storage = mockk<SecureStorageService>()
        every { storage.getAccountById(42L) } returns null

        val result = AccountService(storage, mockk<SetupService>(), appStorage()).verifyAccountPassphrase(42L, "account-passphrase")

        assertFalse(result.isSuccess)
        assertEquals("Account is unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun verify_account_passphrase_runs_its_bcrypt_check_off_the_calling_thread() = runTest {
        // The multi-second bcrypt work must not run on the caller's (main) thread. The account
        // read is the first thing the verifier does after switching dispatchers, so recording
        // the thread there proves the switch happened.
        val callerThread = Thread.currentThread().name
        // Atomic because the answer runs on the worker pool while the test thread waits.
        val workerThread = AtomicReference<String?>(null)
        val storage = mockk<SecureStorageService>()
        every { storage.getAccountById(42L) } answers {
            workerThread.set(Thread.currentThread().name)
            Account(42L, encryptedKey("account-passphrase"))
        }

        val result = AccountService(storage, mockk<SetupService>(), appStorage())
            .verifyAccountPassphrase(42L, "account-passphrase")

        assertTrue(result.isSuccess)
        assertNotNull(workerThread.get())
        assertNotEquals(callerThread, workerThread.get())
    }

    @Test
    fun create_associated_account_is_rejected_at_the_99_account_ceiling() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Account(1, encryptedKey(VAULT_PASSPHRASE))
        every { storage.getAccounts() } returns List(99) { Account(it + 1L, "key") }

        val result = AccountService(storage, setup, appStorage()).createAssociatedAccount(VAULT_PASSPHRASE, "Work account")

        assertFalse(result.isSuccess)
        assertEquals("You can create up to 99 associated accounts", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }

    @Test
    fun create_associated_account_requires_a_3_to_128_character_trimmed_name() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Account(1, encryptedKey(VAULT_PASSPHRASE))

        val result = AccountService(storage, setup, appStorage()).createAssociatedAccount(VAULT_PASSPHRASE, " ab ")

        assertFalse(result.isSuccess)
        assertEquals("Account name must be between 3 and 128 characters", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }

    private fun appStorage(rounds: Int = SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS) = mockk<AppStorageService> {
        every { sshKdfRounds } returns flowOf(rounds)
    }

    private companion object {
        const val VAULT_PASSPHRASE = "vaultKey-passphrase"

        /** A real passphrase-encrypted key, since verification is now a decryption. */
        fun encryptedKey(passphrase: String): String =
            SshKeyCodec.normalizeToSingleLineBase64(
                SshKeyCodec.generateEncrypted(1L, passphrase.toCharArray()).privateKeyPem,
            )
    }
}
