package com.zenten.vault.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.zenten.vault.ui.VaultDestinations
import com.zenten.vault.ui.components.PlaceholderScreen

@Composable
fun GetStartedScreen(navController: NavHostController) {
    PlaceholderScreen(
        title = "Welcome to ZenTen Vault",
        subtitle = "Import your SSH key to create your master account.",
        primaryLabel = "Import SSH Key",
        onPrimary = { navController.navigate(VaultDestinations.GET_STARTED_SSH) },
    )
}
