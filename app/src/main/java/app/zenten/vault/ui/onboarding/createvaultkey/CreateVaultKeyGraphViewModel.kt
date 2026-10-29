package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

/**
 * Returns the [CreateVaultKeyViewModel] shared by every screen of the wizard.
 *
 * The ViewModel is scoped to the wizard's nested navigation graph rather than to
 * an individual screen, so the passphrase captured on the passphrase step is
 * still available when the confirmation step verifies it. Android clears the
 * instance when the whole graph leaves the back stack, which is exactly when the
 * in-memory secrets should disappear.
 */
@Composable
internal fun rememberCreateVaultKeyViewModel(navController: NavHostController): CreateVaultKeyViewModel {
    return rememberCreateVaultKeyGraphViewModel(navController)
}

@Composable
private inline fun <reified VM : androidx.lifecycle.ViewModel> rememberCreateVaultKeyGraphViewModel(
    navController: NavHostController,
): VM {
    // Resolve the graph entry once per composition. The graph entry is stable for the
    // whole wizard, and the composable is recreated when the graph is re-entered, so a
    // plain remember is sufficient. Re-resolving on every current-entry change used to
    // run while the exiting screen was still composing after the graph had been popped
    // (verification now jumps straight to Accounts), and getBackStackEntry then threw
    // because the graph was no longer on the back stack.
    val graphEntry = remember {
        navController.getBackStackEntry(VaultDestinations.CREATE_VAULT_KEY_GRAPH)
    }
    return hiltViewModel(graphEntry)
}
