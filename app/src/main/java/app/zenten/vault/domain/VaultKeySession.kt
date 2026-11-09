package app.zenten.vault.domain

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.secure.SecureStorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory holder for the VaultKey passphrase.
 *
 * The passphrase is deliberately never written to disk. It is typed by the user once per
 * unlocked foreground session (after device authentication), used by consolidation and the
 * other flows that need to decrypt the VaultKey, and zeroed when the app is locked or the
 * process dies. Because it is only in memory, any background work that needs it must be
 * started while the session is unlocked and must take its own [snapshot].
 *
 * A copy is handed out for long-running work so that locking the session cannot corrupt an
 * in-flight operation; the holder of that copy is responsible for zeroing it.
 */
@Singleton
class VaultKeySession @Inject constructor(
    private val secureStorage: SecureStorageService,
) {
    private val mutex = Mutex()
    private var passphrase: CharArray? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /**
     * Verifies [candidate] by decrypting the stored VaultKey's OpenSSH key and, on success,
     * keeps an in-memory copy. Verification is a real bcrypt decryption rather than a
     * comparison against a stored value, which is only possible now that the passphrase is
     * not persisted.
     *
     * The stored container was already checked against the accepted work-factor band when it
     * was imported or generated, and [SshKeyCodec.parse] re-checks it, so a failure here is a
     * wrong passphrase rather than an unsupported key.
     */
    suspend fun unlock(candidate: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey account is unavailable" }
            // Throws when the passphrase does not decrypt the key.
            SshKeyCodec.parse(vaultKey.zenTenKey, candidate)
        }.map { store(candidate) }
    }

    /**
     * Records a passphrase the caller already validated (key generation, recovery).
     *
     * Avoids a second bcrypt decryption when the caller just parsed the same key.
     */
    suspend fun establish(validated: CharArray) = store(validated)

    /** Returns a standalone copy for a long-running job, or null when locked. */
    suspend fun snapshot(): CharArray? = mutex.withLock { passphrase?.copyOf() }

    /** Zeroes and drops the in-memory passphrase. */
    suspend fun lock() = mutex.withLock {
        passphrase?.fill('\u0000')
        passphrase = null
        _isUnlocked.value = false
    }

    private suspend fun store(source: CharArray) = mutex.withLock {
        passphrase?.fill('\u0000')
        passphrase = source.copyOf()
        _isUnlocked.value = true
    }
}
