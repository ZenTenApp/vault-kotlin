package app.zenten.vault.ui.onboarding

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultPillButtonStyle
import kotlinx.coroutines.launch

/**
 * Side of the square footprint the app mark is drawn in. The mark carries its own
 * transparent margin, so the visible shield is roughly 90dp wide by 113dp tall here.
 */
private val BrandMarkSize = 200.dp

/** Vertical gap between stacked action buttons. */
private val ButtonSpacing = 8.dp

/** Size of the third-party provider marks in the "continue with" buttons. */
private val ProviderIconSize = 20.dp

/**
 * First-run entry screen.
 *
 * Only the vault-key import path is implemented today, so it is wired to
 * **Login with VaultKey** (the user already holds a key and brings it in).
 * In-app key generation and federated sign-in exist in the approved design but
 * have no implementation yet, so those actions surface a "coming soon"
 * snackbar rather than silently doing nothing.
 */
@Composable
fun GetStartedScreen(navController: NavHostController) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val comingSoonTemplate = stringResource(R.string.get_started_coming_soon)
    val createLabel = stringResource(R.string.get_started_create_vault_key)
    val recoverLabel = stringResource(R.string.get_started_recover_vault_key)
    val loginLabel = stringResource(R.string.get_started_login_vault_key)
    val googleLabel = stringResource(R.string.get_started_provider_google)
    val appleLabel = stringResource(R.string.get_started_provider_apple)
    val microsoftLabel = stringResource(R.string.get_started_provider_microsoft)
    val comingSoonLabel = stringResource(R.string.get_started_coming_soon_label)

    // Replaces any visible message so rapid taps never queue up snackbars.
    val showComingSoon: (String) -> Unit = { feature ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = comingSoonTemplate.format(feature),
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Scrolls on short screens so the action buttons remain reachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            BrandMark()

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.get_started_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.get_started_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            // Generated-key setup continues through local backup confirmation.
            VaultPillButton(
                label = createLabel,
                style = VaultPillButtonStyle.Secondary,
                onClick = { navController.navigate(VaultDestinations.CREATE_VAULT_KEY_INTRO) },
            )

            Spacer(Modifier.height(ButtonSpacing))

            VaultPillButton(
                label = recoverLabel,
                style = VaultPillButtonStyle.Secondary,
                onClick = { navController.navigate(VaultDestinations.RECOVER_VAULT_KEY_COMBINATIONS) },
            )

            Spacer(Modifier.height(ButtonSpacing))

            // Existing-key setup remains available as the alternate path.
            VaultPillButton(
                label = loginLabel,
                onClick = { navController.navigate(VaultDestinations.GET_STARTED_SSH) },
            )

            Spacer(Modifier.height(24.dp))

            ContinueWithDivider()

            Spacer(Modifier.height(24.dp))

            VaultPillButton(
                label = googleLabel,
                enabled = false,
                onClick = { showComingSoon(googleLabel) },
                icon = { ProviderIcon(R.drawable.ic_brand_google, googleLabel) },
                trailingLabel = comingSoonLabel,
            )

            Spacer(Modifier.height(ButtonSpacing))

            VaultPillButton(
                label = appleLabel,
                enabled = false,
                onClick = { showComingSoon(appleLabel) },
                icon = {
                    ProviderIcon(
                        resId = R.drawable.ic_brand_apple,
                        contentDescription = appleLabel,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                trailingLabel = comingSoonLabel,
            )

            Spacer(Modifier.height(ButtonSpacing))

            VaultPillButton(
                label = microsoftLabel,
                enabled = false,
                onClick = { showComingSoon(microsoftLabel) },
                icon = { ProviderIcon(R.drawable.ic_brand_microsoft, microsoftLabel) },
                trailingLabel = comingSoonLabel,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The app mark, drawn exactly as the launcher icon shows it.
 *
 * `Image`, not `Icon`: the asset is multi-tone and owns its colors, so it must not
 * pick up the theme's content color. It also needs no plate behind it, because the
 * screen background is already the icon's `#121212` background color.
 */
@Composable
private fun BrandMark() {
    Image(
        painter = painterResource(R.drawable.vault_app_icon),
        contentDescription = stringResource(R.string.get_started_logo_description),
        modifier = Modifier.size(BrandMarkSize),
    )
}

/**
 * Horizontal rule with the centered "or continue with" caption. The two rules
 * each take the remaining space so the caption stays centered regardless of
 * translated text length.
 */
@Composable
private fun ContinueWithDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = stringResource(R.string.get_started_continue_with),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Renders a third-party brand mark.
 *
 * Multi-color brand guidelines forbid recoloring, so by default the tint is
 * left unspecified and the colors come from the vector asset itself. This
 * keeps color literals out of the UI code while staying brand compliant.
 * Monochrome marks pass an explicit theme color instead.
 */
@Composable
private fun ProviderIcon(
    resId: Int,
    contentDescription: String,
    tint: Color = Color.Unspecified,
) {
    Icon(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = Modifier.size(ProviderIconSize),
        tint = tint,
    )
}
