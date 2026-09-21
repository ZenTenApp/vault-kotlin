package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton

/**
 * Step 3 of the in-app **Create MasterKey** flow: re-enter the passphrase
 * chosen in step 2.
 *
 * Re-entry guards against a typo in a secret the user can never recover.
 * **Verify** compares this entry with the stored one through the wizard's
 * graph-scoped [CreateMasterKeyViewModel]:
 *
 * - on a mismatch nothing is generated and an inline error is shown, so a typo
 *   can never be baked into the MasterKey;
 * - on a match a fresh Ed25519 OpenSSH key encrypted with that passphrase is
 *   generated, saved locally, backed up to every configured source, and the
 *   wizard advances to the next step.
 *
 * The button is inert while generation runs because bcrypt_pbkdf key derivation
 * takes a noticeable amount of time; the label switches to a progress message
 * so the wait is visible.
 */
@Composable
fun CreateMasterKeyConfirmPassphraseScreen(navController: NavHostController) {
    var reEnteredPassphrase by remember { mutableStateOf("") }
    val viewModel = rememberCreateMasterKeyViewModel(navController)
    val state by viewModel.state.collectAsState()

    // Passphrase input is on screen here, so block screenshots and recordings.
    ScreenshotProtection()

    // One-shot: advance only when the ViewModel reports a freshly generated key,
    // then immediately clear the signal so returning here does not re-navigate.
    LaunchedEffect(state.navigateToBackup) {
        if (state.navigateToBackup) {
            viewModel.onBackupStepShown()
            navController.navigate(VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO)
        }
    }

    // Defensive: step 2 was never completed (for example the process was killed
    // and restored straight into this screen), so there is nothing to verify.
    LaunchedEffect(state.passphraseMissing) {
        if (state.passphraseMissing) navController.popBackStack()
    }

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
                onValueChange = {
                    reEnteredPassphrase = it
                    // Editing means the user is reacting to the message, so drop it.
                    viewModel.clearVerificationMessage()
                },
                // Last input of the wizard, so the keyboard shows "Done".
                imeAction = ImeAction.Done,
            )

            if (state.mismatch || state.generationFailed) {
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(
                        if (state.mismatch) {
                            R.string.create_master_key_confirm_passphrase_mismatch
                        } else {
                            R.string.create_master_key_confirm_passphrase_generation_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            VaultPillButton(
                label = stringResource(
                    if (state.generating) {
                        R.string.create_master_key_confirm_passphrase_generating
                    } else {
                        R.string.create_master_key_confirm_passphrase_verify
                    },
                ),
                // Blocked while generating so a second tap cannot start a second key.
                enabled = reEnteredPassphrase.isNotEmpty() && !state.generating,
                onClick = { viewModel.verifyAndGenerate(reEnteredPassphrase) },
            )
        },
    )
}
