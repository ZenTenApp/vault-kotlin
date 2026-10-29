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
import app.zenten.vault.ui.vaultKey.VaultKeyDetailScreen
import app.zenten.vault.ui.newaccount.NewAccountScreen
import app.zenten.vault.ui.onboarding.GetStartedScreen
import app.zenten.vault.ui.onboarding.SshSetupScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyBackupIntroScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyConfirmBackupScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyConfirmPassphraseScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyCreatingScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyConfirmSecretCombinationsScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyIntroScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeyPassphraseScreen
import app.zenten.vault.ui.onboarding.createvaultkey.CreateVaultKeySecretCombinationsScreen
import app.zenten.vault.ui.root.RootScreen
import app.zenten.vault.ui.secretcombinations.SecretCombinationsCompleteScreen
import app.zenten.vault.ui.secretcombinations.SecretCombinationsConfirmScreen
import app.zenten.vault.ui.secretcombinations.SecretCombinationsShowScreen
import app.zenten.vault.ui.secretcombinations.SecretCombinationsVerifyScreen
import app.zenten.vault.ui.onboarding.recovervaultkey.RecoverVaultKeyCombinationsScreen
import app.zenten.vault.ui.onboarding.recovervaultkey.RecoverVaultKeyPassphraseScreen
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

        navigation(
            route = VaultDestinations.RECOVER_VAULT_KEY_GRAPH,
            startDestination = VaultDestinations.RECOVER_VAULT_KEY_COMBINATIONS,
        ) {
            composable(VaultDestinations.RECOVER_VAULT_KEY_COMBINATIONS) {
                RecoverVaultKeyCombinationsScreen(navController)
            }
            composable(VaultDestinations.RECOVER_VAULT_KEY_PASSPHRASE) {
                RecoverVaultKeyPassphraseScreen(navController)
            }
        }

        // Nested graph so all wizard steps can share one graph-scoped ViewModel
        // that carries the chosen passphrase between the steps without ever
        // placing it in a navigation argument.
        navigation(
            route = VaultDestinations.CREATE_VAULT_KEY_GRAPH,
            startDestination = VaultDestinations.CREATE_VAULT_KEY_INTRO,
        ) {
            composable(VaultDestinations.CREATE_VAULT_KEY_INTRO) {
                CreateVaultKeyIntroScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_PASSPHRASE) {
                CreateVaultKeyPassphraseScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_CONFIRM_PASSPHRASE) {
                CreateVaultKeyConfirmPassphraseScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_CREATING) {
                CreateVaultKeyCreatingScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO) {
                CreateVaultKeyBackupIntroScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_CONFIRM_BACKUP) {
                CreateVaultKeyConfirmBackupScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_SECRET_COMBINATIONS) {
                CreateVaultKeySecretCombinationsScreen(navController)
            }

            composable(VaultDestinations.CREATE_VAULT_KEY_CONFIRM_SECRET_COMBINATIONS) {
                CreateVaultKeyConfirmSecretCombinationsScreen(navController)
            }
        }

        // The Settings review flow uses a nested graph so its screens share one graph-scoped
        // ViewModel and the transcribed values never pass through navigation arguments.
        navigation(
            route = VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH,
            startDestination = VaultDestinations.VIEW_SECRET_COMBINATIONS_VERIFY,
        ) {
            composable(VaultDestinations.VIEW_SECRET_COMBINATIONS_VERIFY) {
                SecretCombinationsVerifyScreen(navController)
            }

            composable(VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW) {
                SecretCombinationsShowScreen(navController)
            }

            composable(VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM) {
                SecretCombinationsConfirmScreen(navController)
            }

            composable(VaultDestinations.VIEW_SECRET_COMBINATIONS_COMPLETE) {
                SecretCombinationsCompleteScreen(navController)
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

        composable(VaultDestinations.VAULT_KEY) {
            VaultKeyDetailScreen(navController)
        }

        composable(VaultDestinations.SETTINGS) {
            SettingsScreen(navController)
        }
    }
}
