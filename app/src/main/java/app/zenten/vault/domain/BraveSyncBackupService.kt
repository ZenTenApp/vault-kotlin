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
    private val passwordService: BravePasswordService = BravePasswordService(),
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

    fun decodeAssociatedAccount(vaultKey: Account, entry: BravePasswordService.Password, passphrase: CharArray): Account? = runCatching {
        // The vaultkey record has no numeric ID, so it is rejected here: normal recovery
        // deliberately restores associated accounts only.
        val accountId = requireNotNull(accountId(entry)?.toLongOrNull())
        // The note is the only place the account name survives a device change; a missing or
        // blank one leaves the name unset, so the restored card shows no name.
        accountFromSshKey(accountId, entry.password, entry.notesText())
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
            val account = decodeAssociatedAccount(vaultKey, entry, passphrase)
            if (account == null) skipped += entry.id else valid += account
        }
        RecoveryResult(valid, skipped, discovered.decryptionFailures, discovered.passwords.size)
    }

    private suspend fun backup(vaultKey: Account, accountId: String, passphrase: CharArray, account: Account = vaultKey): Result<Unit> =
        // The Vault Key record never carries a note; only associated accounts do.
        backupRecord(vaultKey, accountId, passphrase, account, if (accountId == VAULT_KEY_ID) null else accountNote(account))

    /**
     * Writes one record. [note] is the plain account label to store, or `null` for no note.
     *
     * The Vault Key record always passes `null`: its readers require an empty note, and the
     * Vault Key has no user-set name to publish.
     */
    private suspend fun backupRecord(
        vaultKey: Account,
        accountId: String,
        passphrase: CharArray,
        account: Account,
        note: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val syncCode = syncCode(vaultKey, passphrase)
                val url = accountUrl(accountId)
                val key = canonicalKey(account.zenTenKey, passphrase)
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
                    else require(existing.password == key && existing.notesText() == note) {
                        "Brave Sync already contains a different Vault backup for $accountId"
                    }
                }
                Unit
            }
        }

    /**
     * Builds the note stored for an associated account, formatted as `label: <account name>`.
     *
     * The `label: ` prefix keeps the note self-describing for a person reading it in Brave's
     * password manager, where Vault's note otherwise looks like free text. A nameless account
     * gets no note rather than an empty prefixed value. The Vault Key has no note, so its record
     * keeps the empty-note form that [accountId] and [hasVaultKeyBackup] require.
     */
    private fun accountNote(account: Account): String? =
        account.accountName?.trim()?.takeIf { it.isNotEmpty() }?.let { "$ACCOUNT_NOTE_PREFIX$it" }

    /** Returns the account label from a record's note, accepting records without the prefix too. */
    private fun BravePasswordService.Password.notesText(): String? = notes.singleOrNull()?.value
        ?.trim()
        ?.removePrefix(ACCOUNT_NOTE_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

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

        /** Prefix of the single note Vault stores on associated-account records. */
        const val ACCOUNT_NOTE_PREFIX = "label: "
    }
}
