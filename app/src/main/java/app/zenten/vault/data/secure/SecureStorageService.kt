package app.zenten.vault.data.secure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.zenten.vault.data.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keystore-backed encrypted persistence for every sensitive account record. */
class SecureStorageService(context: Context, private val json: Json = Json) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _accounts = MutableStateFlow(readAccounts())
    val accountUpdates: StateFlow<List<Account>> = _accounts.asStateFlow()

    fun addMasterAccount(account: Account) = write(MASTER_ACCOUNT, account)
    fun updateMasterAccount(account: Account) = write(MASTER_ACCOUNT, account)
    fun getMasterAccount(): Account? = read(MASTER_ACCOUNT)
    fun removeMasterAccount() = preferences.edit().remove(MASTER_ACCOUNT).apply()

    /** Adds/replaces an account and appends its ID only once, preserving registry order. */
    fun addAccount(account: Account) {
        preferences.edit().putString(accountKey(account.createdAt), json.encodeToString(account)).apply()
        saveRegistry((registry() + account.createdAt).distinct())
        publishAccounts()
    }

    /** Updates only an existing registry member; callers must use addAccount for new records. */
    fun updateAccount(account: Account) {
        require(account.createdAt in registry()) { "Cannot update an account that is not in the registry" }
        write(accountKey(account.createdAt), account)
        publishAccounts()
    }

    fun getAccountById(createdAt: Long): Account? = read(accountKey(createdAt))

    /** Returns registry-ordered records and silently ignores deleted/corrupt entries. */
    fun getAccounts(): List<Account> = readAccounts()

    fun removeAccount(createdAt: Long) {
        preferences.edit().remove(accountKey(createdAt)).apply()
        saveRegistry(registry().filterNot { it == createdAt })
        publishAccounts()
    }

    fun clearAll() {
        preferences.edit().clear().apply()
        publishAccounts()
    }

    private fun write(key: String, account: Account) =
        preferences.edit().putString(key, json.encodeToString(account)).apply()

    private fun read(key: String): Account? = preferences.getString(key, null)?.let { encoded ->
        runCatching { json.decodeFromString<Account>(encoded) }.getOrNull()
    }

    private fun readAccounts(): List<Account> = registry().mapNotNull(::getAccountById)

    private fun publishAccounts() {
        _accounts.value = readAccounts()
    }

    private fun registry(): List<Long> = preferences.getString(ACCOUNTS_REGISTRY, null)?.let { encoded ->
        runCatching { json.decodeFromString<List<Long>>(encoded) }.getOrDefault(emptyList())
    } ?: emptyList()

    private fun saveRegistry(ids: List<Long>) = preferences.edit()
        .putString(ACCOUNTS_REGISTRY, json.encodeToString(ids.distinct()))
        .apply()

    private fun accountKey(createdAt: Long) = "accounts_$createdAt"

    private companion object {
        const val FILE_NAME = "vault_secure_accounts"
        const val MASTER_ACCOUNT = "master_account"
        const val ACCOUNTS_REGISTRY = "accounts_registry"
    }
}
