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
    // Key on the current NavBackStackEntry so the graph entry is re-resolved as the user moves
    // between the flow's destinations.
    val graphEntry = remember(navController.currentBackStackEntry) {
        navController.getBackStackEntry(VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH)
    }
    return hiltViewModel(graphEntry)
}