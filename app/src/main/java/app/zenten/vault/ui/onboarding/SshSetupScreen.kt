package app.zenten.vault.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.PasteError
import app.zenten.vault.ui.components.VaultScreenScaffold

/**
 * Imports an existing VaultKey in two steps: paste the encrypted private key from
 * the clipboard, then enter the passphrase that protects it.
 *
 * The frame comes from [VaultScreenScaffold] so the header matches every other
 * screen. That header's back arrow is the only Back affordance: there is no in-page
 * Cancel/Back button. Pressing it on the passphrase step returns to the key step, and
 * on the key step it leaves the flow — so the arrow always means "go back one step".
 */
@Composable
fun SshSetupScreen(navController: NavHostController, viewModel: OnboardingViewModel = hiltViewModel()) {
    var keyInput by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(SetupStep.PRIVATE_KEY) }
    // A string resource id (plus an optional format argument), not text: the paste handler below
    // is not a composable and cannot call stringResource, so the error is resolved where it is
    // rendered. The bcrypt round count is public container metadata, never key material.
    var clipboardError by remember { mutableStateOf<PasteError?>(null) }
    val state by viewModel.state.collectAsState()
    val passphraseFeedback by viewModel.passphraseFeedback.collectAsState()
    // Once the key is committed only the backup is missing, so the two paste/derive steps are
    // not meaningful: show the retry (in this process or a resumed one) instead.
    val resumingPendingBackup = state.canRetryStoredVaultKey && !state.completed
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    LaunchedEffect(state.completed) {
        if (state.completed) {
            // A fresh import has Get started beneath this screen; a launch-resumed retry has only
            // the SSH entry. Pop whichever onboarding entry exists so Back from Accounts cannot
            // return to a completed setup screen.
            val hasGetStarted = runCatching {
                navController.getBackStackEntry(VaultDestinations.GET_STARTED)
            }.isSuccess
            navController.navigate(VaultDestinations.ACCOUNTS) {
                if (hasGetStarted) popUpTo(VaultDestinations.GET_STARTED) { inclusive = true }
                else popUpTo(VaultDestinations.GET_STARTED_SSH) { inclusive = true }
            }
        }
    }

    // The measurement belongs to this step only; dropping it keeps a stale rating from being
    // shown for a passphrase that is no longer in the field.
    DisposableEffect(step) {
        onDispose { if (step == SetupStep.PASSPHRASE) viewModel.clearPassphraseFeedback() }
    }

    // Returning to the key step discards the imported key and any typed passphrase, so
    // it is blocked while setup is running rather than silently dropping that work.
    val back: () -> Unit = {
        if (step == SetupStep.PASSPHRASE) {
            viewModel.clearError()
            viewModel.clearPassphraseFeedback()
            keyInput = ""
            passphrase = ""
            step = SetupStep.PRIVATE_KEY
        } else {
            navController.popBackStack()
        }
    }

    VaultScreenScaffold(
        title = stringResource(R.string.ssh_setup_title),
        // The resumed state has no earlier onboarding step to return to and setup must not be
        // abandoned silently, so its header has no Back arrow.
        onBack = if (resumingPendingBackup) null else back,
        backEnabled = !state.inProgress,
        content = {
            if (resumingPendingBackup) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ssh_setup_pending_backup_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                return@VaultScreenScaffold
            }
            Spacer(Modifier.height(16.dp))
            SetupProgress(step)

            when (step) {
                SetupStep.PRIVATE_KEY -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.ssh_setup_paste_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupStep.PASSPHRASE -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.ssh_setup_passphrase_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    OnboardingPassphraseField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            viewModel.onPassphraseChanged(it)
                        },
                        measured = passphraseFeedback.measured,
                        rejectedAsWeak = passphraseFeedback.rejectedAsWeak,
                        enabled = !state.inProgress,
                    )
                }
            }

            state.stage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it)
            }
            state.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            // In the resumed state the stored key is already committed, so pasting/deriving it
            // again is not offered: only the retry below applies.
            if (resumingPendingBackup) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::retryStoredVaultKey,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.inProgress,
                ) {
                    if (state.inProgress) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.ssh_setup_retry_action))
                    }
                }
                return@VaultScreenScaffold
            }
            when (step) {
                SetupStep.PRIVATE_KEY -> {
                    clipboardError?.let {
                        Text(it.render(), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            clipboardError = null
                            val pastedKey = runCatching {
                                clipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                            }.getOrElse {
                                clipboardError = PasteError(R.string.ssh_setup_clipboard_read_error)
                                return@Button
                            }

                            val clipboardCleared = runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    clipboard.clearPrimaryClip()
                                } else {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                                }
                            }.isSuccess
                            if (!clipboardCleared) {
                                clipboardError = PasteError(R.string.ssh_setup_clipboard_clear_error)
                                return@Button
                            }
                            if (pastedKey.isBlank()) {
                                clipboardError = PasteError(R.string.ssh_setup_clipboard_empty_error)
                                return@Button
                            }
                            val validatedKey = runCatching {
                                SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
                            }.getOrElse { error ->
                                // A well-formed key with an out-of-band work factor is explained
                                // on its own terms: the generic "unsupported key" wording would
                                // suggest the paste was malformed, when the real problem is the
                                // key's declared KDF cost.
                                clipboardError = when (error) {
                                    is SshKeyCodec.UnsupportedKdfRoundsException ->
                                        PasteError(R.string.ssh_setup_clipboard_kdf_rounds_error, error.declaredRounds)
                                    else -> PasteError(R.string.ssh_setup_clipboard_invalid_error)
                                }
                                return@Button
                            }

                            keyInput = validatedKey
                            step = SetupStep.PASSPHRASE
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.inProgress,
                    ) {
                        Text(stringResource(R.string.ssh_setup_paste_action))
                    }
                }

                SetupStep.PASSPHRASE -> Button(
                    onClick = { viewModel.performSetup(keyInput, passphrase) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = passphrase.isNotBlank() && !state.inProgress,
                ) {
                    if (state.inProgress) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.ssh_setup_create_action))
                    }
                }
            }

            if (state.canRetryStoredVaultKey) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::retryStoredVaultKey,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.inProgress,
                ) {
                    Text(stringResource(R.string.ssh_setup_retry_action))
                }
            }
        },
    )
}

/** Number of steps in the import flow, used by the progress label. */
private const val SetupStepCount = 2

@Composable
private fun SetupProgress(step: SetupStep) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            // Enum order defines the step number, so the label cannot drift from SetupStep.
            text = stringResource(R.string.ssh_setup_step_progress, step.ordinal + 1, SetupStepCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        if (step == SetupStep.PASSPHRASE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

private enum class SetupStep {
    PRIVATE_KEY,
    PASSPHRASE,
}
