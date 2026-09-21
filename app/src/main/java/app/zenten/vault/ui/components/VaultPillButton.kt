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
 * Two visual ranks are supported. The emphasized rank pins the container to the
 * theme's primary color so the single primary action stands out; the default
 * rank keeps Material's own `Button` colors untouched so the design system stays
 * the single source of truth. Buttons keep a fixed minimum height instead of
 * relying on Material's default so every stacked button lines up.
 *
 * @param emphasized when true, render as the screen's single primary action.
 * @param enabled when false, the button is inert and rendered with Material's
 *   default disabled treatment (e.g. gating "Confirm" behind a checkbox).
 * @param icon optional leading content; it is grouped with the label and the
 *   pair is centered together, matching the approved design.
 */
@Composable
fun VaultPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    // Only the emphasized rank overrides colors; the default rank inherits the
    // design system's Button palette (including its disabled treatment).
    val colors = if (emphasized) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        ButtonDefaults.buttonColors()
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PillHeight),
        enabled = enabled,
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
