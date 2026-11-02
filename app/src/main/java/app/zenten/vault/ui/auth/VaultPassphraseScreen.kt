package app.zenten.vault.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * VaultKey passphrase gate shown after device authentication.
 *
 * The passphrase is never stored, so it must be re-entered each session and is used to decrypt
 * the VaultKey and run consolidation. The field owns its text so the secret never reaches the
 * caller's state; [onSubmit] receives it and the screen clears its own input after a successful
 * verify.
 */
@Composable
fun VaultPassphraseScreen(
    isVerifying: Boolean,
    hasError: Boolean,
    onPassphraseChanged: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Enter your VaultKey passphrase", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your passphrase is not stored on this device. Enter it to unlock the vault for this session.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                onPassphraseChanged()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("VaultKey Passphrase") },
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
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onSubmit(passphrase) },
            enabled = !isVerifying && passphrase.isNotBlank(),
        ) {
            Text(if (isVerifying) "Verifying…" else "Unlock")
        }
    }
}
