package app.zenten.vault.ui

import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.zenten.vault.ui.accounts.AccountDetailScreen
import app.zenten.vault.ui.accounts.AccountsListScreen
import app.zenten.vault.ui.newaccount.NewAccountScreen
import app.zenten.vault.ui.onboarding.GetStartedScreen
import app.zenten.vault.ui.onboarding.SshSetupScreen
import app.zenten.vault.ui.root.RootScreen
import androidx.compose.runtime.Composable

@Composable
fun VaultNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = VaultDestinations.ROOT,
        modifier = modifier,
    ) {
        composable(VaultDestinations.ROOT) {
            RootScreen(navController)
        }

        composable(VaultDestinations.GET_STARTED) {
            GetStartedScreen(navController)
        }

        composable(VaultDestinations.GET_STARTED_SSH) {
            SshSetupScreen(navController)
        }

        composable(VaultDestinations.ACCOUNTS) {
            AccountsListScreen(navController)
        }

        composable(
            route = VaultDestinations.ACCOUNT_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            AccountDetailScreen(navController, id)
        }

        composable(VaultDestinations.NEW_ACCOUNT) {
            NewAccountScreen(navController)
        }
    }
}
