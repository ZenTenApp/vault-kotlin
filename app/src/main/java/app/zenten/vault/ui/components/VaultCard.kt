package app.zenten.vault.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Standard content card.
 *
 * No color override is needed: Compose's `Card` defaults to
 * `surfaceContainerHighest`, which the palette pins to the approved card surface
 * (#232323). Callers supply their own layout, padding, and interaction behavior.
 */
@Composable
fun VaultCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        content = content,
    )
}
