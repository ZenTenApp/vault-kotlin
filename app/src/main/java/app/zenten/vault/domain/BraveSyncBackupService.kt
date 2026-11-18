package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores Vault SSH keys in the password-only Brave Sync adapter.
 *
 * The vaultKey derives the Sync code; records are deliberately limited to
 * canonical single-line OpenSSH private-key Base64 values. Associated-key records also
 * carry the key label as a note so recovery can restore the name without local state;
 * the Vault Key record never has a note because the Vault Key has no user-set name.
 */
class BraveSyncBackupService(
    // No default transport: a Brave source always names the endpoint it belongs to.
    private val passwordService: BravePasswordService,
    private val runSyncCode: String? = null,
) {
    suspend fun backupVaultKey(vaultKey: Key, passphrase: CharArray): Result<Unit> = backup(vaultKey, VAULT_KEY_ID, passphrase)

    suspend fun backupAssociatedKey(vaultKey: Key, key: Key, passphrase: CharArray): Result<Unit> =
        backup(vaultKey, key.createdAt.toString(), passphrase, key)

    /** Lists decrypted record metadata/payloads. SSH key parsing remains deferred to restoration. */
    suspend fun listRecords(vaultKey: Key, passphrase: CharArray): Result<BravePasswordService.ListResult> = withContext(Dispatchers.IO) {
        runCatching { passwordService.list(syncCode(vaultKey, passphrase)) }
    }

    fun keyId(entry: BravePasswordService.Password): String? = when {
        entry.username == VAULT_KEY_ID && entry.url == keyUrl(VAULT_KEY_ID) && entry.notes.isEmpty() -> VAULT_KEY_ID
        entry.username.toLongOrNull() != null && entry.url == keyUrl(entry.username) && entry.notes.size <= 1 -> entry.username
        else -> null
    }

    /**
     * Decodes one associated-key record, or returns `null` when intake fails.
     *
     * A record that does not open with [passphrase] returns `null`, so callers skip it rather
     * than persisting a key that could never be unlocked or exported. The Vault Key record
     * is also rejected here: normal recovery deliberately restores associated keys only.
     */
    fun decodeAssociatedKey(entry: BravePasswordService.Password, passphrase: CharArray): Key? = runCatching {
        // The vaultkey record has no numeric ID, so it is rejected here: normal recovery
        // deliberately restores associated keys only.
        val keyId = requireNotNull(keyId(entry)?.toLongOrNull())
        // Metadata-only checks (numeric ID, container structure, work-factor band) can pass on a
        // container encrypted under a different passphrase, because the private section is
        // AES-CTR and authenticates nothing. Only actually decrypting the key proves the restored
        // key is usable.
        val key = canonicalKey(entry.password, passphrase)
        // The note is the only place the key name survives a device change; a missing or
        // blank one leaves the name unset, so the restored card shows no name.
        keyFromSshKey(keyId, key, entry.notesText())
    }.getOrNull()

    /** Checks the vaultKey record separately because normal recovery deliberately restores associated keys only. */
    suspend fun hasVaultKeyBackup(vaultKey: Key, passphrase: CharArray): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            passwordService.list(syncCode(vaultKey, passphrase)).passwords.any { entry ->
                entry.username == VAULT_KEY_ID && entry.url == keyUrl(VAULT_KEY_ID) &&
                    entry.notes.isEmpty() &&
                    runCatching { canonicalKey(entry.password, passphrase) }.isSuccess
            }
        }
    }

    suspend fun discoverAssociatedKeys(vaultKey: Key, passphrase: CharArray): RecoveryResult = withContext(Dispatchers.IO) {
        val syncCode = syncCode(vaultKey, passphrase)
        val discovered = passwordService.list(syncCode)
        val valid = mutableListOf<Key>()
        val skipped = mutableListOf<String>()
        discovered.passwords.forEach { entry ->
            val keyId = keyId(entry)?.toLongOrNull()
            if (keyId == null) {
                skipped += entry.id
                return@forEach
            }
            val key = decodeAssociatedKey(entry, passphrase)
            if (key == null) skipped += entry.id else valid += key
        }
        RecoveryResult(valid, skipped, discovered.decryptionFailures, discovered.passwords.size)
    }

    private suspend fun backup(vaultKey: Key, keyId: String, passphrase: CharArray, key: Key = vaultKey): Result<Unit> =
        // The Vault Key record never carries a note; only associated keys do.
        backupRecord(vaultKey, keyId, passphrase, key, if (keyId == VAULT_KEY_ID) null else keyLabel(key))

    /**
     * Writes one record. [label] is the plain key label to store, or `null` for no note.
     *
     * The Vault Key record always passes `null`: its readers require an empty note, and the
     * Vault Key has no user-set name to publish.
     */
    private suspend fun backupRecord(
        vaultKey: Key,
        keyId: String,
        passphrase: CharArray,
        key: Key,
        label: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val syncCode = syncCode(vaultKey, passphrase)
                val url = keyUrl(keyId)
                val key = canonicalKey(key.zenTenKey, passphrase)
                // The stored note carries the self-describing prefix; [label] stays plain so the
                // idempotency check can compare it against a decoded note.
                val note = label?.let { "$KEY_NOTE_PREFIX$it" }
                if (runSyncCode != null) {
                    // The engine invokes write only after this source's index proved the ID
                    // absent, so another list/refetch would duplicate network work.
                    passwordService.createKnownMissing(syncCode, url, keyId, key, note)
                } else {
                    // Standalone callers do not provide an index, so retain the idempotency check.
                    val existing = passwordService.list(syncCode).passwords.firstOrNull {
                        it.url == url && it.username == keyId
                    }
                    if (existing == null) passwordService.create(syncCode, url, keyId, key, note)
                    // Compare the decoded label against the plain [label] rather than against the
                    // prefixed [note], so a repeat backup of an unchanged key stays idempotent
                    // while a genuinely different name still conflicts.
                    else require(existing.password == key && existing.notesText() == label) {
                        "Brave Sync already contains a different Vault backup for $keyId"
                    }
                }
                Unit
            }
        }

    /**
     * Builds the plain key label for an associated key, or `null` when it has no name.
     *
     * Only the *outside* of the name is trimmed: leading and trailing whitespace is removed so
     * the stored value never begins with a space directly after the prefix, while spaces inside
     * the name are kept, because a label may be multi-word ("Work key"). The label is stored
     * as a note prefixed with [KEY_NOTE_PREFIX]; the prefix keeps the note self-describing
     * for a person reading it in Brave's password manager, where Vault's note otherwise looks
     * like free text. A nameless key gets no note rather than an empty value. The Vault Key
     * has no note, so its record keeps the empty-note form that [keyId] and
     * [hasVaultKeyBackup] require.
     */
    private fun keyLabel(key: Key): String? =
        key.keyName?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Returns the key label from a record's note, or `null` when the note is absent or does
     * not carry [KEY_NOTE_PREFIX].
     *
     * The accepted form is `label:<name>`, matching what [keyLabel] writes: no space
     * immediately after `label:`, but any spaces inside `<name>` are part of the label. The
     * remainder is therefore used as-is rather than trimmed, since [keyLabel] already
     * stripped the outside whitespace and a value such as `label: Name` is not a Vault label.
     * No legacy spelling is supported because the app has not shipped.
     */
    private fun BravePasswordService.Password.notesText(): String? = notes.singleOrNull()?.value
        ?.takeIf { it.startsWith(KEY_NOTE_PREFIX) }
        ?.removePrefix(KEY_NOTE_PREFIX)
        ?.takeIf { it.isNotBlank() && it == it.trim() }

    private fun syncCode(vaultKey: Key, passphrase: CharArray): String {
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

    private fun keyUrl(keyId: String): String = "https://$keyId.zenten/"

    data class RecoveryResult(
        val keys: List<Key>,
        val skippedEntryIds: List<String>,
        val decryptionFailures: Int,
        val discoveredEntryCount: Int,
    )

    companion object {
        const val VAULT_KEY_ID = "vaultkey"

        /**
         * Prefix of the single note Vault stores on associated-key records.
         *
         * No space follows the colon: the note is exactly `label:<key name>`. There is no
         * legacy spaced form to accept, because the backup format has not shipped yet.
         */
        const val KEY_NOTE_PREFIX = "label:"
    }
}
