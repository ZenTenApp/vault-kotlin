package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultPillButton

/** Step 6: let the user transcribe the Brave recovery words as BIP-39 positions. */
@Composable
fun CreateMasterKeySecretCombinationsScreen(
    viewModel: CreateMasterKeyBackupViewModel = hiltViewModel(),
) {
    val indices = remember { viewModel.secretCombinationIndices() }
    var codesVisible by remember { mutableStateOf(true) }
    var backedUp by remember { mutableStateOf(false) }

    ScreenshotProtection()
    // Reassert the durable point from the destination itself so an immediate process stop
    // cannot send a verified user back to the clipboard screen.
    LaunchedEffect(Unit) { viewModel.recordSecretCombinationsShown() }
    // The generated identity is already committed, and returning would expose a verified
    // clipboard step again. The durable progress marker reopens this screen after restart.
    BackHandler(enabled = true) {}

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(44.dp))
            Text(
                text = stringResource(R.string.create_master_key_secret_combinations_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            CreateMasterKeyDescription(
                text = stringResource(R.string.create_master_key_secret_combinations_body),
            )
            Spacer(Modifier.height(20.dp))
//            CreateMasterKeyWarningRow(centered = true)
//            Spacer(Modifier.height(24.dp))

            if (indices == null || indices.size != 24) {
                Text(
                    text = stringResource(R.string.create_master_key_secret_combinations_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            } else {
                indices.chunked(3).forEachIndexed { rowIndex, group ->
                    SecretCombinationRow(
                        label = ('A'.code + rowIndex).toChar(),
                        indices = group,
                        visible = codesVisible,
                    )
                    if (rowIndex != 7) Spacer(Modifier.height(12.dp))
                }
                TextButton(onClick = { codesVisible = !codesVisible }) {
                    Icon(
                        imageVector = if (codesVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(
                            if (codesVisible) R.string.create_master_key_secret_combinations_hide
                            else R.string.create_master_key_secret_combinations_show,
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = backedUp,
                    onCheckedChange = { backedUp = it },
                    enabled = indices != null,
                )
                Text(
                    text = stringResource(R.string.create_master_key_secret_combinations_consent),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            VaultPillButton(
                label = stringResource(R.string.create_master_key_secret_combinations_confirm),
                onClick = { /* The following onboarding step is not part of this screen. */ },
                enabled = backedUp && indices != null,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.get_started_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SecretCombinationRow(label: Char, indices: List<Int>, visible: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = if (visible) indices.joinToString("-") else "••••-••••-••••",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(vertical = 18.dp, horizontal = 8.dp),
        )
    }
}
