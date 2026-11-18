package app.zenten.vault.ui.newkey

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.domain.KeyService
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold

@Composable
fun NewKeyScreen(navController: NavHostController, viewModel: NewKeyViewModel = hiltViewModel()) {
    var vaultKeyPassphrase by remember { mutableStateOf("") }
    var keyName by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    // Derived each recomposition from the typed name; the ViewModel repeats this check on create.
    val reservedTermUsed = KeyService.containsReservedKeyNameTerm(keyName)
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.created) {
        state.created?.let { key ->
            navController.navigate(VaultDestinations.localKeyBackupExport(key.createdAt)) {
                popUpTo(VaultDestinations.NEW_KEY) { inclusive = true }
            }
        }
    }

    VaultScreenScaffold(
        title = "Create New Key",
        onBack = { navController.popBackStack() },
        content = {
            Spacer(Modifier.height(28.dp))

            if (!state.passphraseVerified) {
                OutlinedTextField(
                    value = vaultKeyPassphrase,
                    onValueChange = { vaultKeyPassphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VaultKey Passphrase") },
                    // The passphrase check is a slow bcrypt derivation; freeze the field so the
                    // secret being checked cannot change while it runs.
                    enabled = !state.verifying,
                    visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    // Password input semantics stop IMEs from autocorrecting punctuation in the secret.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passphraseVisible = !passphraseVisible }, enabled = !state.verifying) {
                            Icon(
                                imageVector = if (passphraseVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passphraseVisible) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
            } else {
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Key Name") },
                    supportingText = {
                        // Show the reserved-term reason in place of the length hint so the user sees why Create is off.
                        if (reservedTermUsed) {
                            Text(
                                "Key name cannot contain \"${KeyService.RESERVED_KEY_NAME_TERM_DISPLAY}\"",
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text("3-128 characters")
                        }
                    },
                    isError = reservedTermUsed || (keyName.isNotEmpty() && keyName.trim().length !in
                        KeyService.MIN_KEY_NAME_LENGTH..KeyService.MAX_KEY_NAME_LENGTH),
                    enabled = !state.creating,
                    singleLine = true,
                )
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            VaultPillButton(
                label = when {
                    state.creating -> "Creating Key"
                    state.verifying -> "Verifying Passphrase"
                    state.passphraseVerified -> "Create Key"
                    else -> "Verify Passphrase"
                },
                icon = if (state.creating || state.verifying) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    null
                },
                // No action runs while a slow passphrase check or key creation is in flight.
                enabled = when {
                    state.creating || state.verifying -> false
                    state.passphraseVerified ->
                        keyName.trim().length in KeyService.MIN_KEY_NAME_LENGTH..KeyService.MAX_KEY_NAME_LENGTH &&
                            !reservedTermUsed
                    else -> vaultKeyPassphrase.isNotBlank()
                },
                onClick = {
                    if (state.passphraseVerified) viewModel.create(vaultKeyPassphrase, keyName)
                    else viewModel.verifyPassphrase(vaultKeyPassphrase)
                },
            )
        },
    )
}
