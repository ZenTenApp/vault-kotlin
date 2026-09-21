package app.zenten.vault.ui.onboarding.createmasterkey

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zenten.vault.R
import app.zenten.vault.ui.theme.LocalExtendedColors

/**
 * Footprint of the hero illustration above the title.
 *
 * Square on purpose even though the three assets have slightly different
 * aspect ratios: each one is scaled to fit inside this box, so every step
 * reserves exactly the same vertical space and the title never shifts between
 * steps.
 */
internal val HeroIconSize = 132.dp

/**
 * Shared page frame for every **Create MasterKey** wizard screen.
 *
 * All wizard steps use the same skeleton: a top-left "< Back" link, a hero
 * illustration, a centered title, then step-specific content, and finally a
 * bottom-anchored action area above the copyright footer. Centralizing it here
 * keeps the steps visually identical; only the artwork and copy differ.
 *
 * Supporting copy is intentionally not part of this frame: each step owns its
 * own description (or omits it) inside [content], because the steps differ in
 * how many paragraphs they need and how much space sits before their fields.
 *
 * The whole page scrolls so short screens can never clip the action button or
 * the footer. When the soft keyboard opens, the frame shrinks by the keyboard
 * height instead of sliding under it, which pulls the bottom-anchored action
 * area up above the keyboard and leaves the rest of the page scrollable.
 *
 * @param title large headline under the illustration.
 * @param heroIcon drawable shown above the title for this step.
 * @param heroIconDescription accessibility label for [heroIcon]; the artwork
 *   carries meaning (what the step is about), so it is not treated as purely
 *   decorative.
 * @param onBack invoked by the "< Back" link.
 * @param content step body placed between the title and the actions.
 * @param actions bottom-anchored buttons and any copy that must sit with them.
 */
@Composable
internal fun CreateMasterKeyScaffold(
    title: String,
    @DrawableRes heroIcon: Int,
    heroIconDescription: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Applied outside verticalScroll on purpose: it reduces the
                // scroll viewport itself, so the bottom action area is laid out
                // above the keyboard rather than being scrolled behind it.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            BackLink(onClick = onBack)

            // fillMaxWidth is required for the centering to take effect: without
            // it this Column wraps to its widest child and the parent Column
            // (default Start alignment) pins that narrow block to the left edge.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeroIcon(resId = heroIcon, contentDescription = heroIconDescription)

                Spacer(Modifier.height(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }

            content()

            // Pushes the action area to the bottom of the viewport when the
            // content is shorter than the screen.
            Spacer(Modifier.weight(1f))

            actions()

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

/**
 * Centered supporting copy for a wizard step.
 *
 * Lives here rather than in [CreateMasterKeyScaffold] so each step decides
 * whether it has a description at all and how many paragraphs it needs, while
 * still sharing one text style. It stretches to the full width because the
 * scaffold's content slot is not itself center-aligned.
 */
@Composable
internal fun CreateMasterKeyDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Red warning row stating that backup material cannot be recovered if lost.
 *
 * Shared by every wizard step that is about to create or display recovery
 * material, so the wording and treatment of that warning stay identical.
 *
 * @param centered when true the icon and text are centered as a pair, matching
 *   the backup steps; the intro step keeps them left-aligned with its body copy.
 */
@Composable
internal fun CreateMasterKeyWarningRow(centered: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
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

/** Top-left "< Back" navigation link, styled after the approved design's link color. */
@Composable
private fun BackLink(onClick: () -> Unit) {
    Row(modifier = Modifier.padding(top = 8.dp)) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = LocalExtendedColors.current.link,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.create_master_key_back),
                color = LocalExtendedColors.current.link,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Hero illustration for a wizard step.
 *
 * Drawn with [Image] rather than `Icon` and without a tint: these assets are
 * multi-tone line art (light outlines over darker fills) and tinting would
 * flatten them into a single-color silhouette. Their colors therefore live in
 * the vector assets, which is safe because the app always renders its dark
 * scheme (see `ZenTenVaultTheme`).
 */
@Composable
private fun HeroIcon(@DrawableRes resId: Int, contentDescription: String) {
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        // Fit keeps each asset's aspect ratio inside the shared square box.
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(HeroIconSize),
    )
}

/**
 * Blocks screenshots and screen recording while the composable is active.
 *
 * Applied to every wizard step that holds passphrase input on screen, matching
 * the other secret-handling screens in the app. The flag is cleared on dispose
 * so unrelated screens keep normal behavior.
 */
@Composable
internal fun ScreenshotProtection() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
