package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.BackupErrorCategory
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
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
class AccountDetailViewModel @Inject constructor(
    private val accountService: AccountService,
    sourceRepository: BackupSourceRepository,
    private val appStorage: AppStorageService,
    private val scheduler: BackupWorkScheduler,
) : ViewModel() {
    private val accountId = MutableStateFlow<Long?>(null)

    val state: StateFlow<AccountDetailState> = combine(
        accountId,
        sourceRepository.sources,
        appStorage.accountSourceStatuses,
    ) { id, sources, statuses ->
        val account = id?.let(accountService::getAccount)
        AccountDetailState(
            account = account,
            sources = sources.filter { it.enabled }.map { source ->
                val status = statuses.firstOrNull { it.accountId == id?.toString() && it.sourceId == source.id }
                BackupSourceRow(
                    id = source.id,
                    label = source.label,
                    endpoint = source.endpoint,
                    type = source.type,
                    state = status?.state ?: AccountSourceBackupState.UNKNOWN,
                    lastCheckedAtMillis = status?.lastCheckedAtMillis,
                    lastSuccessfulBackupAtMillis = status?.lastSuccessfulBackupAtMillis,
                    error = status?.errorCategory,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    fun load(id: Long) {
        accountId.value = id
    }
    fun consolidate() { accountId.value?.let(scheduler::scheduleAccountConsolidation) }

    /** Confirms the passphrase protecting the loaded account before its key is exported. */
    fun verifyExportPassphrase(passphrase: String): Boolean =
        accountId.value?.let { accountService.verifyAccountPassphrase(it, passphrase).isSuccess } == true
}

data class AccountDetailState(
    val account: Account? = null,
    val sources: List<BackupSourceRow> = emptyList(),
)

data class BackupSourceRow(
    val id: String,
    val label: String,
    val endpoint: String,
    val type: BackupSourceType,
    val state: AccountSourceBackupState,
    val lastCheckedAtMillis: Long?,
    val lastSuccessfulBackupAtMillis: Long?,
    val error: BackupErrorCategory?,
)