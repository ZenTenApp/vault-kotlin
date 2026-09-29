package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay

private const val SUCCESS_DISPLAY_MILLIS = 3_000L

/** Brief success confirmation shown after every recovery segment is verified. */
@Composable
fun CreateMasterKeyCongratulationsScreen(
    navController: NavHostController,
    viewModel: CreateMasterKeyViewModel = rememberCreateMasterKeyViewModel(navController),
) {
    // Verification is complete, so returning to secret entry would only expose stale inputs.
    BackHandler(enabled = true) {}

    LaunchedEffect(Unit) {
        // Leave the unambiguous success indicator visible before completing setup automatically.
        delay(SUCCESS_DISPLAY_MILLIS)
        viewModel.completeGeneratedMasterKeySetup {
            navController.navigate(VaultDestinations.ACCOUNTS) {
                popUpTo(VaultDestinations.CREATE_MASTER_KEY_GRAPH) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = stringResource(
                    R.string.create_master_key_congratulations_icon_description,
                ),
                tint = LocalExtendedColors.current.success.color,
                modifier = Modifier.size(160.dp),
            )
        }
    }
}
