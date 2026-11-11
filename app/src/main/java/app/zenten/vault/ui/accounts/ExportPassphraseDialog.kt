package app.zenten.vault.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
 * Verification is a bcrypt derivation that can take seconds, so [onConfirmed] is a suspending
 * callback: it is run from the dialog's own coroutine scope, and while it is in flight the
 * dialog shows a busy state and disables Confirm, Cancel, and text editing. Dismissal requests
 * are ignored during that window so the dialog cannot be torn down mid-derivation.
 * The passphrase lives only in this composable's remembered state and is dropped on dismiss.
 *
 * @param message explanation shown above the passphrase field.
 * @param mismatchError shown when [onConfirmed] rejects the entered passphrase, or throws;
 *   callers customize it so the message names the record the user is exporting.
 * @param onDismiss invoked for Cancel, scrim taps, and a successful confirmation. Not invoked
 *   while a verification is still running.
 * @param onConfirmed suspends while it verifies the entered passphrase and returns `true` only
 *   on a match. Callers do their expensive work here and must dispatch it off the main thread.
 */
@Composable
internal fun ExportPassphraseDialog(
    message: String,
    onDismiss: () -> Unit,
    onConfirmed: suspend (String) -> Boolean,
    mismatchError: String = "That passphrase does not match this account.",
) {
    var passphrase by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // True while the suspended passphrase check is running; gates every interactive control.
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
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
                    // Freeze the field so the secret being verified cannot change mid-check.
                    enabled = !verifying,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    // Password keyboard type disables IME autocorrect and suggestions for the secret.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }, enabled = !verifying) {
                            Icon(
                                imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
                if (verifying) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Verifying…")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The button is disabled while busy, so this also guards programmatic taps.
                    if (!verifying) {
                        verifying = true
                        scope.launch {
                            // A thrown verification is a failed match, not a stuck busy dialog.
                            val matched = runCatching { onConfirmed(passphrase) }.getOrDefault(false)
                            verifying = false
                            if (matched) {
                                onDismiss()
                            } else {
                                error = mismatchError
                            }
                        }
                    }
                },
                enabled = !verifying && passphrase.isNotBlank(),
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) { Text("Cancel") }
        },
    )
}
