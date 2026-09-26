package app.zenten.vault.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateMasterKeyStep
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.PlaceholderScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Composable
fun RootScreen(navController: NavHostController, viewModel: RootViewModel = hiltViewModel()) {
    val destination by viewModel.destination.collectAsState()
    LaunchedEffect(destination) {
        destination?.let { target ->
            navController.navigate(target) {
                popUpTo(VaultDestinations.ROOT) { inclusive = true }
            }
        }
    }
    PlaceholderScreen(title = "ZenTen Vault", subtitle = "Loading…")
}

@HiltViewModel
class RootViewModel @Inject constructor(appStorage: AppStorageService) : ViewModel() {
    val destination = combine(
        appStorage.setupComplete,
        appStorage.pendingLocalBackupAccountIds,
        appStorage.createMasterKeyStep,
    ) { complete, pending, createStep ->
        when {
            complete && pending.isNotEmpty() -> VaultDestinations.localAccountBackupExport(pending.first().toLong())
            complete -> VaultDestinations.ACCOUNTS
            createStep == CreateMasterKeyStep.BACKUP_INTRO -> VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO
            createStep == CreateMasterKeyStep.CONFIRM_BACKUP -> VaultDestinations.CREATE_MASTER_KEY_CONFIRM_BACKUP
            createStep == CreateMasterKeyStep.SECRET_COMBINATIONS -> VaultDestinations.CREATE_MASTER_KEY_SECRET_COMBINATIONS
            createStep == CreateMasterKeyStep.CONGRATULATIONS -> VaultDestinations.CREATE_MASTER_KEY_CONGRATULATIONS
            else -> VaultDestinations.GET_STARTED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
