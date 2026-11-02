package app.zenten.vault.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold

/**
 * VaultKey passphrase gate shown after device authentication.
 *
 * The passphrase is never stored, so it must be re-entered each session and is used to decrypt
 * the VaultKey and run consolidation. The field owns its text so the secret never reaches the
 * caller's state; [onSubmit] receives it and the screen clears its own input after a successful
 * verify.
 *
 * The layout follows the shared page frame ([VaultScreenScaffold]): the title and input sit at
 * the top of the screen, and the Unlock action is pinned to the bottom so it stays reachable
 * one-handed and matches the action buttons used elsewhere in the app.
 */
@Composable
fun VaultPassphraseScreen(
    isVerifying: Boolean,
    hasError: Boolean,
    onPassphraseChanged: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    VaultScreenScaffold(
        title = "VaultKey passphrase",
        onBack = null,
        content = {
            Spacer(Modifier.height(16.dp))
            Text(
                "Your passphrase is not stored on this device. Enter it to unlock the vault for this session.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    onPassphraseChanged()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enter VaultKey Passphrase") },
                singleLine = true,
                enabled = !isVerifying,
                isError = hasError,
                visualTransformation = PasswordVisualTransformation(),
                // Password keyboard type disables IME autocorrect and suggestions for the secret.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (hasError) {
                Spacer(Modifier.height(8.dp))
                Text("That passphrase does not match this VaultKey.", color = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            VaultPillButton(
                label = if (isVerifying) "Verifying…" else "Unlock",
                onClick = { onSubmit(passphrase) },
                enabled = !isVerifying && passphrase.isNotBlank(),
            )
        },
    )
}
