package app.zenten.vault.ui.onboarding.createvaultkey

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import kotlinx.coroutines.delay

/** Step 5: paste and validate the exported VaultKey before continuing later. */
@Composable
fun CreateVaultKeyConfirmBackupScreen(
    navController: NavHostController,
    viewModel: CreateVaultKeyViewModel = rememberCreateVaultKeyViewModel(navController),
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var error by remember { mutableStateOf<Int?>(null) }
    val pasteGuard by viewModel.pasteGuard.collectAsState()

    // Drives the visible countdown. The remaining time is recomputed from the
    // stored deadline on every frame tick so it stays accurate after a pause,
    // and the block is only lifted once the deadline has actually passed.
    var remainingLockoutMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pasteGuard.blockedUntilMillis) {
        val until = pasteGuard.blockedUntilMillis
        if (until == null) {
            remainingLockoutMillis = 0L
            return@LaunchedEffect
        }
        while (true) {
            val remaining = until - System.currentTimeMillis()
            remainingLockoutMillis = remaining.coerceAtLeast(0L)
            if (remaining <= 0L) {
                viewModel.onPasteLockoutExpired()
                // The lockout screen was the last thing shown; returning to a clean
                // Confirm Backup step must not leave the previous error behind.
                error = null
                break
            }
            delay(250L)
        }
    }
    val isLocked = remainingLockoutMillis > 0L

    // A process restart restores this screen directly, so Create Backup may not
    // exist in the new back stack. Pop when possible and rebuild that step when needed.
    // Leaving to export also clears the paste guard: re-exporting starts a new cycle.
    val returnToCreateBackup: () -> Unit = {
        viewModel.resetVaultKeyPasteGuard()
        if (!navController.popBackStack(VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO, false)) {
            navController.navigate(VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO) {
                popUpTo(VaultDestinations.CREATE_VAULT_KEY_CONFIRM_BACKUP) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    // System Back is inert while the escalating paste lockout is active so the
    // wait cannot be skipped by leaving the screen.
    BackHandler {
        if (!isLocked) returnToCreateBackup()
    }

    Box(Modifier.fillMaxSize()) {
        CreateVaultKeyScaffold(
            title = stringResource(R.string.create_vault_key_confirm_backup_title),
            heroIcon = R.drawable.hero_icon_confirm_backup,
            heroIconDescription = stringResource(R.string.create_vault_key_confirm_backup_icon_description),
            onBack = if (isLocked) null else returnToCreateBackup,
            content = {
                Spacer(Modifier.height(12.dp))
                CreateVaultKeyDescription(stringResource(R.string.create_vault_key_confirm_backup_body))
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
                    label = stringResource(R.string.create_vault_key_confirm_backup_action),
                    enabled = !isLocked,
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
                            error = R.string.create_vault_key_confirm_backup_read_error
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
                            error = R.string.create_vault_key_confirm_backup_clear_error
                            return@VaultPillButton
                        }
                        if (pastedKey.isBlank()) {
                            error = R.string.create_vault_key_confirm_backup_empty_error
                            return@VaultPillButton
                        }
                        error = when (val result = viewModel.confirmPastedVaultKey(pastedKey)) {
                            BackupConfirmationResult.MATCHED -> {
                                // Usually already available from generation; after process restoration
                                // this starts the one-time background KDF fallback.
                                viewModel.loadSecretCombinationIndices()
                                viewModel.recordSecretCombinationsShown()
                                navController.navigate(VaultDestinations.CREATE_VAULT_KEY_SECRET_COMBINATIONS) {
                                    launchSingleTop = true
                                }
                                null
                            }
                            // Both an unreadable container and a well-formed key that does not
                            // match count as a wrong paste and feed the escalating lockout.
                            BackupConfirmationResult.INVALID,
                            BackupConfirmationResult.MISMATCHED -> {
                                when (viewModel.registerFailedVaultKeyPaste()) {
                                    VaultKeyPasteFailure.ReturnToExport -> {
                                        returnToCreateBackup()
                                        null
                                    }
                                    // The full-screen block below renders the countdown.
                                    is VaultKeyPasteFailure.Blocked -> when (result) {
                                        BackupConfirmationResult.INVALID ->
                                            R.string.create_vault_key_confirm_backup_invalid_error
                                        else -> R.string.create_vault_key_confirm_backup_mismatch_error
                                    }
                                }
                            }
                            BackupConfirmationResult.UNAVAILABLE -> R.string.create_vault_key_backup_unavailable
                        }
                    },
                )
            },
        )

        if (isLocked) {
            PasteLockoutOverlay(
                message = error?.let { stringResource(it) },
                remainingMillis = remainingLockoutMillis,
            )
        }
    }
}

/**
 * Full-screen block shown while the Confirm Backup lockout is active.
 *
 * Covers the wizard so the paste action and Back cannot be used during the
 * wait, and repeats the last error above a live countdown.
 */
@Composable
private fun PasteLockoutOverlay(
    message: String?,
    remainingMillis: Long,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.create_vault_key_confirm_backup_locked_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = stringResource(
                    R.string.create_vault_key_confirm_backup_locked_body,
                    formatLockoutCountdown(remainingMillis),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Formats the remaining wait as `m:ss`, rounding up so the display only shows
 * `0:00` once the deadline has truly passed.
 */
private fun formatLockoutCountdown(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis + 999L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
