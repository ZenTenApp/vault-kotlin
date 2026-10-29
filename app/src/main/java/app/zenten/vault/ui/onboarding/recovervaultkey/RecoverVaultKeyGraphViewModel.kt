package app.zenten.vault.ui.onboarding.recovervaultkey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

/** Keeps recovery combinations in memory across its combinations and passphrase screens. */
@Composable
internal fun rememberRecoverVaultKeyViewModel(navController: NavHostController): RecoverVaultKeyViewModel {
    // Resolve the graph entry once per composition. The graph entry is stable for the whole
    // recovery flow, and the composable is recreated when the graph is re-entered. Re-resolving
    // on every current-entry change could run while an exiting screen was still composing after
    // the graph had been popped, and getBackStackEntry then threw because the graph was already
    // gone.
    val graphEntry = remember {
        navController.getBackStackEntry(VaultDestinations.RECOVER_VAULT_KEY_GRAPH)
    }
    return hiltViewModel(graphEntry)
}
