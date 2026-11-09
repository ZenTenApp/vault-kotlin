package app.zenten.vault.data.secure

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zenten.vault.data.model.Account
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
        val secret = "private-key-secret"
        storage.addVaultKey(account(1, key = secret))

        val raw = context.getSharedPreferences("vault_secure_accounts_v2", android.content.Context.MODE_PRIVATE)
        assertTrue(raw.all.values.none { value -> value is String && value.contains(secret) })
    }

    @Test
    fun encrypted_accounts_round_trip_across_service_recreation_in_id_order() {
        val vaultKey = account(1, key = "private-key-1")
        val second = account(20)
        val first = account(10)
        storage.addVaultKey(vaultKey)
        // Inserted out of ID order on purpose: the enumeration order comes from the numeric
        // account IDs, not from the order these records happened to be written.
        storage.addAccount(second)
        storage.addAccount(first)
        storage.addAccount(second) // replacing an existing ID stays idempotent

        val reloaded = SecureStorageService(context)
        assertEquals(vaultKey, reloaded.getVaultKey())
        assertEquals(listOf(10L, 20L), reloaded.getAccounts().map(Account::createdAt))

        reloaded.removeAccount(20)
        assertNull(reloaded.getAccountById(20))
        assertEquals(listOf(10L), reloaded.getAccounts().map(Account::createdAt))
        assertTrue(reloaded.getAccounts().none { it.createdAt == 20L })
    }

    /**
     * Enumeration is derived from the encrypted records, so a record is never dependent on a
     * separate index entry surviving alongside it. This is the regression guard for the
     * lost-update bug where two concurrent saves left one account invisible.
     */
    @Test
    fun every_written_record_is_enumerated_independently() {
        val ids = listOf(5L, 7L, 11L, 13L)
        ids.forEach { storage.addAccount(account(it)) }

        val reloaded = SecureStorageService(context)

        assertEquals(ids, reloaded.getAccounts().map(Account::createdAt))
        // Each ID remains individually retrievable, not just present in the aggregate list.
        ids.forEach { assertEquals(it, reloaded.getAccountById(it)?.createdAt) }
    }

    private fun account(id: Long, key: String = "private-key-$id") = Account(
        createdAt = id,
        zenTenKey = key,
    )
}
