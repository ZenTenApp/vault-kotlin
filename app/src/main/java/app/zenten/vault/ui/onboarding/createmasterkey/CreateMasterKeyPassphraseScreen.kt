package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * **Confirm** stays disabled until the passphrase satisfies every creation rule,
 * and the ViewModel checks the policy again before retaining the secret.
 */
@Composable
fun CreateMasterKeyPassphraseScreen(navController: NavHostController) {
    var passphrase by remember { mutableStateOf("") }
    val viewModel = rememberCreateMasterKeyViewModel(navController)
    val passphraseIsValid = MasterKeyPassphrasePolicy.isValid(passphrase)

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

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.create_master_key_passphrase_requirements),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            VaultPillButton(
                label = stringResource(R.string.create_master_key_passphrase_confirm),
                enabled = passphraseIsValid,
                onClick = {
                    // Recheck in the ViewModel so the policy is enforced even if UI
                    // state changes between composing this button and handling its tap.
                    if (viewModel.choosePassphrase(passphrase)) {
                        navController.navigate(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE)
                    }
                },
            )
        },
    )
}
