package app.zenten.vault.ui.onboarding.recovervaultkey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

/** Keeps recovery combinations in memory across its combinations and passphrase screens. */
@Composable
internal fun rememberRecoverVaultKeyViewModel(navController: NavHostController): RecoverVaultKeyViewModel {
    // Use the current destination's NavBackStackEntry as the remember key. It is stable for
    // the lifetime of this screen's composition, so the graph entry is resolved once per screen
    // and never re-resolved during the graph's pop transition (re-resolving on every
    // current-entry change could run while an exiting screen was still composing after the graph
    // had been popped, and getBackStackEntry then threw because the graph was gone). Keying on a
    // NavBackStackEntry also satisfies the UnrememberedGetBackStackEntry lint check.
    val backStackEntry = LocalViewModelStoreOwner.current as NavBackStackEntry
    val graphEntry = remember(backStackEntry) {
        navController.getBackStackEntry(VaultDestinations.RECOVER_VAULT_KEY_GRAPH)
    }
    return hiltViewModel(graphEntry)
}
