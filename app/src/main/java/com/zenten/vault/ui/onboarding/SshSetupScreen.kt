package com.zenten.vault.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.zenten.vault.ui.VaultDestinations
import com.zenten.vault.ui.components.PlaceholderScreen

@Composable
fun SshSetupScreen(navController: NavHostController) {
    // TODO(Phase 7): SSH key entry (paste) + passphrase + progress wizard.
    PlaceholderScreen(
        title = "SSH Key Setup",
        subtitle = "Paste key → enter passphrase → run setup (Phase 7).",
        primaryLabel = "Continue (stub → Accounts)",
        onPrimary = {
            navController.navigate(VaultDestinations.ACCOUNTS) {
                popUpTo(VaultDestinations.GET_STARTED) { inclusive = true }
            }
        },
        secondaryLabel = "Back",
        onSecondary = { navController.popBackStack() },
    )
}
