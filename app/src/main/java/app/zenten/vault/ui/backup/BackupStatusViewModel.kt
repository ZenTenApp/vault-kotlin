package app.zenten.vault.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.kv.AccountBackupStatus
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.domain.BackupSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupStatusViewModel @Inject constructor(
    appStorage: AppStorageService,
    private val syncService: BackupSyncService,
) : ViewModel() {
    val statuses: StateFlow<List<AccountBackupStatus>> = appStorage.backupStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var syncing by mutableStateOf(false)
        private set

    fun syncNow() {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            try { syncService.sync(force = true) } finally { syncing = false }
        }
    }
}
