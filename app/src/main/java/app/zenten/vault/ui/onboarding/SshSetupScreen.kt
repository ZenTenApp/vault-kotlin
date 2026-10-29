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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.VaultDestinations
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
    // A string resource id, not text: the paste handler below is not a composable and
    // cannot call stringResource, so the error is resolved where it is rendered.
    var clipboardError by remember { mutableStateOf<Int?>(null) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    LaunchedEffect(state.completed) {
        if (state.completed) navController.navigate(VaultDestinations.ACCOUNTS) {
            popUpTo(VaultDestinations.GET_STARTED) { inclusive = true }
        }
    }

    // Returning to the key step discards the imported key and any typed passphrase, so
    // it is blocked while setup is running rather than silently dropping that work.
    val back: () -> Unit = {
        if (step == SetupStep.PASSPHRASE) {
            viewModel.clearError()
            keyInput = ""
            passphrase = ""
            step = SetupStep.PRIVATE_KEY
        } else {
            navController.popBackStack()
        }
    }

    VaultScreenScaffold(
        title = stringResource(R.string.ssh_setup_title),
        onBack = back,
        backEnabled = !state.inProgress,
        content = {
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
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.ssh_setup_passphrase_placeholder)) },
                        visualTransformation = PasswordVisualTransformation(),
                        // Password input semantics stop IMEs from autocorrecting punctuation in the secret.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            when (step) {
                SetupStep.PRIVATE_KEY -> {
                    clipboardError?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
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
                                clipboardError = R.string.ssh_setup_clipboard_read_error
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
                                clipboardError = R.string.ssh_setup_clipboard_clear_error
                                return@Button
                            }
                            if (pastedKey.isBlank()) {
                                clipboardError = R.string.ssh_setup_clipboard_empty_error
                                return@Button
                            }
                            val validatedKey = runCatching {
                                SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
                            }.getOrElse {
                                clipboardError = R.string.ssh_setup_clipboard_invalid_error
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
