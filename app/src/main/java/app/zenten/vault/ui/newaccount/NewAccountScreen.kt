package app.zenten.vault.ui.newaccount

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
import app.zenten.vault.domain.AccountService
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold

@Composable
fun NewAccountScreen(navController: NavHostController, viewModel: NewAccountViewModel = hiltViewModel()) {
    var vaultKeyPassphrase by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.created) {
        state.created?.let { account ->
            navController.navigate(VaultDestinations.localAccountBackupExport(account.createdAt)) {
                popUpTo(VaultDestinations.NEW_ACCOUNT) { inclusive = true }
            }
        }
    }

    VaultScreenScaffold(
        title = "Create New Account",
        onBack = { navController.popBackStack() },
        content = {
            Spacer(Modifier.height(28.dp))

            if (!state.passphraseVerified) {
                OutlinedTextField(
                    value = vaultKeyPassphrase,
                    onValueChange = { vaultKeyPassphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VaultKey Passphrase") },
                    visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    // Password input semantics stop IMEs from autocorrecting punctuation in the secret.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                            Icon(
                                imageVector = if (passphraseVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passphraseVisible) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
            } else {
                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Account Name") },
                    supportingText = { Text("3-128 characters") },
                    isError = accountName.isNotEmpty() && accountName.trim().length !in
                        AccountService.MIN_ACCOUNT_NAME_LENGTH..AccountService.MAX_ACCOUNT_NAME_LENGTH,
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
                label = if (state.creating) "Creating Account" else if (state.passphraseVerified) "Create Account" else "Verify Passphrase",
                icon = if (state.creating) {
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
                enabled = if (state.passphraseVerified) {
                    accountName.trim().length in AccountService.MIN_ACCOUNT_NAME_LENGTH..AccountService.MAX_ACCOUNT_NAME_LENGTH &&
                        !state.creating
                } else {
                    vaultKeyPassphrase.isNotBlank()
                },
                onClick = {
                    if (state.passphraseVerified) viewModel.create(vaultKeyPassphrase, accountName)
                    else viewModel.verifyPassphrase(vaultKeyPassphrase)
                },
            )
        },
    )
}
