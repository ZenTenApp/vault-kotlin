package app.zenten.vault.ui.secretcombinations

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

/**
 * Displays the VaultKey's 24 BIP-39 positions for the user to write down again.
 *
 * This is the first irreversible step of the review flow: the exit action advances to
 * verification only, and system Back is blocked so the validated passphrase gate cannot be
 * escaped without finishing. The persisted step marker reopens this screen after a restart.
 */
@Composable
fun SecretCombinationsShowScreen(
    navController: NavHostController,
    viewModel: SecretCombinationsViewModel = rememberSecretCombinationsViewModel(navController),
) {
    val state by viewModel.state.collectAsState()
    val indices = state.secretCombinationIndices
    val codesVisible = true
    var backedUp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadSecretCombinationIndices() }
    // The flow is committed once the passphrase matched; the only exit is verification.
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

            // This is the only flexible region: it places the compact picker near the
            // visual center while keeping acknowledgement and actions at the bottom.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (state.secretCombinationsLoading) {
                    CircularProgressIndicator()
                } else if (indices == null || indices.size != 24) {
                    // The locked flow cannot proceed without readable combinations, so offer
                    // the only safe exit instead of trapping the user on this error.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.create_vault_key_secret_combinations_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        VaultPillButton(
                            label = stringResource(R.string.secret_combinations_unavailable_exit),
                            onClick = {
                                viewModel.exitUnavailableFlow {
                                    navController.navigate(VaultDestinations.SETTINGS) {
                                        popUpTo(VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            enabled = !state.advancing,
                            modifier = Modifier.padding(horizontal = 36.dp),
                        )
                    }
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
                    viewModel.startConfirm {
                        navController.navigate(VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM) {
                            popUpTo(VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW) { inclusive = true }
                        }
                    }
                },
                enabled = backedUp && !state.secretCombinationsLoading && indices?.size == 24 && !state.advancing,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}