package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores Vault SSH keys in the password-only Brave Sync adapter.
 *
 * The vaultKey account derives the Sync code; records are deliberately limited to
 * canonical single-line OpenSSH private-key Base64 values. Associated-account records also
 * carry the account label as a note so recovery can restore the name without local state;
 * the Vault Key record never has a note because the Vault Key has no user-set name.
 */
class BraveSyncBackupService(
    // No default transport: a Brave source always names the endpoint it belongs to.
    private val passwordService: BravePasswordService,
    private val runSyncCode: String? = null,
) {
    suspend fun backupVaultKey(vaultKey: Account, passphrase: CharArray): Result<Unit> = backup(vaultKey, VAULT_KEY_ID, passphrase)

    suspend fun backupAssociatedAccount(vaultKey: Account, account: Account, passphrase: CharArray): Result<Unit> =
        backup(vaultKey, account.createdAt.toString(), passphrase, account)

    /** Lists decrypted record metadata/payloads. SSH key parsing remains deferred to restoration. */
    suspend fun listRecords(vaultKey: Account, passphrase: CharArray): Result<BravePasswordService.ListResult> = withContext(Dispatchers.IO) {
        runCatching { passwordService.list(syncCode(vaultKey, passphrase)) }
    }

    fun accountId(entry: BravePasswordService.Password): String? = when {
        entry.username == VAULT_KEY_ID && entry.url == accountUrl(VAULT_KEY_ID) && entry.notes.isEmpty() -> VAULT_KEY_ID
        entry.username.toLongOrNull() != null && entry.url == accountUrl(entry.username) && entry.notes.size <= 1 -> entry.username
        else -> null
    }

    /**
     * Decodes one associated-account record, or returns `null` when intake fails.
     *
     * A record that does not open with [passphrase] returns `null`, so callers skip it rather
     * than persisting an account that could never be unlocked or exported. The Vault Key record
     * is also rejected here: normal recovery deliberately restores associated accounts only.
     */
    fun decodeAssociatedAccount(entry: BravePasswordService.Password, passphrase: CharArray): Account? = runCatching {
        // The vaultkey record has no numeric ID, so it is rejected here: normal recovery
        // deliberately restores associated accounts only.
        val accountId = requireNotNull(accountId(entry)?.toLongOrNull())
        // Metadata-only checks (numeric ID, container structure, work-factor band) can pass on a
        // container encrypted under a different passphrase, because the private section is
        // AES-CTR and authenticates nothing. Only actually decrypting the key proves the restored
        // account is usable.
        val key = canonicalKey(entry.password, passphrase)
        // The note is the only place the account name survives a device change; a missing or
        // blank one leaves the name unset, so the restored card shows no name.
        accountFromSshKey(accountId, key, entry.notesText())
    }.getOrNull()

    /** Checks the vaultKey record separately because normal recovery deliberately restores associated accounts only. */
    suspend fun hasVaultKeyBackup(vaultKey: Account, passphrase: CharArray): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            passwordService.list(syncCode(vaultKey, passphrase)).passwords.any { entry ->
                entry.username == VAULT_KEY_ID && entry.url == accountUrl(VAULT_KEY_ID) &&
                    entry.notes.isEmpty() &&
                    runCatching { canonicalKey(entry.password, passphrase) }.isSuccess
            }
        }
    }

    suspend fun discoverAssociatedAccounts(vaultKey: Account, passphrase: CharArray): RecoveryResult = withContext(Dispatchers.IO) {
        val syncCode = syncCode(vaultKey, passphrase)
        val discovered = passwordService.list(syncCode)
        val valid = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        discovered.passwords.forEach { entry ->
            val accountId = accountId(entry)?.toLongOrNull()
            if (accountId == null) {
                skipped += entry.id
                return@forEach
            }
            val account = decodeAssociatedAccount(entry, passphrase)
            if (account == null) skipped += entry.id else valid += account
        }
        RecoveryResult(valid, skipped, discovered.decryptionFailures, discovered.passwords.size)
    }

    private suspend fun backup(vaultKey: Account, accountId: String, passphrase: CharArray, account: Account = vaultKey): Result<Unit> =
        // The Vault Key record never carries a note; only associated accounts do.
        backupRecord(vaultKey, accountId, passphrase, account, if (accountId == VAULT_KEY_ID) null else accountLabel(account))

    /**
     * Writes one record. [label] is the plain account label to store, or `null` for no note.
     *
     * The Vault Key record always passes `null`: its readers require an empty note, and the
     * Vault Key has no user-set name to publish.
     */
    private suspend fun backupRecord(
        vaultKey: Account,
        accountId: String,
        passphrase: CharArray,
        account: Account,
        label: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val syncCode = syncCode(vaultKey, passphrase)
                val url = accountUrl(accountId)
                val key = canonicalKey(account.zenTenKey, passphrase)
                // The stored note carries the self-describing prefix; [label] stays plain so the
                // idempotency check can compare it against a decoded note.
                val note = label?.let { "$ACCOUNT_NOTE_PREFIX$it" }
                if (runSyncCode != null) {
                    // The engine invokes write only after this source's index proved the ID
                    // absent, so another list/refetch would duplicate network work.
                    passwordService.createKnownMissing(syncCode, url, accountId, key, note)
                } else {
                    // Standalone callers do not provide an index, so retain the idempotency check.
                    val existing = passwordService.list(syncCode).passwords.firstOrNull {
                        it.url == url && it.username == accountId
                    }
                    if (existing == null) passwordService.create(syncCode, url, accountId, key, note)
                    // Compare the decoded label against the plain [label] rather than against the
                    // prefixed [note], so a repeat backup of an unchanged account stays idempotent
                    // while a genuinely different name still conflicts.
                    else require(existing.password == key && existing.notesText() == label) {
                        "Brave Sync already contains a different Vault backup for $accountId"
                    }
                }
                Unit
            }
        }

    /**
     * Builds the plain account label for an associated account, or `null` when it has no name.
     *
     * Only the *outside* of the name is trimmed: leading and trailing whitespace is removed so
     * the stored value never begins with a space directly after the prefix, while spaces inside
     * the name are kept, because a label may be multi-word ("Work account"). The label is stored
     * as a note prefixed with [ACCOUNT_NOTE_PREFIX]; the prefix keeps the note self-describing
     * for a person reading it in Brave's password manager, where Vault's note otherwise looks
     * like free text. A nameless account gets no note rather than an empty value. The Vault Key
     * has no note, so its record keeps the empty-note form that [accountId] and
     * [hasVaultKeyBackup] require.
     */
    private fun accountLabel(account: Account): String? =
        account.accountName?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Returns the account label from a record's note, or `null` when the note is absent or does
     * not carry [ACCOUNT_NOTE_PREFIX].
     *
     * The accepted form is `label:<name>`, matching what [accountLabel] writes: no space
     * immediately after `label:`, but any spaces inside `<name>` are part of the label. The
     * remainder is therefore used as-is rather than trimmed, since [accountLabel] already
     * stripped the outside whitespace and a value such as `label: Name` is not a Vault label.
     * No legacy spelling is supported because the app has not shipped.
     */
    private fun BravePasswordService.Password.notesText(): String? = notes.singleOrNull()?.value
        ?.takeIf { it.startsWith(ACCOUNT_NOTE_PREFIX) }
        ?.removePrefix(ACCOUNT_NOTE_PREFIX)
        ?.takeIf { it.isNotBlank() && it == it.trim() }

    private fun syncCode(vaultKey: Account, passphrase: CharArray): String {
        // Consolidation supplies a run-scoped code so all endpoints reuse one SSH
        // decryption and one date-derived 25th word. Standalone callers still derive it.
        runSyncCode?.let { return it }
        val seed = SshKeyCodec.parse(vaultKey.zenTenKey, passphrase).seed
        return Seedify.toBraveSyncCode(seed)
    }

    private fun canonicalKey(value: String, passphrase: CharArray): String {
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(value)
        SshKeyCodec.parse(normalized, passphrase) // require a valid Ed25519 OpenSSH key
        return normalized
    }

    private fun accountUrl(accountId: String): String = "https://$accountId.zenten/"

    data class RecoveryResult(
        val accounts: List<Account>,
        val skippedEntryIds: List<String>,
        val decryptionFailures: Int,
        val discoveredEntryCount: Int,
    )

    companion object {
        const val VAULT_KEY_ID = "vaultkey"

        /**
         * Prefix of the single note Vault stores on associated-account records.
         *
         * No space follows the colon: the note is exactly `label:<account name>`. There is no
         * legacy spaced form to accept, because the backup format has not shipped yet.
         */
        const val ACCOUNT_NOTE_PREFIX = "label:"
    }
}
