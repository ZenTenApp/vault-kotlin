package app.zenten.vault.ui.secretcombinations

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

/** Brief success confirmation shown after every combination has been verified. */
@Composable
fun SecretCombinationsCompleteScreen(navController: NavHostController) {
    // Verification already finished and the flow marker is cleared, so returning would only
    // expose stale inputs.
    BackHandler(enabled = true) {}

    LaunchedEffect(Unit) {
        // Keep the success indicator visible, then return to the screen the flow started from.
        delay(SUCCESS_DISPLAY_MILLIS)
        navController.navigate(VaultDestinations.SETTINGS) {
            popUpTo(VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH) { inclusive = true }
            launchSingleTop = true
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
                    R.string.secret_combinations_complete_icon_description,
                ),
                tint = LocalExtendedColors.current.success.color,
                modifier = Modifier.size(160.dp),
            )
        }
    }
}