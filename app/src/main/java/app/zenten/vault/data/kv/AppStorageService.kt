package app.zenten.vault.data.kv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.zenten.vault.crypto.SshKeyCodec
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
    val sshKdfRounds: Flow<Int> = dataStore.data.map { preferences ->
        preferences[SSH_KDF_ROUNDS]
            ?.takeIf { it in SshKeyCodec.SUPPORTED_BCRYPT_PBKDF_ROUNDS }
            ?: SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS
    }
    val createVaultKeyStep: Flow<CreateVaultKeyStep?> = dataStore.data.map { preferences ->
        preferences[CREATE_VAULT_KEY_STEP]?.let { stored ->
            runCatching { CreateVaultKeyStep.valueOf(stored) }.getOrNull()
        }
    }
    /**
     * Durable progress of the Settings-initiated **Secret Combinations** review flow.
     *
     * Non-null means the user already passed the passphrase gate and must finish transcribing
     * and verifying the combinations before the app returns to normal navigation, including
     * after a process restart.
     */
    val secretCombinationsViewStep: Flow<SecretCombinationsViewStep?> = dataStore.data.map { preferences ->
        preferences[SECRET_COMBINATIONS_VIEW_STEP]?.let { stored ->
            runCatching { SecretCombinationsViewStep.valueOf(stored) }.getOrNull()
        }
    }
    val customBackupSources: Flow<List<BackupSourceConfig>> = jsonFlow(CUSTOM_BACKUP_SOURCES)
    val pendingSourceWrites: Flow<List<PendingSourceWrite>> = jsonFlow(PENDING_SOURCE_WRITES)
    val accountSourceStatuses: Flow<List<AccountSourceStatus>> = jsonFlow(ACCOUNT_SOURCE_STATUSES)
    val consolidationCheckpoint: Flow<ConsolidationCheckpoint?> = dataStore.data.map { preferences ->
        preferences[CONSOLIDATION_CHECKPOINT]?.let { runCatching { json.decodeFromString<ConsolidationCheckpoint>(it) }.getOrNull() }
    }
    val lastConsolidationCompletedAtMillis: Flow<Long?> = dataStore.data.map { it[LAST_CONSOLIDATION_COMPLETED] }

    /** Tracked apart from account consolidation because the Vault Key has its own cycle. */
    val lastVaultKeyConsolidationCompletedAtMillis: Flow<Long?> =
        dataStore.data.map { it[LAST_VAULT_KEY_CONSOLIDATION_COMPLETED] }
    val locallyBackedUpAccountIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        decodeList<String>(preferences[LOCALLY_BACKED_UP_ACCOUNT_IDS]).toSet()
    }
    val pendingLocalBackupAccountIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        decodeList<String>(preferences[PENDING_LOCAL_BACKUP_ACCOUNT_IDS]).toSet()
    }
    val cachedAccountProfiles: Flow<List<CachedAccountProfile>> = jsonFlow(CACHED_ACCOUNT_PROFILES)

    /**
     * Failed **Confirm Backup** pastes for the generated VaultKey. Persisted so a
     * process restart cannot reset the escalating lockout, and kept in
     * [AppStorageService] rather than the wizard because only the counter and the
     * non-secret deadline are stored here — never the pasted or stored key.
     */
    val vaultKeyPasteAttempts: Flow<Int> = dataStore.data.map { it[VAULT_KEY_PASTE_ATTEMPTS] ?: 0 }
    val vaultKeyPasteBlockedUntilMillis: Flow<Long?> = dataStore.data.map { it[VAULT_KEY_PASTE_BLOCKED_UNTIL] }

    /**
     * Whether the last Vault Key backup satisfied one relay plus one Brave Sync quorum.
     *
     * Kept apart from [setupComplete] because the generated-VaultKey wizard finishes its local
     * export and transcription steps after the backup attempt, so the two facts can diverge.
     * Persisted so the last wizard step and a process restart cannot disagree about it.
     */
    val vaultKeyQuorumReached: Flow<Boolean> = dataStore.data.map { it[VAULT_KEY_QUORUM_REACHED] ?: false }

    /**
     * Whether an imported Vault Key is stored locally while its backup still owes quorum.
     *
     * Set once an import has committed the validated key to secure storage, and cleared when
     * setup completes or is restarted. Keeping it durable is what lets the launch router send
     * the user back to the backup retry instead of Get Started after a process death, and it
     * distinguishes this state from the generated-key create wizard, which owns
     * [createVaultKeyStep] instead.
     */
    val importedVaultKeySetupPending: Flow<Boolean> =
        dataStore.data.map { it[IMPORTED_VAULT_KEY_SETUP_PENDING] ?: false }

    suspend fun setSetupComplete() = dataStore.edit {
        it[SETUP_COMPLETE] = true
        it.remove(CREATE_VAULT_KEY_STEP)
        // A completed setup no longer has a backup to resume.
        it.remove(IMPORTED_VAULT_KEY_SETUP_PENDING)
    }

    /**
     * Clears completed setup together with its quorum evidence: setup that has to be
     * established again must not inherit a backup claim from the previous attempt.
     * A previous attempt's pending-import state is cleared too; the caller records the new
     * attempt with [requireImportedVaultKeyBackup] when it committed an imported key.
     */
    suspend fun clearSetupComplete() = dataStore.edit {
        it.remove(SETUP_COMPLETE)
        it.remove(VAULT_KEY_QUORUM_REACHED)
        it.remove(IMPORTED_VAULT_KEY_SETUP_PENDING)
    }

    /** Records that an imported Vault Key is stored but setup is still waiting for quorum. */
    suspend fun requireImportedVaultKeyBackup() = dataStore.edit {
        it[IMPORTED_VAULT_KEY_SETUP_PENDING] = true
    }

    /** Records the remote-backup quorum outcome of the latest Vault Key backup attempt. */
    suspend fun setVaultKeyQuorumReached(reached: Boolean) = dataStore.edit {
        it[VAULT_KEY_QUORUM_REACHED] = reached
    }

    suspend fun setSshKdfRounds(rounds: Int) {
        require(rounds in SshKeyCodec.SUPPORTED_BCRYPT_PBKDF_ROUNDS) { "Unsupported bcrypt KDF round count: $rounds" }
        dataStore.edit { it[SSH_KDF_ROUNDS] = rounds }
    }

    /** Persists only wizard progress; generated key material remains in encrypted storage. */
    suspend fun setCreateVaultKeyStep(step: CreateVaultKeyStep) = dataStore.edit {
        it[CREATE_VAULT_KEY_STEP] = step.name
    }

    /** Records which irreversible step of the Secret Combinations review flow is active. */
    suspend fun setSecretCombinationsViewStep(step: SecretCombinationsViewStep) = dataStore.edit {
        it[SECRET_COMBINATIONS_VIEW_STEP] = step.name
    }

    /** Clears the review flow once every combination has been verified. */
    suspend fun clearSecretCombinationsViewStep() = dataStore.edit {
        it.remove(SECRET_COMBINATIONS_VIEW_STEP)
    }

    /**
     * Stores the Confirm Backup paste guard. A zero attempt count clears both
     * keys; a null deadline keeps the count while removing an expired block.
     */
    suspend fun setVaultKeyPasteGuard(attempts: Int, blockedUntilMillis: Long?) = dataStore.edit { preferences ->
        if (attempts <= 0) {
            preferences.remove(VAULT_KEY_PASTE_ATTEMPTS)
            preferences.remove(VAULT_KEY_PASTE_BLOCKED_UNTIL)
        } else {
            preferences[VAULT_KEY_PASTE_ATTEMPTS] = attempts
            if (blockedUntilMillis == null) preferences.remove(VAULT_KEY_PASTE_BLOCKED_UNTIL)
            else preferences[VAULT_KEY_PASTE_BLOCKED_UNTIL] = blockedUntilMillis
        }
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

    /**
     * Records when a Vault Key cycle last finished. It does not clear the consolidation
     * checkpoint, which belongs to the separate account-consolidation cycle.
     */
    suspend fun markVaultKeyConsolidationCompleted(atMillis: Long) = dataStore.edit {
        it[LAST_VAULT_KEY_CONSOLIDATION_COMPLETED] = atMillis
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
        val VAULT_KEY_QUORUM_REACHED = booleanPreferencesKey("vault_key_quorum_reached_v1")
        val IMPORTED_VAULT_KEY_SETUP_PENDING = booleanPreferencesKey("imported_vault_key_setup_pending_v1")
        val SSH_KDF_ROUNDS = intPreferencesKey("ssh_kdf_rounds_v1")
        val CREATE_VAULT_KEY_STEP = stringPreferencesKey("create_vault_key_step_v1")
        val SECRET_COMBINATIONS_VIEW_STEP = stringPreferencesKey("secret_combinations_view_step_v1")
        val CUSTOM_BACKUP_SOURCES = stringPreferencesKey("custom_backup_sources_v2")
        val PENDING_SOURCE_WRITES = stringPreferencesKey("pending_source_writes_v2")
        val ACCOUNT_SOURCE_STATUSES = stringPreferencesKey("account_source_statuses_v2")
        val CONSOLIDATION_CHECKPOINT = stringPreferencesKey("consolidation_checkpoint_v2")
        val LAST_CONSOLIDATION_COMPLETED = longPreferencesKey("last_consolidation_completed_v2")
        val LAST_VAULT_KEY_CONSOLIDATION_COMPLETED = longPreferencesKey("last_vault_key_consolidation_completed_v1")
        val LOCALLY_BACKED_UP_ACCOUNT_IDS = stringPreferencesKey("locally_backed_up_account_ids_v1")
        val PENDING_LOCAL_BACKUP_ACCOUNT_IDS = stringPreferencesKey("pending_local_backup_account_ids_v1")
        val CACHED_ACCOUNT_PROFILES = stringPreferencesKey("cached_account_profiles_v1")
        val VAULT_KEY_PASTE_ATTEMPTS = intPreferencesKey("vault_key_paste_attempts_v1")
        val VAULT_KEY_PASTE_BLOCKED_UNTIL = longPreferencesKey("vault_key_paste_blocked_until_v1")
    }
}

/** Durable resumable points after a generated VaultKey has been committed locally. */
enum class CreateVaultKeyStep {
    BACKUP_INTRO,
    CONFIRM_BACKUP,
    SECRET_COMBINATIONS,
}

/**
 * Resumable steps of the Settings-initiated Secret Combinations review flow.
 *
 * The flow cannot be abandoned once the passphrase gate is passed, so these values are read
 * at launch to reopen the exact unfinished step.
 */
enum class SecretCombinationsViewStep {
    SHOW,
    CONFIRM,
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
enum class PendingWriteReason { VAULT_KEY_QUORUM, DIRECT_BACKUP, CONSOLIDATION, RETRY }

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
enum class ConsolidationTrigger { VAULT_KEY_IMPORT, STARTUP, MANUAL, SOURCE_CHANGED }

@Serializable
enum class ConsolidationPhase { RESTORE, BACKUP }
