package app.zenten.vault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/** Height and corner radius shared by every first-run action button. */
private val PillHeight = 54.dp
private val PillCornerRadius = 12.dp

/** Gap between a leading icon and its label inside a pill button. */
private val PillIconSpacing = 12.dp

/** Visual priority for onboarding action buttons. */
enum class VaultPillButtonStyle {
    Primary,
    Secondary,
}

/**
 * Full-width rounded action button used by the onboarding screens.
 *
 * Primary buttons use Material's default action colors. Secondary buttons use
 * the theme's raised neutral surface, keeping their visual priority below a
 * primary action without introducing a hard-coded color. Buttons keep a fixed
 * minimum height instead of relying on Material's default so every stacked
 * button lines up.
 *
 * @param style visual priority of the button; defaults to [VaultPillButtonStyle.Primary].
 * @param enabled when false, the button is inert and rendered with Material's
 *   default disabled treatment (e.g. gating "Confirm" behind a checkbox).
 * @param icon optional leading content; it is grouped with the label and the
 *   pair is centered together, matching the approved design.
 * @param trailingLabel optional small text aligned to the button's right edge.
 */
@Composable
fun VaultPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VaultPillButtonStyle = VaultPillButtonStyle.Primary,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    trailingLabel: String? = null,
) {
    val colors = when (style) {
        VaultPillButtonStyle.Primary -> ButtonDefaults.buttonColors()
        VaultPillButtonStyle.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
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
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
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
                )
            }

            if (trailingLabel != null) {
                Text(
                    text = trailingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    // A secondary label should not compete with the provider name.
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .alpha(0.7f),
                )
            }
        }
    }
}
