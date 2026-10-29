package app.zenten.vault.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zenten.vault.R

/** Minimum height of the header row, so a short title still lines up with the back icon. */
private val HeaderRowHeight = 56.dp

/** Width reserved on each side of the title so it stays optically centered on screen. */
private val HeaderSideSlotWidth = 48.dp

/** Size of the arrow glyph inside the back button's touch target. */
private val BackIconSize = 24.dp

/**
 * Standard screen header: the back affordance and the screen title on one row.
 *
 * The title is centered on the screen rather than in the leftover space, so it lands in
 * the same place whether or not Back is available: both sides reserve a [HeaderSideSlotWidth]
 * slot, and only the leading one holds a button. The back affordance is an icon-only arrow
 * tinted with the normal on-background text color (never the link accent), so it reads as a
 * navigation control rather than a hyperlink. Its meaning is carried by the accessibility
 * description instead of a visible "Back" label.
 *
 * @param title screen title, centered in the row.
 * @param onBack invoked by the back arrow; `null` hides the arrow while keeping its slot
 *   reserved so the title does not shift.
 * @param backEnabled when false the arrow is shown but inert; used to block Back while an
 *   irreversible step is in flight.
 */
@Composable
fun VaultScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeaderRowHeight)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(HeaderSideSlotWidth),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(BackIconSize),
                    )
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(HeaderSideSlotWidth))
    }
}

/**
 * Shared page frame for full-screen flows.
 *
 * Every screen uses the same skeleton: a [VaultScreenHeader] row holding the back arrow and
 * the title, an optional hero illustration, step-specific content, and a bottom-anchored
 * action area. Centralizing it here keeps account creation, account export, settings, and
 * the VaultKey wizard screens visually identical.
 *
 * Screens that supply a [hero] are the exception to the header title: the title moves below
 * the artwork instead of sitting in the header row, so the illustration reads first and the
 * header carries only the back arrow. Screens without a hero keep the title in the header.
 *
 * The page scrolls so short screens never clip the action button. When the soft keyboard
 * opens, the frame shrinks by the keyboard height instead of sliding under it, pulling the
 * bottom action area above the keyboard.
 *
 * @param title screen title, centered in the header row next to the back arrow, or below the
 *   [hero] artwork when a hero is supplied.
 * @param onBack invoked by the back arrow, or `null` when the flow cannot go back.
 * @param backEnabled when false the arrow is shown but inert.
 * @param hero optional artwork shown under the header row.
 * @param content step body placed between the hero and the actions.
 * @param actions bottom-anchored buttons and any copy that must sit with them.
 */
@Composable
fun VaultScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Applied outside verticalScroll on purpose: it reduces the scroll viewport
                // itself, so the bottom action area is laid out above the keyboard rather than
                // being scrolled behind it.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // With a hero the title is rendered under the artwork, so the header shows only
            // the back arrow: an empty title keeps the header row's height and side slots, so
            // the arrow does not shift and screens with and without a hero stay aligned.
            VaultScreenHeader(
                title = if (hero == null) title else "",
                onBack = onBack,
                backEnabled = backEnabled,
            )

            if (hero != null) {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    hero()
                }
                Spacer(Modifier.height(16.dp))
                // headlineMedium, not the header's titleLarge: hero screens keep the larger
                // centered headline they used before the header unification.
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            content()

            // Pushes the action area to the bottom of the viewport when the content is
            // shorter than the screen.
            Spacer(Modifier.weight(1f))

            actions()

            Spacer(Modifier.height(24.dp))
        }
    }
}
