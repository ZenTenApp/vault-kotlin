package com.zenten.vault.ui.accounts

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.zenten.vault.ui.components.PlaceholderScreen

@Composable
fun AccountDetailScreen(navController: NavHostController, accountId: Long) {
    // TODO(Phase 8): fingerprint, pubkey, npub (master), backup flag, delete.
    PlaceholderScreen(
        title = "Account Detail",
        subtitle = "id = $accountId (Phase 8).",
        secondaryLabel = "Back",
        onSecondary = { navController.popBackStack() },
    )
}
