package app.zenten.vault.ui.newaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.domain.AccountService
import app.zenten.vault.ui.VaultDestinations

@Composable
fun NewAccountScreen(navController: NavHostController, viewModel: NewAccountViewModel = hiltViewModel()) {
    var vaultKeyPassphrase by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(state.created) {
        state.created?.let { account ->
            navController.navigate(VaultDestinations.localAccountBackupExport(account.createdAt)) {
                popUpTo(VaultDestinations.NEW_ACCOUNT) { inclusive = true }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Create New Account", style = MaterialTheme.typography.headlineMedium)
        if (!state.passphraseVerified) {
            OutlinedTextField(
                value = vaultKeyPassphrase,
                onValueChange = { vaultKeyPassphrase = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("VaultKey Passphrase") },
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
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
                supportingText = { Text("8-128 characters") },
                isError = accountName.isNotEmpty() && accountName.trim().length !in
                    AccountService.MIN_ACCOUNT_NAME_LENGTH..AccountService.MAX_ACCOUNT_NAME_LENGTH,
                enabled = !state.creating,
                singleLine = true,
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                if (state.passphraseVerified) viewModel.create(vaultKeyPassphrase, accountName)
                else viewModel.verifyPassphrase(vaultKeyPassphrase)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = if (state.passphraseVerified) {
                accountName.trim().length in AccountService.MIN_ACCOUNT_NAME_LENGTH..AccountService.MAX_ACCOUNT_NAME_LENGTH &&
                    !state.creating
            } else {
                vaultKeyPassphrase.isNotBlank()
            },
        ) {
            if (state.creating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Creating Account", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(
                    if (state.passphraseVerified) "Create Account" else "Verify Passphrase",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.creating,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
        ) {
            Text("Cancel")
        }
    }
}
