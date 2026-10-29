package app.zenten.vault.ui.onboarding.createvaultkey

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
import app.zenten.vault.ui.components.SecretCombinationsBackLink

/** Step 7: sequentially verify every recovery-code segment before setup can continue. */
@Composable
fun CreateVaultKeyConfirmSecretCombinationsScreen(
    navController: NavHostController,
    viewModel: CreateVaultKeyViewModel = rememberCreateVaultKeyViewModel(navController),
    onCompleted: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val expected = state.secretCombinationIndices
    val entries = viewModel.secretCombinationEntries
    val activeSegment = viewModel.activeSecretCombinationSegment
    var completed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadSecretCombinationIndices() }
    BackHandler { navController.popBackStack() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SecretCombinationsBackLink { navController.popBackStack() }
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.create_vault_key_confirm_secret_combinations_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.create_vault_key_confirm_secret_combinations_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))

            if (state.secretCombinationsLoading) {
                CircularProgressIndicator()
            } else if (expected == null || expected.size != 24) {
                Text(
                    text = stringResource(R.string.create_vault_key_secret_combinations_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
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
                                // Verification is the last step: finish setup and open Accounts
                                // directly, without the former green-checkmark celebration screen.
                                viewModel.completeGeneratedVaultKeySetup {
                                    onCompleted()
                                    navController.navigate(VaultDestinations.ACCOUNTS) {
                                        popUpTo(VaultDestinations.CREATE_VAULT_KEY_GRAPH) { inclusive = true }
                                        launchSingleTop = true
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
