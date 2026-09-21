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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultPillButton

/**
 * Step 3 of the in-app **Create MasterKey** flow: re-enter the passphrase
 * chosen in step 2.
 *
 * Re-entry guards against a typo in a secret the user can never recover. The
 * **Verify** action is intentionally inert for now: comparing the two entries
 * and generating the MasterKey depends on the generation step, which is
 * specified separately. Until then this screen only collects input, so nothing
 * partially-created can be persisted.
 */
@Composable
fun CreateMasterKeyConfirmPassphraseScreen(navController: NavHostController) {
    var reEnteredPassphrase by remember { mutableStateOf("") }

    // Passphrase input is on screen here, so block screenshots and recordings.
    ScreenshotProtection()

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_confirm_passphrase_title),
        heroIcon = R.drawable.hero_icon_confirm_passphrase,
        heroIconDescription = stringResource(R.string.create_master_key_confirm_passphrase_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(28.dp))

            PassphraseField(
                label = stringResource(R.string.create_master_key_confirm_passphrase_label),
                value = reEnteredPassphrase,
                onValueChange = { reEnteredPassphrase = it },
                // Last input of the wizard, so the keyboard shows "Done".
                imeAction = ImeAction.Done,
            )
        },
        actions = {
            VaultPillButton(
                label = stringResource(R.string.create_master_key_confirm_passphrase_verify),
                enabled = reEnteredPassphrase.isNotEmpty(),
                onClick = {
                    // Intentionally does nothing yet: passphrase matching and
                    // MasterKey generation are defined in a follow-up step.
                },
            )
        },
    )
}
