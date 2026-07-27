package com.zenten.vault.ui.newaccount

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.zenten.vault.ui.components.PlaceholderScreen

@Composable
fun NewAccountScreen(navController: NavHostController) {
    // TODO(Phase 7): confirm → progress → back to accounts list.
    PlaceholderScreen(
        title = "Create New Account",
        subtitle = "A fresh SSH key pair will be generated (Phase 7).",
        secondaryLabel = "Cancel",
        onSecondary = { navController.popBackStack() },
    )
}
