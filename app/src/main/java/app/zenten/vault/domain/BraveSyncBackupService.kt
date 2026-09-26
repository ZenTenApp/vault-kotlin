package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.toPersistedKeyPair
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
    private val runSyncCode: String? = null,
) {
    suspend fun backupMaster(master: Account): Result<Unit> = backup(master, MASTER_ACCOUNT_ID)

    suspend fun backupAssociatedAccount(master: Account, account: Account): Result<Unit> =
        backup(master, account.createdAt.toString(), account)

    /** Lists decrypted record metadata/payloads. SSH key parsing remains deferred to restoration. */
    suspend fun listRecords(master: Account): Result<BravePasswordService.ListResult> = withContext(Dispatchers.IO) {
        runCatching { passwordService.list(syncCode(master)) }
    }

    fun accountId(entry: BravePasswordService.Password): String? = when {
        entry.username == MASTER_ACCOUNT_ID && entry.url == accountUrl(MASTER_ACCOUNT_ID) && entry.notes.isEmpty() -> MASTER_ACCOUNT_ID
        entry.username.toLongOrNull() != null && entry.url == accountUrl(entry.username) && entry.notes.isEmpty() -> entry.username
        else -> null
    }

    fun decodeAssociatedAccount(master: Account, entry: BravePasswordService.Password): Account? = runCatching {
        val accountId = requireNotNull(accountId(entry)?.toLongOrNull())
        val passphrase = requireNotNull(master.zenTenPass)
        val key = canonicalKey(entry.password, passphrase.toCharArray())
        val parsed = SshKeyCodec.parse(key, passphrase.toCharArray())
        Account(
            createdAt = accountId,
            zenTenKey = key,
            zenTenPass = passphrase,
            pubkey = SshKeyCodec.publicKeyLine(parsed.publicKey, parsed.comment),
            nostr = NostrKeyDerivation.derive(
                Seedify.fromSshKey(key, passphrase.toCharArray()),
            ).toPersistedKeyPair(),
        )
    }.getOrNull()

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
            val accountId = accountId(entry)?.toLongOrNull()
            if (accountId == null) {
                skipped += entry.id
                return@forEach
            }
            val account = decodeAssociatedAccount(master, entry)
            if (account == null) skipped += entry.id else valid += account
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

    private fun syncCode(master: Account): String {
        // Consolidation supplies a run-scoped code so all endpoints reuse one SSH
        // decryption and one date-derived 25th word. Standalone callers still derive it.
        runSyncCode?.let { return it }
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
