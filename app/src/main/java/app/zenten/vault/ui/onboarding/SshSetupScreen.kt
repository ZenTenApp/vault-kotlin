package app.zenten.vault.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

@Composable
fun SshSetupScreen(navController: NavHostController, viewModel: OnboardingViewModel = hiltViewModel()) {
    var keyInput by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(SetupStep.PRIVATE_KEY) }
    val state by viewModel.state.collectAsState()
    val view = LocalView.current
    // Prevent screenshots while users paste a private key or passphrase.
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
    ) {
        when (step) {
            SetupStep.PRIVATE_KEY -> {
                Text("Set up your master account", style = MaterialTheme.typography.headlineMedium)
                Text("Step 1 of 2: Paste your OpenSSH Ed25519 private key.")
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    label = { Text("SSH private key") },
                    minLines = 6,
                    enabled = !state.inProgress,
                )
                Button(
                    onClick = { step = SetupStep.PASSPHRASE },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = keyInput.isNotBlank() && !state.inProgress,
                ) {
                    Text("Continue")
                }
            }

            SetupStep.PASSPHRASE -> {
                Text("Enter your passphrase", style = MaterialTheme.typography.headlineMedium)
                Text("Step 2 of 2: Enter the passphrase that protects your SSH private key.")
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
        Button(
            onClick = {
                if (step == SetupStep.PASSPHRASE) {
                    viewModel.clearError()
                    step = SetupStep.PRIVATE_KEY
                } else {
                    navController.popBackStack()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.inProgress,
        ) { Text("Back") }
    }
}

private enum class SetupStep {
    PRIVATE_KEY,
    PASSPHRASE,
}
