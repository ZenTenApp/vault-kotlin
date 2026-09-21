package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultPillButton

/**
 * Step 4 of the in-app **Create MasterKey** flow: explain the first Vault
 * Backup before it is produced.
 *
 * At this point the MasterKey has been generated in memory (the passphrase
 * entries matched on step 3). This screen only sets expectations — the user has
 * to store the backup themselves, somewhere they control — and repeats the
 * unrecoverable-loss warning shared with the wizard's intro step.
 *
 * **Confirm** is intentionally inert for now: producing, displaying, and
 * persisting the backup is specified as a follow-up step, so nothing
 * half-created can be written yet.
 */
@Composable
fun CreateMasterKeyBackupIntroScreen(navController: NavHostController) {
    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_backup_intro_title),
        heroIcon = R.drawable.hero_icon_create_backup,
        heroIconDescription = stringResource(R.string.create_master_key_backup_intro_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(12.dp))

            CreateMasterKeyDescription(stringResource(R.string.create_master_key_backup_intro_body))
        },
        actions = {
            // Centered to match the design, which sits the warning directly
            // above the action rather than aligning it with body copy.
            CreateMasterKeyWarningRow(centered = true)

            Spacer(Modifier.height(16.dp))

            VaultPillButton(
                label = stringResource(R.string.create_master_key_backup_intro_confirm),
                onClick = {
                    // Intentionally does nothing yet: backup generation and
                    // storage are defined in a follow-up step.
                },
            )
        },
    )
}
