package app.zenten.vault.ui.secretcombinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

/**
 * Returns the [SecretCombinationsViewModel] shared by every screen of the review flow.
 *
 * The ViewModel is scoped to the flow's nested navigation graph, not to one screen, so the
 * transcribed values survive navigation between the display and verification steps. Android
 * clears the instance when the whole graph leaves the back stack.
 */
@Composable
internal fun rememberSecretCombinationsViewModel(
    navController: NavHostController,
): SecretCombinationsViewModel {
    // Use the current destination's NavBackStackEntry as the remember key. It is stable for
    // the lifetime of this screen's composition, so the graph entry is resolved once per screen
    // and never re-resolved during the graph's pop transition (re-resolving on every
    // current-entry change could run while an exiting screen was still composing after the graph
    // had been popped, and getBackStackEntry then threw because the graph was gone). Keying on a
    // NavBackStackEntry also satisfies the UnrememberedGetBackStackEntry lint check.
    val backStackEntry = LocalViewModelStoreOwner.current as NavBackStackEntry
    val graphEntry = remember(backStackEntry) {
        navController.getBackStackEntry(VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH)
    }
    return hiltViewModel(graphEntry)
}