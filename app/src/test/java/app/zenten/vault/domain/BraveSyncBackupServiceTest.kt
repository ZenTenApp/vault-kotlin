package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the Brave Sync write/read contract that [BraveSyncBackupService] owns.
 *
 * Three properties matter here and each has a way to silently regress:
 *
 * - **The labelled-note format carries the account name.** The name is not stored anywhere else on
 *   an associated-account record, so a write that omits the note (or formats it differently) loses
 *   the name on the next device. Only the current `label:<name>` spelling is read; the format has
 *   not shipped, so no legacy spelling is accepted.
 * - **A standalone backup is idempotent.** Running it twice against a record it already wrote must
 *   succeed, and it may only fail when the existing key or label really differs. Comparing a
 *   decoded label against a still-prefixed one made that check always fail, so repeat backups were
 *   rejected even though nothing had changed.
 * - **A restored record must actually decrypt under the session passphrase.** Accepting a record
 *   that merely parses meant a key encrypted with another passphrase (for example a different
 *   Vault session) could be restored into an account that could never be unlocked.
 */
class BraveSyncBackupServiceTest {

    private fun service(passwordService: BravePasswordService = mockk<BravePasswordService>()): BraveSyncBackupService =
        BraveSyncBackupService(passwordService)

    @Test
    fun repeat_standalone_backup_of_a_named_account_succeeds_when_the_record_matches() = runTest {
        // The record was written by an earlier run of this same standalone backup, so its key and
        // label are exactly what this call would write. It must be a success, not a conflict.
        val existing = record(ACCOUNT_ID, ACCOUNT_KEY, "label:Work account")
        // Guard the fixture: the record must carry the canonical key and the exact current note, so
        // the success below proves the comparison, not a vacuously empty record.
        assertEquals(ACCOUNT_KEY, existing.password)
        assertEquals(listOf(BravePasswordService.Note("label:Work account")), existing.notes)

        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult(existing)
        val result = service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "Work account"),
            PASSPHRASE,
        )

        assertTrue(result.isSuccess, "a matching repeat backup must not be treated as a conflict")
        // No new record should be created when the existing one already matches.
        // matchNullable is required for the note slot: any() is typed and would not match a null note.
        verify(exactly = 0) { passwordService.create(any(), any(), any(), any(), matchNullable<String> { true }) }
    }

    @Test
    fun spaced_note_is_not_a_vault_label_so_the_repeat_backup_conflicts() = runTest {
        // The spaced spelling "label: Work account" is not the format Vault writes and is not
        // accepted on read. It yields no decoded label, which differs from the account's name, so
        // the repeat backup must conflict rather than be silently treated as a match.
        val existing = record(ACCOUNT_ID, ACCOUNT_KEY, "label: Work account")

        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult(existing)
        val result = service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "Work account"),
            PASSPHRASE,
        )

        assertTrue(result.isFailure)
        verify(exactly = 0) { passwordService.create(any(), any(), any(), any(), matchNullable<String> { true }) }
    }

    @Test
    fun decode_ignores_a_note_that_is_not_a_vault_label() = runTest {
        // A note can come from any Brave Sync client, so the reader must not guess a name out of
        // free text. Only the exact `label:<name>` form supplies one; anything else leaves the
        // restored account nameless instead of inventing a label from a plain note.
        val entry = record(ACCOUNT_ID, ACCOUNT_KEY, "Work account")

        val restored = requireNotNull(service().decodeAssociatedAccount(entry, PASSPHRASE))

        assertNull(restored.accountName)
    }

    @Test
    fun different_label_in_an_existing_record_fails_and_names_the_account() = runTest {
        // Same key but a different name means the user has two distinct accounts sharing one
        // record slot; overwriting would silently discard one of them, so the write is refused.
        val existing = record(ACCOUNT_ID, ACCOUNT_KEY, "label:Personal")

        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult(existing)
        val result = service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "Work account"),
            PASSPHRASE,
        )

        assertTrue(result.isFailure)
        assertEquals(
            "Brave Sync already contains a different Vault backup for $ACCOUNT_ID",
            result.exceptionOrNull()?.message,
        )
        verify(exactly = 0) { passwordService.create(any(), any(), any(), any(), matchNullable<String> { true }) }
    }

    @Test
    fun different_key_in_an_existing_record_fails() = runTest {
        // The label matches, but the key belongs to a different account; writing would replace a
        // Vault backup the user still relies on. The mismatch must be reported, not overwritten.
        val existing = record(ACCOUNT_ID, DIFFERENT_ACCOUNT_KEY, "label:Work account")

        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult(existing)
        val result = service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "Work account"),
            PASSPHRASE,
        )

        assertTrue(result.isFailure)
        assertEquals(
            "Brave Sync already contains a different Vault backup for $ACCOUNT_ID",
            result.exceptionOrNull()?.message,
        )
        verify(exactly = 0) { passwordService.create(any(), any(), any(), any(), matchNullable<String> { true }) }
    }

    @Test
    fun nameless_account_succeeds_when_the_existing_record_has_no_note() = runTest {
        // A nameless account has no label to store, so "no note" on both sides is a match. This is
        // the counterpart of the named case: absence must compare equal to absence.
        val existing = record(ACCOUNT_ID, ACCOUNT_KEY, note = null)

        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult(existing)
        val result = service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = null),
            PASSPHRASE,
        )

        assertTrue(result.isSuccess)
        // matchNullable so a create with a null note (the nameless case) would still be counted.
        verify(exactly = 0) { passwordService.create(any(), any(), any(), any(), matchNullable<String> { true }) }
    }

    @Test
    fun named_account_is_written_with_the_labelled_note_without_a_space() = runTest {
        // The write side defines the format every reader depends on: exactly one note, prefixed
        // "label:" with no space, so the decoded value is the trimmed account name. The name is
        // multi-word and its internal space is part of the label, not formatting.
        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult()
        every { passwordService.create(any(), any(), any(), any(), any()) } returns
            record(ACCOUNT_ID, ACCOUNT_KEY, "label:Work account")

        service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "Work account"),
            PASSPHRASE,
        ).getOrThrow()

        verify(exactly = 1) {
            passwordService.create(
                any(),
                any(),
                any(),
                any(),
                match<String> { it == "label:Work account" },
            )
        }
    }

    @Test
    fun a_name_with_surrounding_whitespace_does_not_put_a_space_after_the_prefix() = runTest {
        // Only the outside of the name is trimmed. Without this, a name entered with a leading
        // space would be stored as "label: Work account" and no longer match the format readers
        // accept, so the same account would restore without its name.
        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult()
        every { passwordService.create(any(), any(), any(), any(), any()) } returns
            record(ACCOUNT_ID, ACCOUNT_KEY, "label:Work account")

        service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "  Work account  "),
            PASSPHRASE,
        ).getOrThrow()

        // The internal space survives; the surrounding ones do not.
        verify(exactly = 1) {
            passwordService.create(
                any(),
                any(),
                any(),
                any(),
                match<String> { it == "label:Work account" },
            )
        }
    }

    @Test
    fun blank_account_name_writes_no_note() = runTest {
        // A whitespace-only name is treated as nameless: storing "label:   " would make the record
        // look labelled while restoring no usable name.
        val passwordService = mockk<BravePasswordService>()
        every { passwordService.list(any()) } returns listResult()
        // `any()` is type-checked and rejects null, so the note slot needs the null matcher.
        every { passwordService.create(any(), any(), any(), any(), isNull<String>()) } returns
            record(ACCOUNT_ID, ACCOUNT_KEY, note = null)

        service(passwordService).backupAssociatedAccount(
            VAULT_KEY,
            account(accountName = "   "),
            PASSPHRASE,
        ).getOrThrow()

        // Null (not the empty string) is what the reader expects for "no label".
        verify(exactly = 1) { passwordService.create(any(), any(), any(), any(), isNull<String>()) }
    }

    @Test
    fun decode_restores_the_account_name_from_the_label_note() = runTest {
        // The successful read path: a key encrypted under the session passphrase opens, and the
        // note's label comes back as the account name so the restored card is identifiable. A
        // multi-word name keeps the space after the prefix, since it is part of the name.
        val entry = record(ACCOUNT_ID, ACCOUNT_KEY, "label:Work account")

        val restored = requireNotNull(service().decodeAssociatedAccount(entry, PASSPHRASE))

        assertEquals("Work account", restored.accountName)
        assertEquals(ACCOUNT_ID, restored.createdAt)
        assertEquals(ACCOUNT_KEY, restored.zenTenKey)
    }

    @Test
    fun decode_returns_null_when_the_key_was_encrypted_with_a_different_passphrase() = runTest {
        // Regression: the record passes the identity check (numeric ID, right URL, one note) and
        // parses structurally, but its key was encrypted under another passphrase. Restoring it
        // would produce an account the app can never unlock, so the read must reject it.
        val entry = record(ACCOUNT_ID, WRONG_PASSPHRASE_KEY, "label:Work account")

        // Prove the rejection comes from decryptability, not identity: accountId() still accepts it.
        // accountId() is String-typed, so compare against the string form rather than the Long.
        assertEquals(ACCOUNT_ID.toString(), service().accountId(entry))
        assertNull(service().decodeAssociatedAccount(entry, PASSPHRASE))
    }

    @Test
    fun decode_returns_null_for_the_vaultkey_record() = runTest {
        // Normal recovery restores associated accounts only; the Vault Key record has a non-numeric
        // ID and is handled by a separate path, so decoding it here must fail cleanly.
        val entry = BravePasswordService.Password(
            id = "vault-record",
            version = 1L,
            clientTagHash = "vault-tag",
            url = "https://vaultkey.zenten/",
            username = BraveSyncBackupService.VAULT_KEY_ID,
            password = VAULT_KEY.zenTenKey,
            notes = emptyList(),
        )

        assertNull(service().decodeAssociatedAccount(entry, PASSPHRASE))
    }

    @Test
    fun account_id_accepts_zero_or_one_note_and_rejects_two() {
        // An associated record is identified by its numeric username and matching URL. Zero notes
        // is the nameless form and one note carries the label; two notes cannot come from Vault and
        // are treated as an unrecognized record.
        assertEquals(ACCOUNT_ID.toString(), service().accountId(record(ACCOUNT_ID, ACCOUNT_KEY, note = null)))
        assertEquals(
            ACCOUNT_ID.toString(),
            service().accountId(record(ACCOUNT_ID, ACCOUNT_KEY, "label:Work account")),
        )

        val twoNotes = record(ACCOUNT_ID, ACCOUNT_KEY, "label:Work account").let {
            it.copy(notes = it.notes + BravePasswordService.Note("label:Second"))
        }
        assertNull(service().accountId(twoNotes))
    }

    /** Wraps entries in the shape [BravePasswordService.list] returns; no decryption failed. */
    private fun listResult(vararg entries: BravePasswordService.Password): BravePasswordService.ListResult =
        BravePasswordService.ListResult(entries.toList(), decryptionFailures = 0)

    private fun record(createdAt: Long, key: String, note: String?): BravePasswordService.Password =
        BravePasswordService.Password(
            id = "record-$createdAt",
            version = 1L,
            clientTagHash = "tag-$createdAt",
            // The service matches an existing record by URL and username, both derived from the ID.
            url = "https://$createdAt.zenten/",
            username = createdAt.toString(),
            password = key,
            notes = listOfNotNull(note?.let { BravePasswordService.Note(it) }),
        )

    private fun account(accountName: String?): Account =
        Account(createdAt = ACCOUNT_ID, zenTenKey = ACCOUNT_KEY, accountName = accountName)

    private companion object {
        // One passphrase for the whole suite: the service decrypts the Vault Key to derive the Sync
        // code and parses each account key, so every fixture key must be encrypted with it.
        val PASSPHRASE = "pass".toCharArray()

        // Used only to build a key that structurally parses but cannot be opened with PASSPHRASE.
        val WRONG_PASSPHRASE = "other".toCharArray()

        const val ACCOUNT_ID = 10L

        fun encryptedKey(createdAt: Long, passphrase: CharArray): String =
            SshKeyCodec.normalizeToSingleLineBase64(
                SshKeyCodec.generateEncrypted(createdAt, passphrase).privateKeyPem,
            )

        // Keys are costly to generate, and JUnit creates a fresh test instance for each method, so
        // they live in the companion and are computed once when the class loads. Account and String
        // are immutable, so sharing them across tests is safe.
        val VAULT_KEY = Account(createdAt = 1L, zenTenKey = encryptedKey(1L, PASSPHRASE))
        val ACCOUNT_KEY = encryptedKey(ACCOUNT_ID, PASSPHRASE)
        // Any other key that opens under PASSPHRASE will do; reusing the Vault Key avoids another
        // expensive bcrypt generation and is still distinct from ACCOUNT_KEY.
        val DIFFERENT_ACCOUNT_KEY = VAULT_KEY.zenTenKey
        val WRONG_PASSPHRASE_KEY = encryptedKey(ACCOUNT_ID, WRONG_PASSPHRASE)
    }
}
