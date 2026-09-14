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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.VaultDestinations

@Composable
fun SshSetupScreen(navController: NavHostController, viewModel: OnboardingViewModel = hiltViewModel()) {
    var keyInput by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(SetupStep.PRIVATE_KEY) }
    var clipboardError by remember { mutableStateOf<String?>(null) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val view = LocalView.current
    // Prevent screenshots while the app holds a private key or passphrase.
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) navController.navigate(VaultDestinations.ACCOUNTS) {
            popUpTo(VaultDestinations.GET_STARTED) { inclusive = true }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Setup Master Account",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        SetupProgress(step)

        when (step) {
            SetupStep.PRIVATE_KEY -> {
                Text(
                    text = "Copy your encrypted Ed25519 OpenSSH private key, then paste it from your clipboard.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
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
                            clipboardError = "Vault could not read the clipboard. Copy the private key and try again."
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
                            clipboardError = "Vault could not clear the clipboard. Clear it manually, then try again."
                            return@Button
                        }
                        if (pastedKey.isBlank()) {
                            clipboardError = "The clipboard does not contain a private key. Copy the key and try again."
                            return@Button
                        }
                        val validatedKey = runCatching {
                            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
                        }.getOrElse {
                            clipboardError = "The clipboard does not contain a supported encrypted Ed25519 OpenSSH private key."
                            return@Button
                        }

                        keyInput = validatedKey
                        step = SetupStep.PASSPHRASE
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.inProgress,
                ) {
                    Text("Paste MasterKey")
                }
                clipboardError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            SetupStep.PASSPHRASE -> {
                Text("Enter the passphrase that protects your SSH private key.")
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SSH key passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !state.inProgress,
                )
            }
        }
        state.stage?.let { Text(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.canRetryStoredMaster) {
            Button(onClick = viewModel::retryStoredMaster, modifier = Modifier.fillMaxWidth(), enabled = !state.inProgress) {
                Text("Retry backup without re-importing")
            }
        }
        if (step == SetupStep.PASSPHRASE) {
            Button(
                onClick = { viewModel.performSetup(keyInput, passphrase) },
                modifier = Modifier.fillMaxWidth(),
                enabled = passphrase.isNotBlank() && !state.inProgress,
            ) {
                if (state.inProgress) CircularProgressIndicator() else Text("Create and back up master account")
            }
        }
        OutlinedButton(
            onClick = {
                if (step == SetupStep.PASSPHRASE) {
                    viewModel.clearError()
                    keyInput = ""
                    passphrase = ""
                    step = SetupStep.PRIVATE_KEY
                } else {
                    navController.popBackStack()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.inProgress,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
        ) { Text(if (step == SetupStep.PRIVATE_KEY) "Cancel" else "Back") }
    }
}

@Composable
private fun SetupProgress(step: SetupStep) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (step == SetupStep.PRIVATE_KEY) "STEP 1 OF 2" else "STEP 2 OF 2",
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
