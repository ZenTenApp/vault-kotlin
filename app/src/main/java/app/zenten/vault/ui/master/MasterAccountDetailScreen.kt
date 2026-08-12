package app.zenten.vault.ui.master

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
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
    secureStorage: SecureStorageService,
    sourceRepository: BackupSourceRepository,
    appStorage: AppStorageService,
    private val setupService: SetupService,
    private val scheduler: BackupWorkScheduler,
) : ViewModel() {
    private val master = secureStorage.getMasterAccount()
    val state = combine(sourceRepository.sources, appStorage.accountSourceStatuses, appStorage.setupComplete) { sources, statuses, complete ->
        MasterAccountDetailState(
            publicKey = master?.pubkey,
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
    val publicKey: String? = null,
    val setupComplete: Boolean = false,
    val sources: List<BackupSourceRow> = emptyList(),
)

@Composable
fun MasterAccountDetailScreen(navController: NavHostController, viewModel: MasterAccountDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Master Account", style = MaterialTheme.typography.headlineMedium)
        Text(if (state.setupComplete) "Backup quorum reached" else "Backup quorum required", modifier = Modifier.padding(top = 8.dp))
        Text("SSH public key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        Text(state.publicKey ?: "Unavailable", style = MaterialTheme.typography.bodySmall)
        Text("Backup sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        state.sources.forEach { SourceStatusRow(it) }
        Button(onClick = viewModel::retryMaster, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Retry master backup") }
        Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Consolidate now") }
        TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
