package app.zenten.vault.data.secure

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an Android runtime to verify Keystore-backed account persistence across service recreation. */
@RunWith(AndroidJUnit4::class)
class SecureStorageServiceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storage = SecureStorageService(context)

    @After fun cleanup() = storage.clearAll()

    @Test
    fun stored_preferences_do_not_expose_plaintext_secrets() {
        val secret = "very-secret-passphrase"
        storage.addVaultKey(account(1, passphrase = secret))

        val raw = context.getSharedPreferences("vault_secure_accounts_v2", android.content.Context.MODE_PRIVATE)
        assertTrue(raw.all.values.none { value -> value is String && value.contains(secret) })
    }

    @Test
    fun encrypted_accounts_round_trip_across_service_recreation_and_registry_is_ordered() {
        val vaultKey = account(1, passphrase = "secret", nostr = NostrKeyPair("pub", "priv"))
        val second = account(20)
        val first = account(10)
        storage.addVaultKey(vaultKey)
        storage.addAccount(second)
        storage.addAccount(first)
        storage.addAccount(second) // idempotent registry insertion

        val reloaded = SecureStorageService(context)
        assertEquals(vaultKey, reloaded.getVaultKey())
        assertEquals(listOf(20L, 10L), reloaded.getAccounts().map(Account::createdAt))

        reloaded.removeAccount(20)
        assertNull(reloaded.getAccountById(20))
        assertEquals(listOf(10L), reloaded.getAccounts().map(Account::createdAt))
        assertTrue(reloaded.getAccounts().none { it.createdAt == 20L })
    }

    private fun account(id: Long, passphrase: String = "pass", nostr: NostrKeyPair = NostrKeyPair("00".repeat(32), "01".repeat(32))) = Account(
        createdAt = id,
        zenTenKey = "private-key-$id",
        zenTenPass = passphrase,
        nostr = nostr,
    )
}
