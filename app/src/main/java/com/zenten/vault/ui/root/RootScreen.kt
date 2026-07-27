package com.zenten.vault.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import com.zenten.vault.ui.VaultDestinations
import com.zenten.vault.ui.components.PlaceholderScreen

/**
 * App entry. In Phase 6 this reads isSetupComplete() and routes accordingly.
 * For Phase 0 it shows a loading placeholder and routes to onboarding.
 */
@Composable
fun RootScreen(navController: NavHostController) {
    // TODO(Phase 6): replace with real setup-state routing (ui-ux-flows.md §2).
    LaunchedEffect(Unit) {
        navController.navigate(VaultDestinations.GET_STARTED) {
            popUpTo(VaultDestinations.ROOT) { inclusive = true }
        }
    }
    PlaceholderScreen(title = "ZenTen Vault", subtitle = "Loading…")
}
