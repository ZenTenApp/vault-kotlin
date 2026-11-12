package app.zenten.vault.data.secure

import android.app.Application
import android.content.Context
import app.zenten.vault.crypto.KeystoreCryptoService
import app.zenten.vault.data.model.Account
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the account-record store without an Android Keystore.
 *
 * Encryption is replaced with a reversible in-memory transform so the test exercises the real
 * storage logic — key layout, enumeration, ordering and concurrent writes — against a real
 * `SharedPreferences` implementation, without depending on device-backed keys.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application keeps Robolectric from running VaultApplication's Hilt graph.
@Config(sdk = [34], application = Application::class)
class SecureStorageServiceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    // A singleton on purpose: it mirrors the app's single shared crypto component, and the
    // transform must round-trip identically for every service instance that opens this file.
    private val crypto = reversibleCrypto()
    private fun newStorage() = SecureStorageService(context, crypto)

    /**
     * Replaces AES-GCM with Base64 so any number of stored values can be read back.
     *
     * A plain Base64 payload is also a useful property here: it lets the concurrency test run
     * real threads without each write acquiring a Keystore cipher.
     */
    private fun reversibleCrypto(): KeystoreCryptoService = mockk {
        every { encrypt(any()) } answers {
            "enc:" + Base64.getEncoder().encodeToString(firstArg<String>().toByteArray())
        }
        every { decrypt(any()) } answers {
            val value = firstArg<String>()
            if (!value.startsWith("enc:")) null
            else String(Base64.getDecoder().decode(value.removePrefix("enc:")))
        }
        // Stands in for the wrap key being healthy: these tests are about the record store, and
        // the real Keystore is unavailable to host-side tests.
        every { isKeyPresent() } returns true
    }

    @Test
    fun accounts_are_listed_in_ascending_id_order_regardless_of_write_order() {
        val storage = newStorage()
        storage.addAccount(account(30))
        storage.addAccount(account(10))
        storage.addAccount(account(20))

        assertEquals(listOf(10L, 20L, 30L), storage.getAccounts().map(Account::createdAt))
    }

    @Test
    fun replacing_an_existing_id_does_not_duplicate_or_reorder() {
        val storage = newStorage()
        storage.addAccount(account(20))
        storage.addAccount(account(10))
        storage.addAccount(account(20))

        assertEquals(listOf(10L, 20L), storage.getAccounts().map(Account::createdAt))
    }

    @Test
    fun removal_leaves_other_records_intact() {
        val storage = newStorage()
        listOf(10L, 20L, 30L).forEach { storage.addAccount(account(it)) }

        storage.removeAccount(20)

        assertEquals(listOf(10L, 30L), storage.getAccounts().map(Account::createdAt))
        assertNull(storage.getAccountById(20))
        assertEquals(10L, storage.getAccountById(10)?.createdAt)
    }

    /**
     * Regression guard for the lost-update defect that the removed registry made possible.
     *
     * Previously each save appended to a separately stored ID list, so two concurrent writers
     * could both start from the same snapshot and the last write would drop the other account.
     * Every mutation is now a single preference edit, so no write can be lost by interleaving.
     *
     * The latch makes the overlap likely rather than merely possible, so the test is meaningful
     * even on a scheduler that would normally run these saves one after another.
     */
    @Test
    fun concurrent_saves_never_lose_an_account() {
        val writerCount = 16
        val startLine = CountDownLatch(1)
        val done = CountDownLatch(writerCount)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(writerCount) { index ->
            Thread {
                try {
                    startLine.await()
                    // A separate service instance per writer also proves the store is not relying
                    // on one object's in-memory state being the source of truth.
                    newStorage().addAccount(account(1_000L + index))
                } catch (error: Throwable) {
                    errors += error
                } finally {
                    done.countDown()
                }
            }.start()
        }

        startLine.countDown()
        check(done.await(10, TimeUnit.SECONDS)) { "Concurrent writers did not finish" }
        check(errors.isEmpty()) { "A concurrent writer failed: ${errors.first()}" }

        // Enumerate through a fresh instance so the assertion reads only durable state.
        val stored = newStorage().getAccounts().map(Account::createdAt)
        assertEquals((0 until writerCount).map { 1_000L + it }, stored)
    }

    /**
     * A record whose authenticated `createdAt` disagrees with the preference key that names it is
     * not trusted. This is the tamper case the registry used to make impossible to observe.
     */
    @Test
    fun record_claiming_a_different_id_than_its_key_is_ignored() {
        val storage = newStorage()
        storage.addAccount(account(10))
        val mismatched = Json.encodeToString(account(999, key = "smuggled"))
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("accounts_20", crypto.encrypt(mismatched))
            .commit()

        assertEquals(listOf(10L), storage.getAccounts().map(Account::createdAt))
        assertNull(storage.getAccountById(20))
    }

    @Test
    fun undecryptable_record_is_skipped_without_hiding_healthy_records() {
        val storage = newStorage()
        storage.addAccount(account(10))
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("accounts_20", "not-a-valid-payload")
            .commit()

        assertEquals(listOf(10L), storage.getAccounts().map(Account::createdAt))
    }

    @Test
    fun unrelated_preferences_are_not_mistaken_for_accounts() {
        val storage = newStorage()
        storage.addAccount(account(10))
        // A preference whose name merely shares the prefix must not be parsed as a record.
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("accounts_registry", crypto.encrypt("[20]"))
            .putString("accounts_", crypto.encrypt("{}"))
            .putString("accounts_x", crypto.encrypt("{}"))
            .commit()

        assertEquals(listOf(10L), storage.getAccounts().map(Account::createdAt))
    }

    @Test
    fun absent_vault_key_reports_absent_not_unreadable() {
        assertEquals(SecureRecordState.Absent, newStorage().getVaultKeyState())
    }

    @Test
    fun healthy_vault_key_reports_readable() {
        val storage = newStorage()
        storage.addVaultKey(account(1, key = "vault"))

        val state = newStorage().getVaultKeyState()
        assertEquals("vault", (state as SecureRecordState.Readable).record.zenTenKey)
    }

    /**
     * The device-transfer case this contract exists for: ciphertext is present and intact, but the
     * Android Keystore key that wraps it is gone, so it can never be decrypted on this device.
     *
     * Misreporting this as [SecureRecordState.Absent] is what strands a migrated user in an empty
     * vault, so the assertion here is deliberately about the *reason*, not just the failure.
     */
    @Test
    fun ciphertext_without_its_keystore_key_reports_keystore_key_missing() {
        val storage = newStorage()
        storage.addVaultKey(account(1, key = "vault"))

        // Simulate the migration: the stored bytes survive, the wrapping key does not.
        val keyless = mockk<KeystoreCryptoService> {
            every { encrypt(any()) } answers { crypto.encrypt(firstArg()) }
            every { decrypt(any()) } returns null
            every { isKeyPresent() } returns false
        }

        assertEquals(
            SecureRecordState.Unreadable(UnreadableReason.KeystoreKeyMissing),
            SecureStorageService(context, keyless).getVaultKeyState(),
        )
    }

    /**
     * A payload that fails with the key still present is a different problem: the device can still
     * read anything else it stored, so the fix is to rewrite or export from a backup, not to
     * migrate the vault. It must not be reported as a missing key.
     */
    @Test
    fun corrupt_payload_with_key_present_reports_payload_invalid() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()

        assertEquals(
            SecureRecordState.Unreadable(UnreadableReason.PayloadInvalid),
            newStorage().getVaultKeyState(),
        )
    }

    @Test
    fun undecryptable_vault_key_still_reports_null_record_for_existing_callers() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()

        assertNull(newStorage().getVaultKey())
    }

    /**
     * A stranded vault must survive the app doing ordinary work: reading state must never delete
     * or rewrite the ciphertext, because that copy may be the only local one the user has.
     */
    @Test
    fun reading_an_unreadable_vault_key_never_deletes_the_ciphertext() {
        val stored = "not-a-valid-payload"
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", stored)
            .commit()

        repeat(3) { newStorage().getVaultKeyState() }

        assertEquals(
            stored,
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .getString("vault_key", null),
        )
    }

    // --- Quarantine ------------------------------------------------------------------------

    /**
     * The core promise of the stranded-vault reset: the vault stops being served, and the
     * ciphertext is still on the device afterwards.
     *
     * Deleting it would be unrecoverable, and it may be the only local copy of a key whose owning
     * device is gone. The assertion checks both halves — absent to the app, present on disk —
     * because either one alone would pass while the feature was wrong.
     */
    @Test
    fun quarantining_moves_the_unreadable_vault_aside_without_deleting_it() {
        val stored = "not-a-valid-payload"
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", stored)
            .commit()
        val storage = newStorage()

        assertEquals(1, storage.quarantineUnreadableRecords())

        assertEquals(SecureRecordState.Absent, newStorage().getVaultKeyState())
        assertEquals(
            stored,
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .getString("quarantined_vault_key", null),
        )
    }

    @Test
    fun quarantining_leaves_readable_records_in_service() {
        val storage = newStorage()
        storage.addVaultKey(account(1, key = "vault"))
        storage.addAccount(account(10))

        assertEquals(0, storage.quarantineUnreadableRecords())

        assertEquals("vault", newStorage().getVaultKey()?.zenTenKey)
        assertEquals(listOf(10L), newStorage().getAccounts().map(Account::createdAt))
    }

    @Test
    fun quarantining_takes_only_the_unreadable_records() {
        val storage = newStorage()
        storage.addAccount(account(10))
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("accounts_20", "not-a-valid-payload")
            .commit()

        assertEquals(1, newStorage().quarantineUnreadableRecords())

        // The healthy record is untouched; the broken one is no longer enumerated as live.
        assertEquals(listOf(10L), newStorage().getAccounts().map(Account::createdAt))
        assertEquals(
            "not-a-valid-payload",
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .getString("quarantined_accounts_20", null),
        )
    }

    /**
     * A quarantined record must never be enumerated as an account. `accounts_` parsing rejects a
     * non-numeric suffix and the quarantine prefix differs anyway; this pins both.
     */
    @Test
    fun quarantined_records_are_never_listed_as_accounts() {
        val storage = newStorage()
        storage.addAccount(account(10))
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("quarantined_accounts_30", crypto.encrypt(Json.encodeToString(account(30))))
            .commit()

        assertEquals(listOf(10L), newStorage().getAccounts().map(Account::createdAt))
    }

    @Test
    fun quarantining_reports_that_records_are_held() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()
        val storage = newStorage()

        assertEquals(false, storage.hasQuarantinedRecords())
        storage.quarantineUnreadableRecords()
        assertEquals(true, newStorage().hasQuarantinedRecords())
    }

    // --- Unreadable-record detection -------------------------------------------------------

    @Test
    fun a_healthy_store_has_no_unreadable_records() {
        val storage = newStorage()

        assertEquals(false, storage.hasUnreadableRecords())

        storage.addVaultKey(account(1, key = "vault"))
        storage.addAccount(account(10))

        assertEquals(false, newStorage().hasUnreadableRecords())
    }

    @Test
    fun an_unreadable_vault_key_is_reported_as_an_unreadable_record() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()

        assertEquals(true, newStorage().hasUnreadableRecords())
    }

    /**
     * The partial-transfer case: account ciphertext arrives, the VaultKey record does not.
     *
     * Without this, the gate sees an absent VaultKey, reports a fresh install, and onboarding
     * silently orphans the records that are still on disk.
     */
    @Test
    fun unreadable_account_records_are_detected_without_a_vault_key() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("accounts_20", "not-a-valid-payload")
            .commit()
        val storage = newStorage()

        assertEquals(SecureRecordState.Absent, storage.getVaultKeyState())
        assertEquals(true, storage.hasUnreadableRecords())
    }

    /**
     * Set-aside records must stop counting, or the launch gate would keep sending the user back to
     * the recovery screen after they cleared it.
     */
    @Test
    fun quarantined_records_no_longer_count_as_unreadable() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()
        val storage = newStorage()

        storage.quarantineUnreadableRecords()

        assertEquals(false, newStorage().hasUnreadableRecords())
    }

    @Test
    fun clear_all_returns_the_vault_key_to_absent() {
        val storage = newStorage()
        storage.addVaultKey(account(1, key = "vault"))
        storage.addAccount(account(10))

        storage.clearAll()

        assertEquals(SecureRecordState.Absent, newStorage().getVaultKeyState())
        assertEquals(emptyList<Long>(), newStorage().getAccounts().map(Account::createdAt))
    }

    private fun account(id: Long, key: String = "private-key-$id") = Account(
        createdAt = id,
        zenTenKey = key,
    )

    private companion object {
        // Mirrors SecureStorageService.FILE_NAME, which is private to the implementation.
        const val FILE_NAME = "vault_secure_accounts_v2"
    }
}
