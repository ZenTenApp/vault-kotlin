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
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.kv.SecretCombinationsViewStep
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
        appStorage.createVaultKeyStep,
        appStorage.secretCombinationsViewStep,
        appStorage.importedVaultKeySetupPending,
    ) { complete, pending, createStep, secretCombinationsStep, importedPending ->
        when {
            // The Settings review flow is irreversible once entered, so it takes precedence
            // over every other launch destination until verification finishes.
            secretCombinationsStep == SecretCombinationsViewStep.SHOW ->
                VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW
            secretCombinationsStep == SecretCombinationsViewStep.CONFIRM ->
                VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM
            complete && pending.isNotEmpty() -> VaultDestinations.localAccountBackupExport(pending.first().toLong())
            complete -> VaultDestinations.ACCOUNTS
            createStep == CreateVaultKeyStep.BACKUP_INTRO -> VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO
            createStep == CreateVaultKeyStep.CONFIRM_BACKUP -> VaultDestinations.CREATE_VAULT_KEY_CONFIRM_BACKUP
            createStep == CreateVaultKeyStep.SECRET_COMBINATIONS -> VaultDestinations.CREATE_VAULT_KEY_SECRET_COMBINATIONS
            // An imported key was committed but its backup never reached quorum. Resume the
            // backup from that stored key instead of Get Started, which could start a new
            // identity and replace the committed one. The generated-key wizard is excluded by
            // its create-step markers above and never sets this flag.
            importedPending -> VaultDestinations.GET_STARTED_SSH
            else -> VaultDestinations.GET_STARTED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
