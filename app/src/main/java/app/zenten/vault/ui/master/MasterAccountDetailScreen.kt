package app.zenten.vault.ui.master

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.domain.BackupAccountId
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.SetupService
import app.zenten.vault.ui.accounts.BackupSourceRow
import app.zenten.vault.ui.accounts.SourceStatusRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MasterAccountDetailViewModel @Inject constructor(
    sourceRepository: BackupSourceRepository,
    appStorage: AppStorageService,
    private val setupService: SetupService,
    private val scheduler: BackupWorkScheduler,
) : ViewModel() {
    val state = combine(sourceRepository.sources, appStorage.accountSourceStatuses, appStorage.setupComplete) { sources, statuses, complete ->
        MasterAccountDetailState(
            setupComplete = complete,
            sources = sources.filter { it.enabled }.map { source ->
                val status = statuses.firstOrNull { it.accountId == BackupAccountId.MASTER_VALUE && it.sourceId == source.id }
                BackupSourceRow(
                    source.id, source.label, source.endpoint, source.type,
                    status?.state ?: AccountSourceBackupState.UNKNOWN,
                    status?.lastCheckedAtMillis,
                    status?.lastSuccessfulBackupAtMillis,
                    status?.errorCategory,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MasterAccountDetailState())

    fun retryMaster() { viewModelScope.launch { setupService.retryMasterBackup() } }
    fun consolidate() { scheduler.scheduleConsolidation() }
}

data class MasterAccountDetailState(
    val setupComplete: Boolean = false,
    val sources: List<BackupSourceRow> = emptyList(),
)

@Composable
fun MasterAccountDetailScreen(viewModel: MasterAccountDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Master Account", style = MaterialTheme.typography.headlineMedium)
        Text("Backup Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        state.sources.forEach { SourceStatusRow(it) }
        if (state.setupComplete) {
            Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Consolidate backups")
            }
        } else {
            Button(onClick = viewModel::retryMaster, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Retry master setup")
            }
        }
    }
}
