package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultScreenScaffold

/**
 * Footprint of the hero illustration shown under the header row.
 *
 * Square on purpose even though the three assets have slightly different
 * aspect ratios: each one is scaled to fit inside this box, so every step
 * reserves exactly the same vertical space and the title never shifts between
 * steps.
 */
internal val HeroIconSize = 132.dp

/**
 * Shared page frame for every **Create VaultKey** wizard screen.
 *
 * All wizard steps use the same skeleton: a header row with an icon-only back
 * arrow beside the centered title, a hero illustration, step-specific content,
 * and finally a bottom-anchored action area. The layout itself lives in
 * [VaultScreenScaffold] so account creation and account export can reuse exactly
 * the same frame; this wrapper only supplies the wizard's hero artwork.
 *
 * Supporting copy is intentionally not part of this frame: each step owns its
 * own description (or omits it) inside [content], because the steps differ in
 * how many paragraphs they need and how much space sits before their fields.
 *
 * @param title centered title in the header row.
 * @param heroIcon drawable shown above the title for this step.
 * @param heroIconDescription accessibility label for [heroIcon]; the artwork
 *   carries meaning (what the step is about), so it is not treated as purely
 *   decorative.
 * @param onBack invoked by the "< Back" link, or `null` when the committed flow cannot go back.
 * @param content step body placed between the title and the actions.
 * @param actions bottom-anchored buttons and any copy that must sit with them.
 */
@Composable
internal fun CreateVaultKeyScaffold(
    title: String,
    @DrawableRes heroIcon: Int,
    heroIconDescription: String,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    VaultScreenScaffold(
        title = title,
        onBack = onBack,
        hero = { HeroIcon(resId = heroIcon, contentDescription = heroIconDescription) },
        content = content,
        actions = actions,
    )
}

/**
 * Centered supporting copy for a wizard step.
 *
 * Lives here rather than in [VaultScreenScaffold] so each step decides
 * whether it has a description at all and how many paragraphs it needs, while
 * still sharing one text style.
 */
@Composable
internal fun CreateVaultKeyDescription(text: String, modifier: Modifier = Modifier) {
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
internal fun CreateVaultKeyWarningRow(centered: Boolean = false) {
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
            text = stringResource(R.string.create_vault_key_intro_warning),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.error,
        )
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
