package app.zenten.vault.data.secure

import android.content.Context
import androidx.core.content.edit
import app.zenten.vault.crypto.KeystoreCryptoService
import app.zenten.vault.data.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Keystore-backed encrypted persistence for every sensitive account record.
 *
 * Each preference value is JSON-serialized, encrypted with [KeystoreCryptoService] (AES-256-GCM
 * using an Android Keystore key), then stored as Base64 text. Preference keys and the file name
 * remain plaintext but contain only non-secret record identifiers.
 *
 * There is deliberately **no separate index/registry of accounts**. The account records are
 * themselves the index: each is stored under `accounts_<createdAt>` and also carries `createdAt`
 * inside its authenticated ciphertext. Enumerating therefore means scanning that key prefix.
 *
 * Removing the registry is a correctness property, not just a simplification. An index has to be
 * kept in sync with the records it lists, and that read-modify-write pair cannot be atomic in
 * `SharedPreferences`. Two concurrent writers could each write a distinct valid record while only
 * one ID survived in the index, silently orphaning the loser — a credential the app could no
 * longer list, back up, or export. With every mutation reduced to a single preference edit, there
 * is no multi-step update left to lose, so no caller has to remember to hold a lock.
 *
 * There is intentionally no migration code for the former `accounts_registry` preference. The app
 * has not been released, so every install is a development install and no stored registry exists
 * in the field. Account records were always stored under `accounts_<createdAt>`, so they remain
 * readable as-is. A stale `accounts_registry` value on a development device is inert: it is not an
 * `accounts_<id>` key, so enumeration ignores it, and [clearAll] removes it.
 */
class SecureStorageService(
    context: Context,
    private val crypto: KeystoreCryptoService = KeystoreCryptoService(),
    private val json: Json = Json,
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(readAccounts())
    val accountUpdates: StateFlow<List<Account>> = _accounts.asStateFlow()

    /**
     * Decrypted VaultKey record, used by the launch gate to know whether to ask for a passphrase.
     * Holds only the passphrase-encrypted key, never the passphrase itself.
     */
    private val _vaultKey = MutableStateFlow(read(VAULT_KEY))
    val vaultKeyUpdates: StateFlow<Account?> = _vaultKey.asStateFlow()

    fun addVaultKey(account: Account) {
        write(VAULT_KEY, account)
        _vaultKey.value = account
    }

    fun getVaultKey(): Account? = read(VAULT_KEY)

    fun removeVaultKey() {
        preferences.edit { remove(VAULT_KEY) }
        _vaultKey.value = null
    }

    /**
     * Adds or replaces an account in a single preference edit.
     *
     * One edit is atomic and process-safe, so concurrent saves never discard each other. Replacing
     * an existing ID is idempotent because the key is derived solely from `createdAt`.
     */
    fun addAccount(account: Account) {
        write(accountKey(account.createdAt), account)
        publishAccounts()
    }

    /** Returns the record only when it decrypts and still claims the ID that was asked for. */
    fun getAccountById(createdAt: Long): Account? = read(accountKey(createdAt))
        ?.takeIf { it.createdAt == createdAt }

    /** Returns all readable records, ordered by ascending account ID (oldest first). */
    fun getAccounts(): List<Account> = readAccounts()

    /** Removes the record in a single preference edit; removing an absent ID is a no-op. */
    fun removeAccount(createdAt: Long) {
        preferences.edit { remove(accountKey(createdAt)) }
        publishAccounts()
    }

    fun clearAll() {
        preferences.edit { clear() }
        publishAccounts()
    }

    private fun write(key: String, account: Account) =
        preferences.edit { putString(key, crypto.encrypt(json.encodeToString(account))) }

    private fun read(key: String): Account? = preferences.getString(key, null)
        ?.let(crypto::decrypt)
        ?.let { encoded -> runCatching { json.decodeFromString<Account>(encoded) }.getOrNull() }

    /**
     * Rebuilds the account list from the encrypted records themselves.
     *
     * The order is explicit rather than incidental: without a registry there is no stored
     * insertion order to fall back on, so ascending `createdAt` is the canonical order. Every
     * caller either re-sorts or treats the list as a set, so this is not user-visible.
     *
     * A record that fails to decrypt, fails to parse, or whose authenticated `createdAt` disagrees
     * with its preference key is skipped rather than trusted. It is left on disk untouched so a
     * later recovery path can still inspect it; distinguishing "corrupt" from "absent" for the
     * user is tracked separately and is not attempted here.
     */
    private fun readAccounts(): List<Account> = preferences.all.keys
        .mapNotNull(::accountIdFromKey)
        .mapNotNull(::getAccountById)
        .sortedBy { it.createdAt }

    private fun publishAccounts() {
        _accounts.value = readAccounts()
    }

    /**
     * Extracts the account ID from an `accounts_<id>` preference key.
     *
     * Parsing the numeric suffix rejects any other preference whose name merely starts with the
     * prefix, so unrelated state can never be mistaken for a record.
     */
    private fun accountIdFromKey(key: String): Long? = key
        .takeIf { it.startsWith(ACCOUNT_KEY_PREFIX) }
        ?.removePrefix(ACCOUNT_KEY_PREFIX)
        ?.takeIf { suffix -> suffix.isNotEmpty() && suffix.all(Char::isDigit) }
        ?.toLongOrNull()

    private fun accountKey(createdAt: Long) = "$ACCOUNT_KEY_PREFIX$createdAt"

    private companion object {
        const val FILE_NAME = "vault_secure_accounts_v2"
        const val VAULT_KEY = "vault_key"
        const val ACCOUNT_KEY_PREFIX = "accounts_"
    }
}
