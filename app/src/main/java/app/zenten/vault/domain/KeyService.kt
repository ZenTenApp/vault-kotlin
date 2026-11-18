package app.zenten.vault.domain

import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Key
import app.zenten.vault.data.secure.SecureStorageService
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** User-facing associated key lifecycle. The vaultKey remains an internal-only identity. */
class KeyService(
    private val secureStorage: SecureStorageService,
    private val setupService: SetupService,
    private val appStorage: AppStorageService,
) {
    private val lastCreatedAt = AtomicLong(0)

    val keyUpdates: StateFlow<List<Key>> get() = secureStorage.keyUpdates
    fun getKeys(): List<Key> = secureStorage.getKeys()
    fun getKey(id: Long): Key? = secureStorage.getKeyById(id)

    /**
     * Verifies the VaultKey passphrase by decrypting the stored key.
     *
     * The passphrase is no longer persisted, so a real decryption replaces the old plaintext
     * comparison. A wrong passphrase fails the bcrypt container check.
     *
     * bcrypt_pbkdf at the configured work factor is seconds of CPU work, so it must never run on
     * the main looper or the UI freezes and can ANR. This suspends on [Dispatchers.Default] and
     * every caller is suspend/off-main by design.
     */
    suspend fun verifyVaultKeyPassphrase(vaultKeyPassphrase: String): Result<Unit> =
        // `runCatching<Unit>` is explicit: with `withContext` in between, the declared
        // `Result<Unit>` return type no longer propagates into inference, and the block's last
        // expression (the parsed key) would otherwise make this a `Result<ParsedKey>`.
        withContext(Dispatchers.Default) { runCatching<Unit> {
            val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey is unavailable" }
            require(vaultKeyPassphrase.isNotBlank()) { "VaultKey passphrase is required to create an associated key" }
            runCatching { SshKeyCodec.parse(vaultKey.zenTenKey, vaultKeyPassphrase.toCharArray()) }
                .getOrElse { throw IllegalArgumentException("The passphrase does not match the vaultKey") }
        } }

    /**
     * Verifies the passphrase that protects one stored key's encrypted private key.
     *
     * Export uses this rather than [verifyVaultKeyPassphrase] because a restored key keeps
     * the passphrase it was originally imported with, which can differ from the current Vault
     * Key passphrase.
     *
     * Like [verifyVaultKeyPassphrase], the bcrypt derivation is seconds of CPU work and is moved
     * off the caller's thread so a Main-dispatched caller cannot freeze the UI.
     */
    suspend fun verifyKeyPassphrase(keyId: Long, passphrase: String): Result<Unit> =
        withContext(Dispatchers.Default) { runCatching<Unit> {
            val key = requireNotNull(secureStorage.getKeyById(keyId)) { "Key is unavailable" }
            require(passphrase.isNotBlank()) { "The key passphrase is required to export the key" }
            runCatching { SshKeyCodec.parse(key.zenTenKey, passphrase.toCharArray()) }
                .getOrElse { throw IllegalArgumentException("The passphrase does not match this key") }
        } }

    suspend fun createAssociatedKey(vaultKeyPassphrase: String, keyName: String): Result<Key> = withContext(Dispatchers.Default) { runCatching {
        verifyVaultKeyPassphrase(vaultKeyPassphrase).getOrThrow()
        val vaultKey = requireNotNull(secureStorage.getVaultKey()) { "VaultKey is unavailable" }
        val normalizedKeyName = keyName.trim()
        require(normalizedKeyName.length in MIN_KEY_NAME_LENGTH..MAX_KEY_NAME_LENGTH) {
            "Key name must be between $MIN_KEY_NAME_LENGTH and $MAX_KEY_NAME_LENGTH characters"
        }
        // Blocks any name containing "vault", which could impersonate the VaultKey, which is an internal identity users must not see.
        require(!containsReservedKeyNameTerm(normalizedKeyName)) {
            "Key name cannot contain \"$RESERVED_KEY_NAME_TERM_DISPLAY\""
        }
        // Enforce the 99-key ceiling only for user-initiated creation. Consolidation and
        // recovery write through SecureStorageService directly so restored keys are exempt.
        require(secureStorage.getKeys().size < MAX_ASSOCIATED_KEYS) {
            "You can create up to $MAX_ASSOCIATED_KEYS associated keys"
        }
        val createdAt = nextCreatedAt()
        val generated = SshKeyCodec.generateEncrypted(
            createdAt,
            vaultKeyPassphrase.toCharArray(),
            rounds = appStorage.sshKdfRounds.first(),
        )
        val key = Key(
            createdAt = createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),
            keyName = normalizedKeyName,
        )
        setupService.addKey(vaultKey, key).getOrThrow()
        key
    } }

    /**
     * Allocates the next key ID, never reusing or regressing past one already issued here.
     *
     * [newKeyCreatedAt] supplies a clock-based candidate with a random sub-millisecond tail.
     * The CAS loop makes two concurrent creations — both run on [Dispatchers.Default] — hand out
     * distinct IDs, and `previous + 1` keeps IDs increasing even if the wall clock jumps backwards
     * (NTP correction, manual time change). The counter is per process; it is not seeded from
     * already-stored IDs.
     */
    private fun nextCreatedAt(): Long {
        while (true) {
            val candidate = newKeyCreatedAt()
            val previous = lastCreatedAt.get()
            val next = maxOf(candidate, previous + 1)
            if (lastCreatedAt.compareAndSet(previous, next)) return next
        }
    }

    companion object {
        /** Maximum number of user-creatable associated keys; the vaultKey is tracked separately. */
        const val MAX_ASSOCIATED_KEYS = 99
        const val MIN_KEY_NAME_LENGTH = 3
        const val MAX_KEY_NAME_LENGTH = 128

        /** Wording shown to the user for the reserved term. */
        const val RESERVED_KEY_NAME_TERM_DISPLAY = "vault"

        /** The reserved term in the comparison form: lowercase letters only. */
        private const val RESERVED_KEY_NAME_TERM = "vault"

        /**
         * True when [name] contains "vault" in any spelling of case, spacing, or punctuation.
         *
         * The name is folded to NFKC first so full-width or compatibility letters match their plain
         * forms, then only letters are kept. Digits and symbols are dropped too, so "Vault-Key",
         * "VAULT_KEY", "v a u l t", and "my.vault" are all rejected. Lowercasing uses
         * `lowercaseChar()`, which does not depend on the device locale.
         */
        fun containsReservedKeyNameTerm(name: String): Boolean {
            val folded = Normalizer.normalize(name, Normalizer.Form.NFKC)
            val lettersOnly = buildString {
                folded.forEach { if (it.isLetter()) append(it.lowercaseChar()) }
            }
            return lettersOnly.contains(RESERVED_KEY_NAME_TERM)
        }
    }
}
