package app.zenten.vault.ui.onboarding.recovervaultkey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

/** Keeps recovery combinations in memory across its combinations and passphrase screens. */
@Composable
internal fun rememberRecoverVaultKeyViewModel(navController: NavHostController): RecoverVaultKeyViewModel {
    // Key on the current NavBackStackEntry so the graph-scoped ViewModel is
    // re-resolved correctly as the user moves between recovery steps.
    val graphEntry = remember(navController.currentBackStackEntry) {
        navController.getBackStackEntry(VaultDestinations.RECOVER_VAULT_KEY_GRAPH)
    }
    return hiltViewModel(graphEntry)
}
