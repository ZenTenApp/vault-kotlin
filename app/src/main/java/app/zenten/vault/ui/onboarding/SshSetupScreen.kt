package app.zenten.vault.ui.onboarding

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.PasteError
import app.zenten.vault.ui.components.SensitiveClipboard
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold

/** Where the first step of the import flow obtains the VaultKey text. */
enum class KeySource {
    /** "Paste VaultKey": read (and clear) the clipboard. */
    CLIPBOARD,

    /** "Login with QR Code": scan the QR shown on another device's VaultKey detail screen. */
    QR_SCAN,
}

/**
 * Imports an existing VaultKey in two steps: paste (or scan) the encrypted private key from
 * the clipboard, then enter the passphrase that protects it.
 *
 * The frame comes from [VaultScreenScaffold] so the header matches every other
 * screen. That header's back arrow is the only Back affordance: there is no in-page
 * Cancel/Back button. Pressing it on the passphrase step returns to the key step, and
 * on the key step it leaves the flow — so the arrow always means "go back one step".
 */
@Composable
fun SshSetupScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
    keySource: KeySource = KeySource.CLIPBOARD,
) {
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
            // the SSH entry. Pop whichever onboarding entry exists so Back from Keys cannot
            // return to a completed setup screen.
            val hasGetStarted = runCatching {
                navController.getBackStackEntry(VaultDestinations.GET_STARTED)
            }.isSuccess
            navController.navigate(VaultDestinations.KEYS) {
                if (hasGetStarted) popUpTo(VaultDestinations.GET_STARTED) { inclusive = true }
                else popUpTo(
                    if (keySource == KeySource.QR_SCAN) VaultDestinations.GET_STARTED_QR
                    else VaultDestinations.GET_STARTED_SSH,
                ) { inclusive = true }
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
                        text = stringResource(
                            if (keySource == KeySource.QR_SCAN) R.string.qr_scan_body else R.string.ssh_setup_paste_body,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (keySource == KeySource.QR_SCAN) {
                        Spacer(Modifier.height(16.dp))
                        QrScanner(
                            enabled = !state.inProgress,
                            onScanned = { scanned ->
                                // Same checks as a paste; an invalid code just shows the error
                                // while the camera keeps looking for the right one.
                                when (val result = ImportedKeyValidator.validate(scanned)) {
                                    is ImportedKeyValidation.Invalid -> clipboardError = result.error
                                    is ImportedKeyValidation.Valid -> {
                                        clipboardError = null
                                        keyInput = result.normalizedKey
                                        step = SetupStep.PASSPHRASE
                                    }
                                }
                            },
                        )
                    }
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
                VaultPillButton(
                    label = stringResource(R.string.ssh_setup_retry_action),
                    onClick = viewModel::retryStoredVaultKey,
                    enabled = !state.inProgress,
                    icon = if (state.inProgress) ({ ButtonSpinner() }) else null,
                )
                return@VaultScreenScaffold
            }
            when (step) {
                SetupStep.PRIVATE_KEY -> {
                    clipboardError?.let {
                        Text(it.render(), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    // The QR flow has no button here: the live scanner above is the action.
                    if (keySource == KeySource.CLIPBOARD) VaultPillButton(
                        label = stringResource(R.string.ssh_setup_paste_action),
                        enabled = !state.inProgress,
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
                                return@VaultPillButton
                            }

                            val clipboardCleared = runCatching {
                                SensitiveClipboard.clear(clipboard)
                            }.isSuccess
                            if (!clipboardCleared) {
                                clipboardError = PasteError(R.string.ssh_setup_clipboard_clear_error)
                                return@VaultPillButton
                            }
                            when (val result = ImportedKeyValidator.validate(pastedKey)) {
                                is ImportedKeyValidation.Invalid -> {
                                    clipboardError = result.error
                                    return@VaultPillButton
                                }
                                is ImportedKeyValidation.Valid -> {
                                    keyInput = result.normalizedKey
                                    step = SetupStep.PASSPHRASE
                                }
                            }
                        },
                    )
                }

                SetupStep.PASSPHRASE -> VaultPillButton(
                    label = stringResource(R.string.ssh_setup_create_action),
                    onClick = { viewModel.performSetup(keyInput, passphrase) },
                    enabled = passphrase.isNotBlank() && !state.inProgress,
                    icon = if (state.inProgress) ({ ButtonSpinner() }) else null,
                )
            }

            if (state.canRetryStoredVaultKey) {
                Spacer(Modifier.height(8.dp))
                VaultPillButton(
                    label = stringResource(R.string.ssh_setup_retry_action),
                    onClick = viewModel::retryStoredVaultKey,
                    enabled = !state.inProgress,
                )
            }
        },
    )
}

/**
 * Small spinner shown beside a pill button's label while setup runs. The default
 * indicator is 40dp, which would overflow the button, so it is sized to the label.
 */
@Composable
private fun ButtonSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
