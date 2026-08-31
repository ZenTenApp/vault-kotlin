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
        coJustRun { setup.addAccount(any(), any()) }

        val created = AccountService(storage, setup).createAssociatedAccount("master-passphrase").getOrThrow()

        assertTrue(created.createdAt > 1_000_000_000_000_000L)
        assertTrue(created.createdAt % 1_000L in 1L..999L)
        assertEquals(created.createdAt.toString(), SshKeyCodec.parse(created.zenTenKey, "master-passphrase".toCharArray()).comment)
        assertTrue(SshKeyCodec.parse(created.zenTenKey, "master-passphrase".toCharArray()).encrypted)
        assertEquals("master-passphrase", created.zenTenPass)
        assertTrue(created.pubkey!!.endsWith(" ${created.createdAt}"))
        coVerify(exactly = 1) { setup.addAccount(any(), created) }
    }

    @Test
    fun create_associated_account_rejects_a_nonmatching_master_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getMasterAccount() } returns Account(1, "master", zenTenPass = "master-passphrase")

        val result = AccountService(storage, setup).createAssociatedAccount("wrong-passphrase")

        assertFalse(result.isSuccess)
        assertEquals("The passphrase does not match the master account", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addAccount(any(), any()) }
    }


    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
