package app.zenten.vault.domain

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Key
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

class KeyServiceTest {
    @Test
    fun create_associated_key_generates_microsecond_id_comment_and_persists_through_setup() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Key(1, encryptedKey(VAULT_PASSPHRASE))
        every { storage.getKeys() } returns emptyList()
        coJustRun { setup.addKey(any(), any()) }

        val created = KeyService(storage, setup, appStorage())
            .createAssociatedKey(VAULT_PASSPHRASE, "Work key")
            .getOrThrow()

        assertTrue(created.createdAt > 1_000_000_000_000_000L)
        assertTrue(created.createdAt % 1_000L in 1L..999L)
        assertEquals(created.createdAt.toString(), SshKeyCodec.parse(created.zenTenKey, VAULT_PASSPHRASE.toCharArray()).comment)
        assertTrue(SshKeyCodec.parse(created.zenTenKey, VAULT_PASSPHRASE.toCharArray()).encrypted)
        assertEquals("Work key", created.keyName)
        coVerify(exactly = 1) { setup.addKey(any(), created) }
    }

    @Test
    fun create_associated_key_rejects_a_nonmatching_vault_key_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Key(1, encryptedKey(VAULT_PASSPHRASE))

        val result = KeyService(storage, setup, appStorage()).createAssociatedKey("wrong-passphrase", "Work key")

        assertFalse(result.isSuccess)
        assertEquals("The passphrase does not match the vaultKey", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addKey(any(), any()) }
    }

    @Test
    fun verify_key_passphrase_accepts_that_keys_own_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        every { storage.getKeyById(42L) } returns Key(42L, encryptedKey("key-passphrase"))

        val result = KeyService(storage, mockk<SetupService>(), appStorage()).verifyKeyPassphrase(42L, "key-passphrase")

        assertTrue(result.isSuccess)
    }

    @Test
    fun verify_key_passphrase_rejects_a_different_passphrase() = runTest {
        val storage = mockk<SecureStorageService>()
        every { storage.getKeyById(42L) } returns Key(42L, encryptedKey("key-passphrase"))

        val result = KeyService(storage, mockk<SetupService>(), appStorage()).verifyKeyPassphrase(42L, "wrong-passphrase")

        assertFalse(result.isSuccess)
        assertEquals("The passphrase does not match this key", result.exceptionOrNull()?.message)
    }

    @Test
    fun verify_key_passphrase_reports_an_unavailable_key() = runTest {
        val storage = mockk<SecureStorageService>()
        every { storage.getKeyById(42L) } returns null

        val result = KeyService(storage, mockk<SetupService>(), appStorage()).verifyKeyPassphrase(42L, "key-passphrase")

        assertFalse(result.isSuccess)
        assertEquals("Key is unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun verify_key_passphrase_runs_its_bcrypt_check_off_the_calling_thread() = runTest {
        // The multi-second bcrypt work must not run on the caller's (main) thread. The key
        // read is the first thing the verifier does after switching dispatchers, so recording
        // the thread there proves the switch happened.
        val callerThread = Thread.currentThread().name
        // Atomic because the answer runs on the worker pool while the test thread waits.
        val workerThread = AtomicReference<String?>(null)
        val storage = mockk<SecureStorageService>()
        every { storage.getKeyById(42L) } answers {
            workerThread.set(Thread.currentThread().name)
            Key(42L, encryptedKey("key-passphrase"))
        }

        val result = KeyService(storage, mockk<SetupService>(), appStorage())
            .verifyKeyPassphrase(42L, "key-passphrase")

        assertTrue(result.isSuccess)
        assertNotNull(workerThread.get())
        assertNotEquals(callerThread, workerThread.get())
    }

    @Test
    fun create_associated_key_is_rejected_at_the_99_key_ceiling() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Key(1, encryptedKey(VAULT_PASSPHRASE))
        every { storage.getKeys() } returns List(99) { Key(it + 1L, "key") }

        val result = KeyService(storage, setup, appStorage()).createAssociatedKey(VAULT_PASSPHRASE, "Work key")

        assertFalse(result.isSuccess)
        assertEquals("You can create up to 99 associated keys", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addKey(any(), any()) }
    }

    @Test
    fun create_associated_key_requires_a_3_to_128_character_trimmed_name() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Key(1, encryptedKey(VAULT_PASSPHRASE))

        val result = KeyService(storage, setup, appStorage()).createAssociatedKey(VAULT_PASSPHRASE, " ab ")

        assertFalse(result.isSuccess)
        assertEquals("Key name must be between 3 and 128 characters", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { setup.addKey(any(), any()) }
    }

    @Test
    fun create_associated_key_rejects_the_vault_key_term_in_any_spelling() = runTest {
        val storage = mockk<SecureStorageService>()
        val setup = mockk<SetupService>()
        every { storage.getVaultKey() } returns Key(1, encryptedKey(VAULT_PASSPHRASE))

        for (name in listOf("Vault Key", "vaultkey", "VAULT KEY", "vault-key", "My VaultKey wallet", "v a u l t k e y", "Vault Key", "ｖａｕｌｔｋｅｙ")) {
            val result = KeyService(storage, setup, appStorage()).createAssociatedKey(VAULT_PASSPHRASE, name)

            assertFalse(result.isSuccess, name)
            assertEquals("Key name cannot contain \"vault key\"", result.exceptionOrNull()?.message, name)
        }
        coVerify(exactly = 0) { setup.addKey(any(), any()) }
    }

    @Test
    fun reserved_term_check_allows_names_that_only_look_similar() {
        // "vault" and "key" apart, or other words, are fine; the check only matches the joined term.
        for (name in listOf("Vault server", "Key server", "Vault keeper", "Work key", "vaul key")) {
            assertFalse(KeyService.containsReservedKeyNameTerm(name), name)
        }
        assertTrue(KeyService.containsReservedKeyNameTerm("vault​KEY"))
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
