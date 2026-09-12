package app.zenten.vault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Height and corner radius shared by every first-run action button. */
private val PillHeight = 54.dp
private val PillCornerRadius = 12.dp

/** Gap between a leading icon and its label inside a pill button. */
private val PillIconSpacing = 12.dp

/**
 * Full-width rounded action button used by the onboarding screens.
 *
 * Two visual ranks are supported and both take their colors from the app theme:
 * the emphasized rank inverts the palette (light container, dark label) so the
 * single primary action stands out, while the default rank sits on the regular
 * surface color. Buttons keep a fixed minimum height instead of relying on
 * Material's default so every stacked button lines up.
 *
 * @param emphasized when true, render as the screen's single primary action.
 * @param icon optional leading content; it is grouped with the label and the
 *   pair is centered together, matching the approved design.
 */
@Composable
fun VaultPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = if (emphasized) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PillHeight),
        shape = RoundedCornerShape(PillCornerRadius),
        colors = colors,
        // The design shows flat buttons; elevation would tint the container.
        elevation = null,
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(PillIconSpacing))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
