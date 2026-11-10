package app.zenten.vault.ui.onboarding

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.zenten.vault.R
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseRequirement
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthDetailsSheet
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthMeter
import app.zenten.vault.ui.onboarding.createvaultkey.VaultKeyPassphrasePolicy

/**
 * Passphrase input for the imported and recovered VaultKey steps, with live strength feedback.
 *
 * The **Create VaultKey** wizard owns the elaborated version of this feedback; these two steps
 * reuse its requirement row, meter, and details sheet rather than restating the wording, so a
 * rating reads the same wherever it appears. The difference is what the feedback means: here a
 * weak passphrase blocks the submit (see [VaultKeyPassphrasePolicy]), whereas the wizard is
 * choosing a brand-new one.
 *
 * Both rules are shown, not just the meter. The refusal message points the user at "the
 * requirements above", so the length rule has to be visible for that sentence to refer to
 * something; the row also names the commonest failure (a short passphrase) without making the
 * user open the details sheet.
 *
 * The field itself is a plain obscured box with no visibility toggle, matching the previous
 * behaviour on both steps. The estimator's matched-text output stays inside
 * [PassphraseStrengthDetailsSheet], which the user has to open deliberately.
 *
 * @param measured latest debounced measurement; null hides the meter until one exists.
 * @param rejectedAsWeak shows the fixed "not strong enough to import" message under the field.
 *   The caller clears it when the field is edited; this composable never decides on its own.
 */
@Composable
internal fun OnboardingPassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    measured: MeasuredPassphrase?,
    rejectedAsWeak: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var detailsVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Resolved here because the default parameter cannot call a composable function.
    val fieldLabel = label ?: stringResource(R.string.ssh_setup_passphrase_placeholder)

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(fieldLabel) },
            visualTransformation = PasswordVisualTransformation(),
            // Password input semantics stop IMEs from autocorrecting punctuation in the secret.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = enabled,
            singleLine = true,
        )

        // Rendered as soon as anything is typed, so a passphrase that is too short is named
        // before the estimator has even run.
        if (value.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PassphraseRequirement(
                label = stringResource(
                    R.string.create_vault_key_passphrase_rule_min_length,
                    VaultKeyPassphrasePolicy.MINIMUM_LENGTH,
                ),
                satisfied = VaultKeyPassphrasePolicy.hasMinimumLength(value),
            )
        }

        measured?.let { result ->
            Spacer(Modifier.height(8.dp))
            PassphraseStrengthMeter(
                score = result.strength.score,
                onShowDetails = {
                    // Drop the keyboard first so the sheet is not squeezed behind it.
                    focusManager.clearFocus()
                    detailsVisible = true
                },
            )
        }

        if (rejectedAsWeak) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onboarding_passphrase_too_weak),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    val shown = measured
    if (detailsVisible && shown != null) {
        PassphraseStrengthDetailsSheet(
            strength = shown.strength,
            onDismiss = { detailsVisible = false },
        )
    }
}
