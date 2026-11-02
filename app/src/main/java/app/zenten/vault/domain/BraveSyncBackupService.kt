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
 * canonical single-line OpenSSH private-key Base64 values and carry no notes.
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
        entry.username.toLongOrNull() != null && entry.url == accountUrl(entry.username) && entry.notes.isEmpty() -> entry.username
        else -> null
    }

    fun decodeAssociatedAccount(vaultKey: Account, entry: BravePasswordService.Password, passphrase: CharArray): Account? = runCatching {
        // The vaultkey record has no numeric ID, so it is rejected here: normal recovery
        // deliberately restores associated accounts only.
        val accountId = requireNotNull(accountId(entry)?.toLongOrNull())
        accountFromSshKey(accountId, entry.password, passphrase)
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
        withContext(Dispatchers.IO) {
            runCatching {
                val syncCode = syncCode(vaultKey, passphrase)
                val url = accountUrl(accountId)
                val key = canonicalKey(account.zenTenKey, passphrase)
                if (runSyncCode != null) {
                    // The engine invokes write only after this source's index proved the ID
                    // absent, so another list/refetch would duplicate network work.
                    passwordService.createKnownMissing(syncCode, url, accountId, key)
                } else {
                    // Standalone callers do not provide an index, so retain the idempotency check.
                    val existing = passwordService.list(syncCode).passwords.firstOrNull {
                        it.url == url && it.username == accountId
                    }
                    if (existing == null) passwordService.create(syncCode, url, accountId, key)
                    else require(existing.password == key && existing.notes.isEmpty()) {
                        "Brave Sync already contains a different Vault backup for $accountId"
                    }
                }
                Unit
            }
        }

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
    }
}
