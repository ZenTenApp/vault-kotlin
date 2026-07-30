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
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.PlaceholderScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Composable
fun RootScreen(navController: NavHostController, viewModel: RootViewModel = hiltViewModel()) {
    val setupComplete by viewModel.setupComplete.collectAsState()
    LaunchedEffect(setupComplete) {
        setupComplete?.let { complete ->
            navController.navigate(if (complete) VaultDestinations.ACCOUNTS else VaultDestinations.GET_STARTED) {
                popUpTo(VaultDestinations.ROOT) { inclusive = true }
            }
        }
    }
    PlaceholderScreen(title = "ZenTen Vault", subtitle = "Loading…")
}

@HiltViewModel
class RootViewModel @Inject constructor(appStorage: AppStorageService) : ViewModel() {
    val setupComplete = appStorage.setupComplete.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
