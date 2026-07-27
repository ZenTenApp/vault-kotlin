package com.zenten.vault.ui.accounts

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.zenten.vault.ui.VaultDestinations
import com.zenten.vault.ui.components.PlaceholderScreen

@Composable
fun AccountsListScreen(navController: NavHostController) {
    // TODO(Phase 8): master + sub-account cards, pending restores, FAB.
    PlaceholderScreen(
        title = "My Accounts",
        subtitle = "No accounts yet (Phase 8).",
        primaryLabel = "Add Account",
        onPrimary = { navController.navigate(VaultDestinations.NEW_ACCOUNT) },
        secondaryLabel = "Open sample detail",
        onSecondary = { navController.navigate(VaultDestinations.accountDetail(0L)) },
    )
}
