package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton

/**
 * Step 1 of the in-app **Create MasterKey** flow.
 *
 * Sets expectations before key generation begins: the user must be ready to
 * record recovery material offline, and explicitly acknowledges that losing
 * it means permanent loss of access. The **Confirm** action stays disabled
 * until the consent checkbox is checked, matching the approved design, and
 * then advances to the passphrase step.
 *
 * The page frame (back link, hero illustration, title, description, footer)
 * comes from [CreateMasterKeyScaffold] so every wizard step stays visually
 * identical.
 */
@Composable
fun CreateMasterKeyIntroScreen(navController: NavHostController) {
    var consentChecked by remember { mutableStateOf(false) }

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_intro_title),
        heroIcon = R.drawable.hero_icon_getting_started,
        heroIconDescription = stringResource(R.string.create_master_key_intro_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(12.dp))

            // This step is the only one with two-paragraph copy.
            CreateMasterKeyDescription(stringResource(R.string.create_master_key_intro_body1))

            Spacer(Modifier.height(12.dp))

            CreateMasterKeyDescription(stringResource(R.string.create_master_key_intro_body2))
        },
        actions = {
            WarningRow()

            Spacer(Modifier.height(16.dp))

            ConsentRow(
                checked = consentChecked,
                onCheckedChange = { consentChecked = it },
            )

            Spacer(Modifier.height(16.dp))

            // Gated on consent: the user must acknowledge unrecoverable loss
            // before the wizard collects a passphrase and generates a key.
            VaultPillButton(
                label = stringResource(R.string.create_master_key_intro_confirm),
                enabled = consentChecked,
                onClick = { navController.navigate(VaultDestinations.CREATE_MASTER_KEY_PASSPHRASE) },
            )
        },
    )
}

/** Red warning icon + text calling out that backup material cannot be recovered if lost. */
@Composable
private fun WarningRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.create_master_key_intro_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Checkbox + consent copy that gates the Confirm action. */
@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                uncheckedColor = MaterialTheme.colorScheme.onSurface,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                checkedColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Text(
            text = stringResource(R.string.create_master_key_intro_consent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Aligns the first line of text with the checkbox's visual center.
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
        )
    }
}
