package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.zenten.vault.R

/** Corner radius of the wizard's passphrase input, matching the approved design. */
private val PassphraseFieldCornerRadius = 12.dp

/** Height of the passphrase input so both wizard steps line up pixel-for-pixel. */
private val PassphraseFieldHeight = 72.dp

/**
 * Labeled, obscured passphrase input used by the **Create MasterKey** wizard.
 *
 * Renders the design's caption above a rounded outlined field with a trailing
 * eye toggle. Visibility state is owned here and defaults to hidden, so the
 * passphrase is masked whenever the screen is first shown or recomposed from
 * scratch; callers only deal with the text value.
 *
 * Autocorrect and suggestions are disabled through the password keyboard type
 * so the platform keyboard does not learn or predict secret material.
 *
 * @param label caption shown above the field (e.g. "Passphrase").
 * @param value current passphrase text.
 * @param onValueChange invoked with the updated text on every edit.
 * @param imeAction keyboard action key; the final wizard step uses "Done".
 */
@Composable
internal fun PassphraseField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    var visible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(PassphraseFieldHeight),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
            shape = RoundedCornerShape(PassphraseFieldCornerRadius),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (visible) {
                                R.string.create_master_key_passphrase_hide
                            } else {
                                R.string.create_master_key_passphrase_show
                            },
                        ),
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                cursorColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
    }
}
