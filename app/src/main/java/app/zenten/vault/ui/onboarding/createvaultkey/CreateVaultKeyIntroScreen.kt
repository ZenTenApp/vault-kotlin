package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
 * Step 1 of the in-app **Create VaultKey** flow.
 *
 * Sets expectations before key generation begins: the user must be ready to
 * record recovery material offline, and explicitly acknowledges that losing
 * it means permanent loss of access. The **Confirm** action stays disabled
 * until the consent checkbox is checked, matching the approved design, and
 * then advances to the passphrase step.
 *
 * The page frame (back link, hero illustration, title, description, footer)
 * comes from [CreateVaultKeyScaffold] so every wizard step stays visually
 * identical.
 */
@Composable
fun CreateVaultKeyIntroScreen(navController: NavHostController) {
    var consentChecked by remember { mutableStateOf(false) }

    CreateVaultKeyScaffold(
        title = stringResource(R.string.create_vault_key_intro_title),
        heroIcon = R.drawable.hero_icon_getting_started,
        heroIconDescription = stringResource(R.string.create_vault_key_intro_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(12.dp))

            // This step is the only one with two-paragraph copy.
            CreateVaultKeyDescription(stringResource(R.string.create_vault_key_intro_body1))

            Spacer(Modifier.height(12.dp))

            CreateVaultKeyDescription(stringResource(R.string.create_vault_key_intro_body2))
        },
        actions = {
            Spacer(Modifier.height(16.dp))

            ConsentRow(
                checked = consentChecked,
                onCheckedChange = { consentChecked = it },
            )

            Spacer(Modifier.height(24.dp))

            // Gated on consent: the user must acknowledge unrecoverable loss
            // before the wizard collects a passphrase and generates a key.
            VaultPillButton(
                label = stringResource(R.string.create_vault_key_intro_confirm),
                enabled = consentChecked,
                onClick = { navController.navigate(VaultDestinations.CREATE_VAULT_KEY_PASSPHRASE) },
            )
        },
    )
}

/** Checkbox + consent copy that gates the Confirm action. */
@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
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
            text = stringResource(R.string.create_vault_key_intro_consent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Aligns the first line of text with the checkbox's visual center.
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),

        )
    }
}
