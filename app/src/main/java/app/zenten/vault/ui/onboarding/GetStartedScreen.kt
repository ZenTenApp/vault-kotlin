package app.zenten.vault.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultPillButtonStyle

/**
 * Rendered size of the app mark. The asset is cropped to the shield's own bounds
 * (243.89 x 304.23 vector units), so it is portrait, carries no transparent margin,
 * and the width follows from the height through that aspect ratio.
 */
private val BrandMarkHeight = 200.dp
private val BrandMarkWidth = BrandMarkHeight * (243.89f / 304.23f) // ≈ 160.dp

/** Vertical gap between stacked action buttons. */
private val ButtonSpacing = 12.dp

/** Taller than the shared pill default (54dp) for easier tapping on this screen. */
private val ActionButtonMinHeight = 56.dp

/** Space under the last button; the Scaffold padding already covers the nav bar. */
private val BottomPadding = 24.dp

/**
 * First-run entry screen offering the vault-key paths: create, recover, or
 * log in with an existing key (pasted or scanned as a QR code). Federated sign-in (Google/Apple/Microsoft) is
 * intentionally not shown until it is implemented.
 */
@Composable
fun GetStartedScreen(navController: NavHostController) {
    val createLabel = stringResource(R.string.get_started_create_vault_key)
    val recoverLabel = stringResource(R.string.get_started_recover_vault_key)
    val loginLabel = stringResource(R.string.get_started_login_vault_key)
    val loginQrLabel = stringResource(R.string.get_started_login_qr_code)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // BoxWithConstraints gives the visible height so the content can be at least
        // that tall, which lets the buttons sit at the bottom on tall screens.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Minimum height = screen height; on short screens or with large
                    // fonts the content grows taller and scrolls so buttons stay reachable.
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // With the extra height, this pushes the buttons to the bottom edge.
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))

                    BrandMark()

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = stringResource(R.string.get_started_title),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
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

                    // Keeps the text from touching the buttons when the screen is short.
                    Spacer(Modifier.height(40.dp))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Generated-key setup continues through local backup confirmation.
                    VaultPillButton(
                        label = createLabel,
                        style = VaultPillButtonStyle.Secondary,
                        modifier = Modifier.heightIn(min = ActionButtonMinHeight),
                        onClick = { navController.navigate(VaultDestinations.CREATE_VAULT_KEY_INTRO) },
                    )

                    Spacer(Modifier.height(ButtonSpacing))

                    VaultPillButton(
                        label = recoverLabel,
                        style = VaultPillButtonStyle.Secondary,
                        modifier = Modifier.heightIn(min = ActionButtonMinHeight),
                        onClick = { navController.navigate(VaultDestinations.RECOVER_VAULT_KEY_COMBINATIONS) },
                    )

                    Spacer(Modifier.height(ButtonSpacing))

                    // Existing-key setup remains available as the alternate path.
                    VaultPillButton(
                        label = loginLabel,
                        style = VaultPillButtonStyle.Secondary,
                        modifier = Modifier.heightIn(min = ActionButtonMinHeight),
                        onClick = { navController.navigate(VaultDestinations.GET_STARTED_SSH) },
                    )

                    Spacer(Modifier.height(ButtonSpacing))

                    // Same import as the paste path, but the VaultKey is scanned from the QR code
                    // shown on another device's VaultKey detail screen.
                    VaultPillButton(
                        label = loginQrLabel,
                        style = VaultPillButtonStyle.Secondary,
                        modifier = Modifier.heightIn(min = ActionButtonMinHeight),
                        onClick = { navController.navigate(VaultDestinations.GET_STARTED_QR) },
                    )

                    Spacer(Modifier.height(BottomPadding))
                }
            }
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
        modifier = Modifier.size(width = BrandMarkWidth, height = BrandMarkHeight),
    )
}

