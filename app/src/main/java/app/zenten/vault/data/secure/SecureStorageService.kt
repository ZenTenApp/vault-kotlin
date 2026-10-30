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
 */
class SecureStorageService(
    context: Context,
    private val crypto: KeystoreCryptoService = KeystoreCryptoService(),
    private val json: Json = Json,
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(readAccounts())
    val accountUpdates: StateFlow<List<Account>> = _accounts.asStateFlow()

    fun addVaultKey(account: Account) = write(VAULT_KEY, account)

    fun getVaultKey(): Account? = read(VAULT_KEY)
    fun removeVaultKey() = preferences.edit { remove(VAULT_KEY) }

    /** Adds/replaces an account and appends its ID only once, preserving registry order. */
    fun addAccount(account: Account) {
        write(accountKey(account.createdAt), account)
        saveRegistry((registry() + account.createdAt).distinct())
        publishAccounts()
    }

    fun getAccountById(createdAt: Long): Account? = read(accountKey(createdAt))

    /** Returns registry-ordered records and silently ignores deleted/corrupt entries. */
    fun getAccounts(): List<Account> = readAccounts()

    fun removeAccount(createdAt: Long) {
        preferences.edit { remove(accountKey(createdAt)) }
        saveRegistry(registry().filterNot { it == createdAt })
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

    private fun readAccounts(): List<Account> = registry().mapNotNull(::getAccountById)

    private fun publishAccounts() {
        _accounts.value = readAccounts()
    }

    private fun registry(): List<Long> = preferences.getString(ACCOUNTS_REGISTRY, null)
        ?.let(crypto::decrypt)
        ?.let { encoded ->
            runCatching { json.decodeFromString<List<Long>>(encoded) }.getOrDefault(emptyList())
        }
        ?: emptyList()

    private fun saveRegistry(ids: List<Long>) = preferences.edit {
        putString(ACCOUNTS_REGISTRY, crypto.encrypt(json.encodeToString(ids.distinct())))
    }

    private fun accountKey(createdAt: Long) = "accounts_$createdAt"

    private companion object {
        const val FILE_NAME = "vault_secure_accounts_v2"
        const val VAULT_KEY = "vault_key"
        const val ACCOUNTS_REGISTRY = "accounts_registry"
    }
}
