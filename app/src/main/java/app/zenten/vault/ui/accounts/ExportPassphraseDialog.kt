package app.zenten.vault.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Confirmation dialog shown immediately before an account's encrypted private key is handed
 * to another app for export.
 *
 * Exporting the key is the one place Vault deliberately discloses secret material outside the
 * vault, so the user must re-enter the passphrase that protects that specific account. This
 * proves intent and that the user still knows the passphrase, rather than relying only on the
 * device being unlocked.
 *
 * The dialog owns the entered text and visibility toggle so the secret never reaches the
 * caller's state: [onConfirmed] receives the candidate, returns whether it matched, and the
 * dialog either dismisses itself (match) or shows an inline error and stays open (no match).
 *
 * @param message explanation shown above the passphrase field.
 * @param mismatchError shown when [onConfirmed] rejects the entered passphrase; callers
 *   customize it so the message names the record the user is exporting.
 * @param onDismiss invoked for Cancel, scrim taps, and a successful confirmation.
 * @param onConfirmed verifies the entered passphrase and returns `true` only on a match.
 */
@Composable
internal fun ExportPassphraseDialog(
    message: String,
    onDismiss: () -> Unit,
    onConfirmed: (String) -> Boolean,
    mismatchError: String = "That passphrase does not match this account.",
) {
    var passphrase by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        // Editing means the user is reacting to the error, so drop it.
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VaultKey Passphrase") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    // Password keyboard type disables IME autocorrect and suggestions for the secret.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onConfirmed(passphrase)) {
                        onDismiss()
                    } else {
                        error = mismatchError
                    }
                },
                enabled = passphrase.isNotBlank(),
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
