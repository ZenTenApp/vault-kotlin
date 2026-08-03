package app.zenten.vault.domain

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/** User-facing associated account lifecycle. The master remains an internal-only identity. */
class AccountService(
    private val secureStorage: SecureStorageService,
    private val setupService: SetupService,
) {
    private val lastCreatedAt = AtomicLong(0)

    fun getAccounts(): List<Account> = secureStorage.getAccounts()
    fun getAccount(id: Long): Account? = secureStorage.getAccountById(id)

    suspend fun createAssociatedAccount(): Result<Account> = runCatching {
        val master = requireNotNull(secureStorage.getMasterAccount()) { "Master account is unavailable" }
        val passphrase = requireNotNull(master.zenTenPass) { "Master account passphrase is unavailable" }
        require(passphrase.isNotBlank()) { "Master account passphrase is required to create an associated account" }
        val createdAt = nextCreatedAt()
        val generated = SshKeyCodec.generateEncrypted(createdAt, passphrase.toCharArray())
        val account = Account(
            createdAt = createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),
            zenTenPass = passphrase,
            pubkey = generated.publicKey,
        )
        setupService.addAccount(master, account).getOrThrow()
        account
    }


    private fun nextCreatedAt(): Long {
        while (true) {
            val candidate = System.currentTimeMillis() * 1_000L + ThreadLocalRandom.current().nextLong(1, 1_000)
            val previous = lastCreatedAt.get()
            val next = maxOf(candidate, previous + 1)
            if (lastCreatedAt.compareAndSet(previous, next)) return next
        }
    }
}
