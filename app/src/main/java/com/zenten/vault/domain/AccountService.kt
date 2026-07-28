package com.zenten.vault.domain

import com.zenten.vault.crypto.SshKeyCodec
import com.zenten.vault.data.model.Account
import com.zenten.vault.data.secure.SecureStorageService
import java.util.concurrent.atomic.AtomicLong

/** User-facing sub-account lifecycle. The master remains an internal-only identity. */
class AccountService(
    private val secureStorage: SecureStorageService,
    private val setupService: SetupService,
) {
    private val lastCreatedAt = AtomicLong(0)

    fun getAccounts(): List<Account> = secureStorage.getAccounts()
    fun getAccount(id: Long): Account? = secureStorage.getAccountById(id)

    suspend fun createSubAccount(): Result<Account> = runCatching {
        val master = requireNotNull(secureStorage.getMasterAccount()) { "Master account is unavailable" }
        val createdAt = nextCreatedAt()
        val generated = SshKeyCodec.generateUnencrypted(createdAt)
        val account = Account(
            createdAt = createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),
            pubkey = generated.publicKey,
        )
        setupService.addAccount(master, account).getOrThrow()
        account
    }


    private fun nextCreatedAt(): Long {
        while (true) {
            val candidate = System.currentTimeMillis() * 1_000L
            val previous = lastCreatedAt.get()
            val next = maxOf(candidate, previous + 1)
            if (lastCreatedAt.compareAndSet(previous, next)) return next
        }
    }
}
