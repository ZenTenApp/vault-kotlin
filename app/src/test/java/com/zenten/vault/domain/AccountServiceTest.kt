package com.zenten.vault.domain

import com.zenten.vault.crypto.NostrKeyDerivation
import com.zenten.vault.crypto.SshKeyCodec
import com.zenten.vault.data.kv.AppStorageService
import com.zenten.vault.data.model.Account
import com.zenten.vault.data.model.NostrKeyPair
import com.zenten.vault.data.secure.SecureStorageService
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountServiceTest {
    @Test
    fun create_sub_account_generates_microsecond_id_comment_and_persists_through_setup() = runTest {
        val storage = mockk<SecureStorageService>()
        val app = mockk<AppStorageService>()
        val setup = mockk<SetupService>()
        val keys = NostrKeyDerivation.derive("alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady")
        every { storage.getMasterAccount() } returns Account(1, "master", nostr = NostrKeyPair(keys.publicKey.hex(), keys.privateKey.hex()))
        coJustRun { setup.addAccount(any(), any()) }

        val created = AccountService(storage, app, setup).createSubAccount().getOrThrow()

        assertTrue(created.createdAt > 1_000_000_000_000_000L)
        assertEquals(created.createdAt.toString(), SshKeyCodec.parse(created.zenTenKey).comment)
        assertTrue(created.pubkey!!.endsWith(" ${created.createdAt}"))
        coVerify(exactly = 1) { setup.addAccount(any(), created) }
    }

    @Test
    fun reset_clears_secure_and_non_secret_state() = runTest {
        val storage = mockk<SecureStorageService>()
        val app = mockk<AppStorageService>()
        val setup = mockk<SetupService>()
        every { storage.clearAll() } just runs
        coJustRun { app.clearSetupComplete() }
        coJustRun { app.clearFailedBackupAccountIds() }

        AccountService(storage, app, setup).resetVault()

        verify { storage.clearAll() }
        coVerify { app.clearSetupComplete(); app.clearFailedBackupAccountIds() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
