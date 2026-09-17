package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.theme.VaultColors

/** Diameter of the circular illustration placeholder above the title. */
private val IllustrationBadgeSize = 132.dp

/**
 * First screen of the in-app **Create MasterKey** flow.
 *
 * Sets expectations before key generation begins: the user must be ready to
 * record recovery material offline, and explicitly acknowledges that losing
 * it means permanent loss of access. The **Confirm** action stays disabled
 * until the consent checkbox is checked, matching the approved design.
 *
 * The illustration above the title is a placeholder today; the pen-and-paper
 * artwork will be dropped in once the asset is ready.
 */
@Composable
fun CreateMasterKeyIntroScreen(navController: NavHostController) {
    var consentChecked by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Scrolls on short screens so the footer never clips the button.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            BackLink(onClick = navController::popBackStack)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))

                IllustrationPlaceholder()

                Spacer(Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.create_master_key_intro_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.create_master_key_intro_body1),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.create_master_key_intro_body2),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            WarningRow()

            Spacer(Modifier.height(16.dp))

            ConsentRow(
                checked = consentChecked,
                onCheckedChange = { consentChecked = it },
            )

            Spacer(Modifier.height(16.dp))

            // Gated on consent: the user must acknowledge unrecoverable loss
            // before key generation can proceed.
            VaultPillButton(
                label = stringResource(R.string.create_master_key_intro_confirm),
                enabled = consentChecked,
                onClick = {
                    // Next screen in the wizard is not implemented yet.
                },
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.get_started_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Top-left "< Back" navigation link, styled after the approved design's link color. */
@Composable
private fun BackLink(onClick: () -> Unit) {
    Row(modifier = Modifier.padding(top = 8.dp)) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = VaultColors.Link,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.create_master_key_back),
                color = VaultColors.Link,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Circular placeholder standing in for the pen-and-paper illustration.
 *
 * The design calls for artwork here reminding the user to prepare a pen and
 * paper; until that asset is ready this renders as an empty tinted circle
 * matching its footprint so layout does not shift when it lands.
 */
@Composable
private fun IllustrationPlaceholder() {
    Surface(
        modifier = Modifier.size(IllustrationBadgeSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        content = {},
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
