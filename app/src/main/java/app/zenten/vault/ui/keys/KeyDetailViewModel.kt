package app.zenten.vault.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.kv.KeySourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.BackupErrorCategory
import app.zenten.vault.data.model.Key
import app.zenten.vault.domain.KeyService
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.domain.BackupWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KeyDetailViewModel @Inject constructor(
    private val keyService: KeyService,
    sourceRepository: BackupSourceRepository,
    private val appStorage: AppStorageService,
    private val scheduler: BackupWorkScheduler,
) : ViewModel() {
    private val keyId = MutableStateFlow<Long?>(null)

    val state: StateFlow<KeyDetailState> = combine(
        keyId,
        sourceRepository.sources,
        appStorage.keySourceStatuses,
    ) { id, sources, statuses ->
        val key = id?.let(keyService::getKey)
        KeyDetailState(
            key = key,
            sources = sources.filter { it.enabled }.map { source ->
                val status = statuses.firstOrNull { it.keyId == id?.toString() && it.sourceId == source.id }
                BackupSourceRow(
                    id = source.id,
                    label = source.label,
                    endpoint = source.endpoint,
                    type = source.type,
                    state = status?.state ?: KeySourceBackupState.UNKNOWN,
                    lastCheckedAtMillis = status?.lastCheckedAtMillis,
                    lastSuccessfulBackupAtMillis = status?.lastSuccessfulBackupAtMillis,
                    error = status?.errorCategory,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeyDetailState())

    fun load(id: Long) {
        keyId.value = id
    }
    fun consolidate() { keyId.value?.let(scheduler::scheduleKeyConsolidation) }

    /**
     * Confirms the passphrase protecting the loaded key before its key is exported.
     *
     * Suspends because the check decrypts the key with bcrypt_pbkdf; [KeyService]
     * moves that derivation to [kotlinx.coroutines.Dispatchers.Default], so the caller must not
     * invoke it directly from a Compose click handler on the main thread.
     */
    suspend fun verifyExportPassphrase(passphrase: String): Boolean =
        keyId.value?.let { keyService.verifyKeyPassphrase(it, passphrase).isSuccess } == true
}

data class KeyDetailState(
    val key: Key? = null,
    val sources: List<BackupSourceRow> = emptyList(),
)

data class BackupSourceRow(
    val id: String,
    val label: String,
    val endpoint: String,
    val type: BackupSourceType,
    val state: KeySourceBackupState,
    val lastCheckedAtMillis: Long?,
    val lastSuccessfulBackupAtMillis: Long?,
    val error: BackupErrorCategory?,
)