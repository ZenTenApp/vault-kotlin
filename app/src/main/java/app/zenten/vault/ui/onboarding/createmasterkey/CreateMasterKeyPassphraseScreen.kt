package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton

/**
 * Step 2 of the in-app **Create MasterKey** flow: choose the passphrase that
 * will protect the generated MasterKey.
 *
 * On **Confirm** the passphrase is handed to the wizard's graph-scoped
 * [CreateMasterKeyViewModel] so step 3 can compare it with the re-entry. It is
 * deliberately not placed in a navigation argument, because route arguments are
 * retained in back-stack state and can be saved and restored by the system.
 *
 * **Confirm** stays disabled until something has been typed so the flow cannot
 * advance with an empty passphrase.
 */
@Composable
fun CreateMasterKeyPassphraseScreen(navController: NavHostController) {
    var passphrase by remember { mutableStateOf("") }
    val viewModel = rememberCreateMasterKeyViewModel(navController)

    // Passphrase input is on screen here, so block screenshots and recordings.
    ScreenshotProtection()

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_passphrase_title),
        heroIcon = R.drawable.hero_icon_passphrase,
        heroIconDescription = stringResource(R.string.create_master_key_passphrase_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(28.dp))

            PassphraseField(
                label = stringResource(R.string.create_master_key_passphrase_label),
                value = passphrase,
                onValueChange = { passphrase = it },
            )
        },
        actions = {
            VaultPillButton(
                label = stringResource(R.string.create_master_key_passphrase_confirm),
                enabled = passphrase.isNotEmpty(),
                onClick = {
                    viewModel.choosePassphrase(passphrase)
                    navController.navigate(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE)
                },
            )
        },
    )
}
