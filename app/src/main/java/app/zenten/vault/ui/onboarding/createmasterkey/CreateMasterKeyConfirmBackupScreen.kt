package app.zenten.vault.ui.onboarding.createmasterkey

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton

/** Step 5: paste and validate the exported MasterKey before continuing later. */
@Composable
fun CreateMasterKeyConfirmBackupScreen(
    navController: NavHostController,
    viewModel: CreateMasterKeyBackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var error by remember { mutableStateOf<Int?>(null) }

    ScreenshotProtection()

    // A process restart restores this screen directly, so Create Backup may not
    // exist in the new back stack. Pop when possible and rebuild that step when needed.
    val returnToCreateBackup = {
        if (!navController.popBackStack(VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO, false)) {
            navController.navigate(VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO) {
                popUpTo(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_BACKUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        Unit
    }
    BackHandler(onBack = returnToCreateBackup)

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_confirm_backup_title),
        heroIcon = R.drawable.hero_icon_confirm_backup,
        heroIconDescription = stringResource(R.string.create_master_key_confirm_backup_icon_description),
        onBack = returnToCreateBackup,
        content = {
            Spacer(Modifier.height(12.dp))
            CreateMasterKeyDescription(stringResource(R.string.create_master_key_confirm_backup_body))
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            VaultPillButton(
                label = stringResource(R.string.create_master_key_confirm_backup_action),
                onClick = {
                    error = null
                    val pastedKey = runCatching {
                        clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                    }.getOrElse {
                        error = R.string.create_master_key_confirm_backup_read_error
                        return@VaultPillButton
                    }
                    val clipboardCleared = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }.isSuccess
                    if (!clipboardCleared) {
                        error = R.string.create_master_key_confirm_backup_clear_error
                        return@VaultPillButton
                    }
                    if (pastedKey.isBlank()) {
                        error = R.string.create_master_key_confirm_backup_empty_error
                        return@VaultPillButton
                    }
                    error = when (viewModel.confirmPastedMasterKey(pastedKey)) {
                        BackupConfirmationResult.MATCHED -> null // Secret Combinations follows in a later change.
                        BackupConfirmationResult.INVALID -> R.string.create_master_key_confirm_backup_invalid_error
                        BackupConfirmationResult.MISMATCHED -> R.string.create_master_key_confirm_backup_mismatch_error
                        BackupConfirmationResult.UNAVAILABLE -> R.string.create_master_key_backup_unavailable
                    }
                },
            )
        },
    )
}
