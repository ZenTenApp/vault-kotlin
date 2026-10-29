package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.SecretCombinationPicker
import app.zenten.vault.ui.components.SecretCombinationsWarning
import app.zenten.vault.ui.components.VaultPillButton

/** Step 6: let the user transcribe the Vault Key backup recovery words as BIP-39 positions. */
@Composable
fun CreateVaultKeySecretCombinationsScreen(
    navController: NavHostController,
    viewModel: CreateVaultKeyViewModel = rememberCreateVaultKeyViewModel(navController),
) {
    val state by viewModel.state.collectAsState()
    val indices = state.secretCombinationIndices
    var codesVisible by remember { mutableStateOf(true) }
    var backedUp by remember { mutableStateOf(false) }

    // Start the expensive derivation only when these values are needed. The graph-scoped
    // ViewModel keeps the result available when the verification screen opens.
    LaunchedEffect(Unit) {
        viewModel.loadSecretCombinationIndices()
        // Reassert the durable point from the destination itself so an immediate process stop
        // cannot send a verified user back to the clipboard screen.
        viewModel.recordSecretCombinationsShown()
    }
    // The generated identity is already committed, and returning would expose a verified
    // clipboard step again. The durable progress marker reopens this screen after restart.
    BackHandler(enabled = true) {}

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.create_vault_key_secret_combinations_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            SecretCombinationsWarning()

            // This is the only flexible region: it places the compact picker slightly above
            // the visual center (negative vertical bias) while keeping acknowledgement and
            // actions at the bottom.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.25f),
            ) {
                if (state.secretCombinationsLoading) {
                    CircularProgressIndicator()
                } else if (indices == null || indices.size != 24) {
                    Text(
                        text = stringResource(R.string.create_vault_key_secret_combinations_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SecretCombinationPicker(
                            groups = indices.chunked(3),
                            visible = codesVisible,
                        )

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.create_vault_key_secret_combinations_instruction),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = backedUp,
                    onCheckedChange = { backedUp = it },
                    enabled = !state.secretCombinationsLoading && indices?.size == 24,
                )
                Text(
                    text = stringResource(R.string.create_vault_key_secret_combinations_consent),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            VaultPillButton(
                label = stringResource(R.string.create_vault_key_secret_combinations_confirm),
                onClick = {
                    navController.navigate(VaultDestinations.CREATE_VAULT_KEY_CONFIRM_SECRET_COMBINATIONS)
                },
                enabled = backedUp && !state.secretCombinationsLoading && indices?.size == 24,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}