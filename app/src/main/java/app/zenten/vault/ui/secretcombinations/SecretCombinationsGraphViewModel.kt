package app.zenten.vault.ui.secretcombinations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
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
    // Resolve the graph entry once per composition. The graph entry is stable for the whole flow,
    // and the composable is recreated when the graph is re-entered. Re-resolving on every
    // current-entry change could run while an exiting screen was still composing after the graph
    // had been popped, and getBackStackEntry then threw because the graph was already gone.
    val graphEntry = remember {
        navController.getBackStackEntry(VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH)
    }
    return hiltViewModel(graphEntry)
}