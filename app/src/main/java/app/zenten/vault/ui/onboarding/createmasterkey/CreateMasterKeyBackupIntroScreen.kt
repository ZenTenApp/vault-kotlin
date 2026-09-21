package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
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
 * At this point the MasterKey has been saved in encrypted local storage and a
 * backup has been attempted on every enabled source. This screen sets expectations — the user has
 * to store the backup themselves, somewhere they control — and repeats the
 * unrecoverable-loss warning shared with the wizard's intro step.
 *
 * **Confirm** is intentionally inert for now because the next user-facing
 * recovery-material step has not been implemented. The durable progress marker
 * returns the user here after app or process restart.
 */
@Composable
fun CreateMasterKeyBackupIntroScreen(navController: NavHostController) {
    // Generation is irreversible; both system and toolbar back navigation are disabled.
    BackHandler(enabled = true) {}

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_backup_intro_title),
        heroIcon = R.drawable.hero_icon_create_backup,
        heroIconDescription = stringResource(R.string.create_master_key_backup_intro_icon_description),
        // The key is already committed; returning to generation could create a second identity.
        onBack = null,
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
                    // Intentionally does nothing until the next setup step is implemented.
                },
            )
        },
    )
}
