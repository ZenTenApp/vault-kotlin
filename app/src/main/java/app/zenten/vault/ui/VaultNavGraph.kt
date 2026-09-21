package app.zenten.vault.ui

import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import app.zenten.vault.ui.accounts.AccountDetailScreen
import app.zenten.vault.ui.accounts.AccountsListScreen
import app.zenten.vault.ui.accounts.LocalAccountBackupExportScreen
import app.zenten.vault.ui.master.MasterAccountDetailScreen
import app.zenten.vault.ui.newaccount.NewAccountScreen
import app.zenten.vault.ui.onboarding.GetStartedScreen
import app.zenten.vault.ui.onboarding.SshSetupScreen
import app.zenten.vault.ui.onboarding.createmasterkey.CreateMasterKeyBackupIntroScreen
import app.zenten.vault.ui.onboarding.createmasterkey.CreateMasterKeyConfirmPassphraseScreen
import app.zenten.vault.ui.onboarding.createmasterkey.CreateMasterKeyIntroScreen
import app.zenten.vault.ui.onboarding.createmasterkey.CreateMasterKeyPassphraseScreen
import app.zenten.vault.ui.root.RootScreen
import app.zenten.vault.ui.settings.SettingsScreen
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

        // Nested graph so all wizard steps can share one graph-scoped ViewModel
        // that carries the chosen passphrase between the steps without ever
        // placing it in a navigation argument.
        navigation(
            route = VaultDestinations.CREATE_MASTER_KEY_GRAPH,
            startDestination = VaultDestinations.CREATE_MASTER_KEY_INTRO,
        ) {
            composable(VaultDestinations.CREATE_MASTER_KEY_INTRO) {
                CreateMasterKeyIntroScreen(navController)
            }

            composable(VaultDestinations.CREATE_MASTER_KEY_PASSPHRASE) {
                CreateMasterKeyPassphraseScreen(navController)
            }

            composable(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE) {
                CreateMasterKeyConfirmPassphraseScreen(navController)
            }

            composable(VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO) {
                CreateMasterKeyBackupIntroScreen(navController)
            }
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

        composable(
            route = VaultDestinations.LOCAL_ACCOUNT_BACKUP_EXPORT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            LocalAccountBackupExportScreen(navController, id)
        }

        composable(VaultDestinations.MASTER_ACCOUNT) {
            MasterAccountDetailScreen()
        }

        composable(VaultDestinations.SETTINGS) {
            SettingsScreen(navController)
        }
    }
}
