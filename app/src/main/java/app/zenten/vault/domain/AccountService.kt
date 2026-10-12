package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** User-facing associated account lifecycle. The vaultKey remains an internal-only identity. */
class AccountService(
    private val secureStorage: SecureStorageService,
    private val setupService: SetupService,
    private val appStorage: AppStorageService,
) {
    private val lastCreatedAt = AtomicLong(0)

    val accountUpdates: StateFlow<List<Account>> get() = secureStorage.accountUpdates
    fun getAccounts(): List<Account> = secureStorage.getAccounts()
    fun getAccount(id: Long): Account? = secureStorage.getAccountById(id)

    fun verifyVaultKeyPassphrase(vaultKeyPassphrase: String): Result<Unit> = runCatching {
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        val storedPassphrase = vaultKey.zenTenPass
        require(vaultKeyPassphrase.isNotBlank()) { "VaultKey account passphrase is required to create an associated account" }
        require(vaultKeyPassphrase == storedPassphrase) { "The passphrase does not match the vaultKey account" }
    }

    suspend fun createAssociatedAccount(vaultKeyPassphrase: String, accountName: String): Result<Account> = withContext(Dispatchers.Default) { runCatching {
        verifyVaultKeyPassphrase(vaultKeyPassphrase).getOrThrow()
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
        val normalizedAccountName = accountName.trim()
        require(normalizedAccountName.length in MIN_ACCOUNT_NAME_LENGTH..MAX_ACCOUNT_NAME_LENGTH) {
            "Account name must be between $MIN_ACCOUNT_NAME_LENGTH and $MAX_ACCOUNT_NAME_LENGTH characters"
        }
        // Enforce the 99-account ceiling only for user-initiated creation. Consolidation and
        // recovery write through SecureStorageService directly so restored accounts are exempt.
        require(secureStorage.getAccounts().size < MAX_ASSOCIATED_ACCOUNTS) {
            "You can create up to $MAX_ASSOCIATED_ACCOUNTS associated accounts"
        }
        val createdAt = nextCreatedAt()
        val generated = SshKeyCodec.generateEncrypted(
            createdAt,
            vaultKeyPassphrase.toCharArray(),
            rounds = appStorage.sshKdfRounds.first(),
        )
        val account = Account(
            createdAt = createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),
            zenTenPass = vaultKeyPassphrase,
            nostr = NostrKeyDerivation.derive(
                Seedify.fromSshKey(generated.privateKeyPem, vaultKeyPassphrase.toCharArray()),
            ).toPersistedKeyPair(),
            accountName = normalizedAccountName,
        )
        setupService.addAccount(vaultKey, account).getOrThrow()
        account
    } }


    private fun nextCreatedAt(): Long {
        while (true) {
            val candidate = System.currentTimeMillis() * 1_000L + ThreadLocalRandom.current().nextLong(1, 1_000)
            val previous = lastCreatedAt.get()
            val next = maxOf(candidate, previous + 1)
            if (lastCreatedAt.compareAndSet(previous, next)) return next
        }
    }

    companion object {
        /** Maximum number of user-creatable associated accounts; the vaultKey is tracked separately. */
        const val MAX_ASSOCIATED_ACCOUNTS = 99
        const val MIN_ACCOUNT_NAME_LENGTH = 3
        const val MAX_ACCOUNT_NAME_LENGTH = 128
    }
}
