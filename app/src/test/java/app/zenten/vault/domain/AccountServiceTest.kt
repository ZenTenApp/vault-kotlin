package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountServiceTest {
    @Test
    fun create_associated_account_generates_microsecond_id_comment_and_persists_through_setup() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        val keys = NostrKeyDerivation.derive("alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady")
        every { storage.getMasterAccount() } returns Account(1, "master", zenTenPass = "master-passphrase", nostr = NostrKeyPair(keys.publicKey.hex(), keys.privateKey.hex()))
        every { storage.getAccounts() } returns emptyList()
        coJustRun { setup.addAccount(any(), any()) }

        val created = AccountService(storage, setup)
            .createAssociatedAccount("master-passphrase", "Work account")
            .getOrThrow()

        assertTrue(created.createdAt > 1_000_000_000_000_000L)
        assertTrue(created.createdAt % 1_000L in 1L..999L)
        assertEquals(created.createdAt.toString(), SshKeyCodec.parse(created.zenTenKey, "master-passphrase".toCharArray()).comment)
        assertTrue(SshKeyCodec.parse(created.zenTenKey, "master-passphrase".toCharArray()).encrypted)
        assertEquals("master-passphrase", created.zenTenPass)
        assertEquals("Work account", created.accountName)
        assertTrue(created.pubkey!!.endsWith(" ${created.createdAt}"))
        assertEquals(
            NostrKeyDerivation.derive(
                app.zenten.vault.crypto.Seedify.fromSshKey(created.zenTenKey, "master-passphrase".toCharArray()),
            ).publicKey.hex(),
            created.nostr.pubkey,
        )
        coVerify(exactly = 1) { setup.addAccount(any(), created) }
    }

    @Test
    fun create_associated_account_rejects_a_nonmatching_master_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getMasterAccount() } returns Account(1, "master", zenTenPass = "master-passphrase", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))

        val result = AccountService(storage, setup).createAssociatedAccount("wrong-passphrase", "Work account")

        assertFalse(result.isSuccess)
        assertEquals("The passphrase does not match the master account", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }

    @Test
    fun create_associated_account_is_rejected_at_the_99_account_ceiling() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getMasterAccount() } returns Account(1, "master", zenTenPass = "master-passphrase", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))
        every { storage.getAccounts() } returns List(99) { Account(it + 1L, "account", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32))) }

        val result = AccountService(storage, setup).createAssociatedAccount("master-passphrase", "Work account")

        assertFalse(result.isSuccess)
        assertEquals("You can create up to 99 associated accounts", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }

    @Test
    fun create_associated_account_requires_an_8_to_128_character_trimmed_name() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getMasterAccount() } returns Account(1, "master", zenTenPass = "master-passphrase", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))

        val result = AccountService(storage, setup).createAssociatedAccount("master-passphrase", " short ")

        assertFalse(result.isSuccess)
        assertEquals("Account name must be between 8 and 128 characters", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }


    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
