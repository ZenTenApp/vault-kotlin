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
 * The master account derives the Sync code; records are deliberately limited to
 * canonical single-line OpenSSH private-key Base64 values and carry no notes.
 */
class BraveSyncBackupService(
    private val passwordService: BravePasswordService = BravePasswordService(),
) : AccountBackupTarget {
    override val id = BackupTargetId.BRAVE_SYNC
    override suspend fun backupMaster(master: Account): Result<Unit> = backup(master, MASTER_ACCOUNT_ID)

    override suspend fun backupAssociatedAccount(master: Account, account: Account): Result<Unit> =
        backup(master, account.createdAt.toString(), account)

    /** Checks the master record separately because normal recovery deliberately restores associated accounts only. */
    suspend fun hasMasterBackup(master: Account): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            passwordService.list(syncCode(master)).passwords.any { entry ->
                entry.username == MASTER_ACCOUNT_ID && entry.url == accountUrl(MASTER_ACCOUNT_ID) &&
                    entry.notes.isEmpty() && master.zenTenPass?.let { passphrase ->
                        runCatching { canonicalKey(entry.password, passphrase.toCharArray()) }.isSuccess
                    } == true
            }
        }
    }

    suspend fun discoverAssociatedAccounts(master: Account): RecoveryResult = withContext(Dispatchers.IO) {
        val syncCode = syncCode(master)
        val discovered = passwordService.list(syncCode)
        val valid = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        discovered.passwords.forEach { entry ->
            val accountId = entry.username.toLongOrNull()
            if (accountId == null || entry.url != accountUrl(entry.username) || entry.notes.isNotEmpty()) {
                skipped += entry.id
                return@forEach
            }
            val passphrase = master.zenTenPass ?: run {
                skipped += entry.id
                return@forEach
            }
            val key = runCatching { canonicalKey(entry.password, passphrase.toCharArray()) }.getOrNull()
            if (key == null) {
                skipped += entry.id
                return@forEach
            }
            val parsed = SshKeyCodec.parse(key, passphrase.toCharArray())
            valid += Account(
                createdAt = accountId,
                zenTenKey = key,
                zenTenPass = passphrase,
                pubkey = SshKeyCodec.publicKeyLine(parsed.publicKey, parsed.comment),
            )
        }
        RecoveryResult(valid, skipped, discovered.decryptionFailures, discovered.passwords.size)
    }

    private suspend fun backup(master: Account, accountId: String, account: Account = master): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val syncCode = syncCode(master)
                val url = accountUrl(accountId)
                val passphrase = requireNotNull(account.zenTenPass) { "Account passphrase is unavailable" }
                val key = canonicalKey(account.zenTenKey, passphrase.toCharArray())
                // Retrying a previous successful write must not create a duplicate client tag.
                val existing = passwordService.list(syncCode).passwords.firstOrNull {
                    it.url == url && it.username == accountId
                }
                if (existing == null) passwordService.create(syncCode, url, accountId, key)
                else require(existing.password == key && existing.notes.isEmpty()) {
                    "Brave Sync already contains a different Vault backup for $accountId"
                }
                Unit
            }
        }

    private fun syncCode(master: Account): String {
        val passphrase = requireNotNull(master.zenTenPass) { "Master account passphrase is unavailable" }
        val seed = SshKeyCodec.parse(master.zenTenKey, passphrase.toCharArray()).seed
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
        const val MASTER_ACCOUNT_ID = "masterkey"
    }
}
