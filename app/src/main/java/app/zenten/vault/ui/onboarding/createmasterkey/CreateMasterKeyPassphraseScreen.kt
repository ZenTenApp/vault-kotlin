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
 * The passphrase is kept only in this composable's state and is deliberately
 * not placed in a navigation argument, because route arguments are retained in
 * the back-stack state (and can be saved/restored by the system). Step 3 asks
 * the user to re-enter it; matching the two entries is wired up separately once
 * key generation exists.
 *
 * **Confirm** stays disabled until something has been typed so the flow cannot
 * advance with an empty passphrase.
 */
@Composable
fun CreateMasterKeyPassphraseScreen(navController: NavHostController) {
    var passphrase by remember { mutableStateOf("") }

    // Passphrase input is on screen here, so block screenshots and recordings.
    ScreenshotProtection()

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_passphrase_title),
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
                emphasized = true,
                enabled = passphrase.isNotEmpty(),
                onClick = { navController.navigate(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE) },
            )
        },
    )
}
