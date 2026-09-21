package app.zenten.vault.data.kv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.zenten.vault.domain.BackupSourceConfig
import app.zenten.vault.domain.DefaultBackupSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Non-secret app state. Sensitive accounts and encrypted remote payloads must never be placed here. */
class AppStorageService(private val dataStore: DataStore<Preferences>, private val json: Json = Json) {
    val setupComplete: Flow<Boolean> = dataStore.data.map { it[SETUP_COMPLETE] ?: false }
    val createMasterKeyStep: Flow<CreateMasterKeyStep?> = dataStore.data.map { preferences ->
        preferences[CREATE_MASTER_KEY_STEP]?.let { stored ->
            runCatching { CreateMasterKeyStep.valueOf(stored) }.getOrNull()
        }
    }
    val customBackupSources: Flow<List<BackupSourceConfig>> = jsonFlow(CUSTOM_BACKUP_SOURCES)
    val pendingSourceWrites: Flow<List<PendingSourceWrite>> = jsonFlow(PENDING_SOURCE_WRITES)
    val accountSourceStatuses: Flow<List<AccountSourceStatus>> = jsonFlow(ACCOUNT_SOURCE_STATUSES)
    val consolidationCheckpoint: Flow<ConsolidationCheckpoint?> = dataStore.data.map { preferences ->
        preferences[CONSOLIDATION_CHECKPOINT]?.let { runCatching { json.decodeFromString<ConsolidationCheckpoint>(it) }.getOrNull() }
    }
    val lastConsolidationCompletedAtMillis: Flow<Long?> = dataStore.data.map { it[LAST_CONSOLIDATION_COMPLETED] }
    val locallyBackedUpAccountIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        decodeList<String>(preferences[LOCALLY_BACKED_UP_ACCOUNT_IDS]).toSet()
    }
    val pendingLocalBackupAccountIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        decodeList<String>(preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS]).toSet()
    }
    val cachedAccountProfiles: Flow<List<CachedAccountProfile>> = jsonFlow(CACHED_ACCOUNT_PROFILES)

    suspend fun setSetupComplete() = dataStore.edit {
        it[SETUP_COMPLETE] = true
        it.remove(CREATE_MASTER_KEY_STEP)
    }
    suspend fun clearSetupComplete() = dataStore.edit { it.remove(SETUP_COMPLETE) }

    /** Persists only wizard progress; generated key material remains in encrypted storage. */
    suspend fun setCreateMasterKeyStep(step: CreateMasterKeyStep) = dataStore.edit {
        it[CREATE_MASTER_KEY_STEP] = step.name
    }

    suspend fun setCustomBackupSources(sources: List<BackupSourceConfig>) = dataStore.edit {
        it[CUSTOM_BACKUP_SOURCES] = json.encodeToString(sources)
    }

    suspend fun setPendingSourceWrites(writes: List<PendingSourceWrite>) = dataStore.edit {
        if (writes.isEmpty()) it.remove(PENDING_SOURCE_WRITES)
        else it[PENDING_SOURCE_WRITES] = json.encodeToString(writes.distinctBy { write -> write.accountId to write.sourceId })
    }

    suspend fun upsertPendingSourceWrite(write: PendingSourceWrite) = dataStore.edit { preferences ->
        val current = decodeList<PendingSourceWrite>(preferences[PENDING_SOURCE_WRITES]).associateBy { it.accountId to it.sourceId }.toMutableMap()
        current[write.accountId to write.sourceId] = write
        preferences[PENDING_SOURCE_WRITES] = json.encodeToString(current.values.toList())
    }

    suspend fun clearPendingSourceWrite(accountId: String, sourceId: String) = dataStore.edit { preferences ->
        val remaining = decodeList<PendingSourceWrite>(preferences[PENDING_SOURCE_WRITES]).filterNot {
            it.accountId == accountId && it.sourceId == sourceId
        }
        if (remaining.isEmpty()) preferences.remove(PENDING_SOURCE_WRITES)
        else preferences[PENDING_SOURCE_WRITES] = json.encodeToString(remaining)
    }

    suspend fun setAccountSourceStatuses(statuses: List<AccountSourceStatus>) = dataStore.edit {
        it[ACCOUNT_SOURCE_STATUSES] = json.encodeToString(statuses.distinctBy { status -> status.accountId to status.sourceId })
    }

    suspend fun upsertAccountSourceStatus(status: AccountSourceStatus) = dataStore.edit { preferences ->
        val current = decodeList<AccountSourceStatus>(preferences[ACCOUNT_SOURCE_STATUSES])
            .associateBy { it.accountId to it.sourceId }.toMutableMap()
        current[status.accountId to status.sourceId] = status
        preferences[ACCOUNT_SOURCE_STATUSES] = json.encodeToString(current.values.toList())
    }

    suspend fun setConsolidationCheckpoint(checkpoint: ConsolidationCheckpoint?) = dataStore.edit {
        if (checkpoint == null) it.remove(CONSOLIDATION_CHECKPOINT)
        else it[CONSOLIDATION_CHECKPOINT] = json.encodeToString(checkpoint)
    }

    suspend fun markConsolidationCompleted(atMillis: Long) = dataStore.edit {
        it[LAST_CONSOLIDATION_COMPLETED] = atMillis
        it.remove(CONSOLIDATION_CHECKPOINT)
    }

    /** Records only an account ID; private key material remains in encrypted storage. */
    suspend fun markAccountLocallyBackedUp(accountId: Long) = dataStore.edit { preferences ->
        val ids = decodeList<String>(preferences[LOCALLY_BACKED_UP_ACCOUNT_IDS]).toMutableSet()
        ids += accountId.toString()
        preferences[LOCALLY_BACKED_UP_ACCOUNT_IDS] = json.encodeToString(ids.toList())
        val pending = decodeList<String>(preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS]).filterNot { it == accountId.toString() }
        if (pending.isEmpty()) preferences.remove(PENDING_LOCAL_BACKUP_ACCOUNT_IDS)
        else preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS] = json.encodeToString(pending)
    }

    suspend fun requireAccountLocalBackup(accountId: Long) = dataStore.edit { preferences ->
        val ids = decodeList<String>(preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS]).toMutableSet()
        ids += accountId.toString()
        preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS] = json.encodeToString(ids.toList())
    }

    suspend fun getCachedAccountProfiles(): List<CachedAccountProfile> = cachedAccountProfiles.first()

    /** Stores public profile presentation data separately from encrypted account secrets. */
    suspend fun upsertCachedAccountProfiles(profiles: List<CachedAccountProfile>) = dataStore.edit { preferences ->
        val current = decodeList<CachedAccountProfile>(preferences[CACHED_ACCOUNT_PROFILES])
            .associateBy { it.accountId }.toMutableMap()
        profiles.forEach { profile -> current[profile.accountId] = profile }
        preferences[CACHED_ACCOUNT_PROFILES] = json.encodeToString(current.values.toList())
    }

    /** Removes state from superseded implementations. */
    suspend fun clearLegacyBackupState() = dataStore.edit {
        it.remove(stringPreferencesKey("failed_backup_account_ids"))
        it.remove(stringPreferencesKey("pending_account_backups"))
        it.remove(stringPreferencesKey("backup_statuses"))
        it.remove(stringPreferencesKey("qr_backed_up_account_ids_v1"))
    }

    private inline fun <reified T> jsonFlow(key: Preferences.Key<String>): Flow<List<T>> = dataStore.data.map {
        decodeList(it[key])
    }

    private inline fun <reified T> decodeList(value: String?): List<T> = value?.let {
        runCatching { json.decodeFromString<List<T>>(it) }.getOrDefault(emptyList())
    }.orEmpty()

    companion object {
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val CREATE_MASTER_KEY_STEP = stringPreferencesKey("create_master_key_step_v1")
        val CUSTOM_BACKUP_SOURCES = stringPreferencesKey("custom_backup_sources_v2")
        val PENDING_SOURCE_WRITES = stringPreferencesKey("pending_source_writes_v2")
        val ACCOUNT_SOURCE_STATUSES = stringPreferencesKey("account_source_statuses_v2")
        val CONSOLIDATION_CHECKPOINT = stringPreferencesKey("consolidation_checkpoint_v2")
        val LAST_CONSOLIDATION_COMPLETED = longPreferencesKey("last_consolidation_completed_v2")
        val LOCALLY_BACKED_UP_ACCOUNT_IDS = stringPreferencesKey("locally_backed_up_account_ids_v1")
        val PENDING_LOCAL_BACKUP_ACCOUNT_IDS = stringPreferencesKey("pending_local_backup_account_ids_v1")
        val CACHED_ACCOUNT_PROFILES = stringPreferencesKey("cached_account_profiles_v1")
    }
}

/** Durable resumable points after a generated MasterKey has been committed locally. */
enum class CreateMasterKeyStep {
    BACKUP_INTRO,
    CONFIRM_BACKUP,
}

@Serializable
data class CachedAccountProfile(
    val accountId: Long,
    val displayName: String? = null,
    val createdAtSeconds: Long? = null,
)

@Serializable
data class PendingSourceWrite(
    val accountId: String,
    val sourceId: String,
    val reason: PendingWriteReason,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
)

@Serializable
enum class PendingWriteReason { MASTER_QUORUM, DIRECT_BACKUP, CONSOLIDATION, RETRY }

@Serializable
data class AccountSourceStatus(
    val accountId: String,
    val sourceId: String,
    val state: AccountSourceBackupState,
    val lastCheckedAtMillis: Long? = null,
    val lastSuccessfulBackupAtMillis: Long? = null,
    val lastAttemptAtMillis: Long? = null,
    val errorCategory: BackupErrorCategory? = null,
)

@Serializable
enum class AccountSourceBackupState { PRESENT, WRITE_CONFIRMED, MISSING, CHECKING, BACKING_UP, BACKUP_FAILED, UNKNOWN }

@Serializable
enum class BackupErrorCategory {
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    AUTHENTICATION_FAILED,
    REMOTE_REJECTED,
    REMOTE_PROTOCOL_ERROR,
    INVALID_REMOTE_RECORD,
    LOCAL_ACCOUNT_UNAVAILABLE,
    UNKNOWN,
}

@Serializable
data class ConsolidationCheckpoint(
    val cycleId: String,
    val trigger: ConsolidationTrigger,
    val phase: ConsolidationPhase,
    val currentSourceId: String? = null,
    val currentAccountId: String? = null,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
enum class ConsolidationTrigger { MASTER_IMPORT, STARTUP, PERIODIC, MANUAL, SOURCE_CHANGED }

@Serializable
enum class ConsolidationPhase { RESTORE, BACKUP }
