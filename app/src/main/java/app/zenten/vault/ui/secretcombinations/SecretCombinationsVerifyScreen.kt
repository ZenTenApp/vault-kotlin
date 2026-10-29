package app.zenten.vault.ui.secretcombinations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenHeader

/**
 * Entry gate for the **Secret Combinations** review flow.
 *
 * The passphrase is checked against the stored VaultKey before any combination is displayed.
 * This step is still abortable: the flow becomes irreversible only after [SecretCombinationsViewModel.submitPassphrase]
 * succeeds and durably records the display step.
 */
@Composable
fun SecretCombinationsVerifyScreen(
    navController: NavHostController,
    viewModel: SecretCombinationsViewModel = rememberSecretCombinationsViewModel(navController),
) {
    val state by viewModel.state.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }

    // Advance only after the ViewModel has persisted the SHOW step, so a process stop here
    // resumes the flow rather than dropping the user back to Settings.
    LaunchedEffect(state.passphraseVerified) {
        if (state.passphraseVerified) {
            navController.navigate(VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW) {
                popUpTo(VaultDestinations.VIEW_SECRET_COMBINATIONS_VERIFY) { inclusive = true }
            }
        }
    }
    // Once the passphrase matched, the durable flow marker is already written, so block Back
    // for the instant before the SHOW destination takes over.
    BackHandler(enabled = state.verifying || state.passphraseVerified) {}

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VaultScreenHeader(
                title = stringResource(R.string.secret_combinations_verify_title),
                onBack = { navController.navigateUp() },
                backEnabled = !state.verifying && !state.passphraseVerified,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.secret_combinations_verify_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    viewModel.clearPassphraseError()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.secret_combinations_verify_label)) },
                singleLine = true,
                enabled = !state.verifying,
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                // Password input semantics stop IMEs from autocorrecting punctuation in the secret.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(
                            imageVector = if (passphraseVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passphraseVisible) {
                                stringResource(R.string.secret_combinations_verify_hide)
                            } else {
                                stringResource(R.string.secret_combinations_verify_show)
                            },
                        )
                    }
                },
            )
            if (state.passphraseError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.secret_combinations_verify_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
            VaultPillButton(
                label = stringResource(R.string.secret_combinations_verify_action),
                onClick = { viewModel.submitPassphrase(passphrase) },
                enabled = passphrase.isNotBlank() && !state.verifying,
                icon = if (state.verifying) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

