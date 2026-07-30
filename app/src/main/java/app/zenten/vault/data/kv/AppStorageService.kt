package app.zenten.vault.data.kv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.zenten.vault.data.model.AppServerConfig
import app.zenten.vault.domain.BackupTargetId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Non-secret app state. Sensitive accounts must never be placed in this store. */
class AppStorageService(private val dataStore: DataStore<Preferences>, private val json: Json = Json) {
    val setupComplete: Flow<Boolean> = dataStore.data.map { it[SETUP_COMPLETE] ?: false }
    val appServerConfig: Flow<AppServerConfig?> = dataStore.data.map { preferences ->
        preferences[APP_SERVER_CONFIG]?.let { runCatching { json.decodeFromString<AppServerConfig>(it) }.getOrNull() }
    }
    /** Legacy relay-only retry queue, retained for existing persisted installations. */
    val failedBackupAccountIds: Flow<List<Long>> = dataStore.data.map { preferences ->
        preferences[FAILED_BACKUP_ACCOUNT_IDS]?.let { runCatching { json.decodeFromString<List<Long>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }
    val pendingAccountBackups: Flow<List<PendingAccountBackup>> = dataStore.data.map { preferences ->
        preferences[PENDING_ACCOUNT_BACKUPS]?.let { runCatching { json.decodeFromString<List<PendingAccountBackup>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    suspend fun setSetupComplete() = dataStore.edit { it[SETUP_COMPLETE] = true }
    suspend fun clearSetupComplete() = dataStore.edit { it.remove(SETUP_COMPLETE) }
    suspend fun setAppServerConfig(config: AppServerConfig) = dataStore.edit { it[APP_SERVER_CONFIG] = json.encodeToString(config) }
    suspend fun setFailedBackupAccountIds(ids: Collection<Long>) = dataStore.edit {
        it[FAILED_BACKUP_ACCOUNT_IDS] = json.encodeToString(ids.distinct())
    }
    suspend fun addFailedBackupAccountId(createdAt: Long) = dataStore.edit { preferences ->
        val current = preferences[FAILED_BACKUP_ACCOUNT_IDS]?.let {
            runCatching { json.decodeFromString<List<Long>>(it) }.getOrDefault(emptyList())
        }.orEmpty()
        preferences[FAILED_BACKUP_ACCOUNT_IDS] = json.encodeToString((current + createdAt).distinct())
    }

    suspend fun removeFailedBackupAccountId(createdAt: Long) = dataStore.edit { preferences ->
        val current = preferences[FAILED_BACKUP_ACCOUNT_IDS]?.let {
            runCatching { json.decodeFromString<List<Long>>(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val remaining = current.filterNot { it == createdAt }
        if (remaining.isEmpty()) preferences.remove(FAILED_BACKUP_ACCOUNT_IDS)
        else preferences[FAILED_BACKUP_ACCOUNT_IDS] = json.encodeToString(remaining)
    }

    suspend fun clearFailedBackupAccountIds() = dataStore.edit { it.remove(FAILED_BACKUP_ACCOUNT_IDS) }

    suspend fun markPendingAccountBackup(accountId: Long, targets: Set<BackupTargetId>) = dataStore.edit { preferences ->
        val current = preferences[PENDING_ACCOUNT_BACKUPS]?.let {
            runCatching { json.decodeFromString<List<PendingAccountBackup>>(it) }.getOrDefault(emptyList())
        }.orEmpty().associateBy { it.accountId }.toMutableMap()
        current[accountId] = PendingAccountBackup(accountId, (current[accountId]?.targets.orEmpty() + targets).toSet())
        preferences[PENDING_ACCOUNT_BACKUPS] = json.encodeToString(current.values.toList())
    }

    suspend fun clearPendingAccountBackupTargets(accountId: Long, targets: Set<BackupTargetId>) = dataStore.edit { preferences ->
        val current = preferences[PENDING_ACCOUNT_BACKUPS]?.let {
            runCatching { json.decodeFromString<List<PendingAccountBackup>>(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val remaining = current.mapNotNull { pending ->
            if (pending.accountId != accountId) pending
            else PendingAccountBackup(accountId, pending.targets - targets).takeIf { it.targets.isNotEmpty() }
        }
        if (remaining.isEmpty()) preferences.remove(PENDING_ACCOUNT_BACKUPS)
        else preferences[PENDING_ACCOUNT_BACKUPS] = json.encodeToString(remaining)
    }

    companion object {
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val APP_SERVER_CONFIG = stringPreferencesKey("app_server_config")
        val FAILED_BACKUP_ACCOUNT_IDS = stringPreferencesKey("failed_backup_account_ids")
        val PENDING_ACCOUNT_BACKUPS = stringPreferencesKey("pending_account_backups")
    }
}

@Serializable
data class PendingAccountBackup(
    val accountId: Long,
    val targets: Set<BackupTargetId>,
)
