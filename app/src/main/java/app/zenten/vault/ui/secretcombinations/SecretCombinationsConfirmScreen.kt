package app.zenten.vault.ui.secretcombinations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import app.zenten.vault.ui.components.ConfirmCombinationPicker
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenHeader

/**
 * Verifies the values the user just wrote down by requiring every segment again.
 *
 * Entry is strictly sequential because the expected values are already known locally. Back
 * returns to the display screen to review the combinations but never leaves the committed flow,
 * and entered values are preserved when the user returns. A process restart returns here and
 * restarts verification from the first segment.
 */
@Composable
fun SecretCombinationsConfirmScreen(
    navController: NavHostController,
    viewModel: SecretCombinationsViewModel = rememberSecretCombinationsViewModel(navController),
) {
    val state by viewModel.state.collectAsState()
    val expected = state.secretCombinationIndices
    val entries = viewModel.secretCombinationEntries
    val activeSegment = viewModel.activeSecretCombinationSegment
    var completed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadSecretCombinationIndices() }

    // Back reviews the displayed combinations; it never leaves the locked flow. Navigating to the
    // SHOW route (rather than popBackStack) also works when a restart resumed directly on CONFIRM.
    val reviewCombinations = {
        viewModel.startShow {
            navController.navigate(VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW) {
                popUpTo(VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM) { inclusive = true }
            }
        }
    }
    BackHandler { reviewCombinations() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VaultScreenHeader(
                title = stringResource(R.string.create_vault_key_confirm_secret_combinations_title),
                onBack = reviewCombinations,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.secret_combinations_confirm_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))

            if (state.secretCombinationsLoading) {
                CircularProgressIndicator()
            } else if (expected == null || expected.size != 24) {
                // Verification cannot start without readable combinations, so allow the only
                // safe exit rather than leaving the user on an unanswerable screen.
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
                    )
                }
            } else {
                ConfirmCombinationPicker(
                    expected = expected,
                    entries = entries,
                    activeSegment = activeSegment,
                    onEntryChanged = { index, value ->
                        viewModel.updateSecretCombinationEntry(index, value)
                        if (value == expected[index].toString()) {
                            if (index == expected.lastIndex) {
                                completed = true
                                // Finish before the success screen so a restart cannot resume
                                // a flow whose verification already succeeded.
                                viewModel.completeFlow {
                                    navController.navigate(VaultDestinations.VIEW_SECRET_COMBINATIONS_COMPLETE) {
                                        popUpTo(VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM) {
                                            inclusive = true
                                        }
                                    }
                                }
                            } else {
                                viewModel.advanceSecretCombinationEntry()
                            }
                        }
                    },
                    completed = completed,
                )
            }
        }
    }
}