package app.zenten.vault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zenten.vault.R
import app.zenten.vault.ui.theme.LocalExtendedColors

/**
 * Reveal-and-verify UI for the VaultKey's 24 BIP-39 word positions.
 *
 * Both the first-run **Create VaultKey** wizard and the later **View Secret Combinations**
 * flow transcribe and verify the same values, so the pickers live here rather than being
 * duplicated in each wizard. Callers own the index list, the transcribed values, and
 * navigation; these composables only render and report input.
 */

/**
 * In-flow Back affordance used by the combination screens.
 *
 * It moves between steps of the locked flow, never out of it: callers decide the destination and
 * are expected to keep the user inside the flow.
 */
@Composable
internal fun SecretCombinationsBackLink(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = LocalExtendedColors.current.link,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.create_vault_key_back),
                color = LocalExtendedColors.current.link,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

private val CombinationRowHeight = 72.dp
private val CombinationRowSpacing = 10.dp
private val CombinationSlotHeight = CombinationRowHeight + CombinationRowSpacing

/** Warning card shown where the combinations can be lost if they are not written down. */
@Composable
internal fun SecretCombinationsWarning() {
    val shape = RoundedCornerShape(12.dp)
    val errorColor = MaterialTheme.colorScheme.error
    val warningTitle = stringResource(R.string.create_vault_key_secret_combinations_warning_title)
    val warningBody = stringResource(R.string.create_vault_key_secret_combinations_warning_body)

    // Render the icon as inline content of the text so the message starts on the same line as the
    // icon, while wrapped lines fall back to the card's left edge (under the icon) instead of being
    // indented under the "Warning:" label. `InlineTextContent` reserves leading space equal to the
    // icon, so the placeholder's TextUnit size must match the drawn icon size.
    val iconSize = 24.dp
    val inlineIconSize = with(LocalDensity.current) { iconSize.toSp() }
    val inlineContent = mapOf(
        WarningIconKey to InlineTextContent(
            placeholder = Placeholder(
                width = inlineIconSize,
                height = inlineIconSize,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = errorColor,
                modifier = Modifier.size(iconSize),
            )
        },
    )

    Text(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, errorColor, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        text = buildAnnotatedString {
            appendInlineContent(WarningIconKey)
            // Gap between the inline icon and the label.
            append(" ")
            withStyle(SpanStyle(color = errorColor, fontWeight = FontWeight.SemiBold)) {
                append(warningTitle)
            }
            // Android trims trailing whitespace in string resources, so add the separator here to
            // keep a visible gap between "Warning:" and the body text.
            append(" ")
            append(warningBody)
        },
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Key used to place [SecretCombinationsWarning]'s icon inside its text flow. */
private const val WarningIconKey = "warningIcon"

/**
 * Three-row snapping wheel. Empty edge padding lets rows 1 and 8 occupy the center target
 * without wrapping, while only this compact region—not the whole screen—scrolls.
 */
@Composable
internal fun SecretCombinationPicker(groups: List<List<Int>>, visible: Boolean) {
    val listState = rememberLazyListState()
    val centeredIndex by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs((item.offset + item.size / 2) - viewportCenter)
            }?.index ?: 0
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(CombinationSlotHeight * 3),
        contentPadding = PaddingValues(vertical = CombinationSlotHeight),
        flingBehavior = rememberSnapFlingBehavior(
            lazyListState = listState,
            snapPosition = SnapPosition.Center,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(groups, key = { index, _ -> index }) { rowIndex, group ->
            SecretCombinationRow(
                label = rowIndex + 1,
                indices = group,
                visible = visible,
                positionFromCenter = rowIndex.compareTo(centeredIndex),
                // Symmetric spacing keeps the card's center on the snap point.
                modifier = Modifier.padding(vertical = CombinationRowSpacing / 2),
            )
        }
    }
}

@Composable
private fun SecretCombinationRow(
    label: Int,
    indices: List<Int>,
    visible: Boolean,
    /** Negative above center, zero at center, positive below center. */
    positionFromCenter: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val isCentered = positionFromCenter == 0
    val contentOpacity = if (isCentered) 1f else 0.28f
    val edgeColor = MaterialTheme.colorScheme.background
    val innerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val rowBrush = when {
        positionFromCenter < 0 -> Brush.verticalGradient(
            colors = listOf(edgeColor, innerColor),
        )
        positionFromCenter > 0 -> Brush.verticalGradient(
            colors = listOf(innerColor, edgeColor),
        )
        else -> Brush.verticalGradient(listOf(innerColor, innerColor))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CombinationRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 12.dp)
                .alpha(contentOpacity),
        )
        Text(
            text = if (visible) indices.joinToString("-") else "••••-••••-••••",
            // Slightly larger than the default headlineSmall so the numbers read as the primary
            // content of each row without outgrowing the fixed row height.
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentOpacity),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                // Elevation belongs only to the selected card. Neighboring cards use
                // directional fills so they visually recede toward the wheel edges.
                .then(
                    if (isCentered) Modifier.shadow(10.dp, shape, clip = false)
                    else Modifier,
                )
                .background(brush = rowBrush, shape = shape)
                .padding(vertical = 18.dp, horizontal = 8.dp),
        )
    }
}

private val ConfirmRowHeight = 80.dp
private val ConfirmRowSpacing = 12.dp
private val ConfirmSlotHeight = ConfirmRowHeight + ConfirmRowSpacing

/**
 * The list moves only under program control. This preserves the three-row wheel appearance
 * while preventing users from skipping ahead or revisiting already verified segments.
 */
@Composable
internal fun ConfirmCombinationPicker(
    expected: List<Int>,
    entries: List<String>,
    activeSegment: Int,
    onEntryChanged: (Int, String) -> Unit,
    completed: Boolean,
) {
    val activeRow = activeSegment / 3
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(activeSegment, completed) {
        listState.animateScrollToItem(activeRow)
        if (completed) {
            keyboard?.hide()
        } else {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // Surface a single error line whenever any already-typed segment diverges from its
    // expected value. Empty segments are treated as "not yet wrong" (see the helper).
    val hasIncorrect = expected.indices.any { index ->
        isCombinationSegmentIncorrect(expected[index], entries.getOrElse(index) { "" })
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(ConfirmSlotHeight * 3),
            contentPadding = PaddingValues(vertical = ConfirmSlotHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(expected.chunked(3), key = { index, _ -> index }) { rowIndex, row ->
                val positionFromCenter = rowIndex.compareTo(activeRow)
                ConfirmCombinationRow(
                    label = rowIndex + 1,
                    expected = row,
                    entries = entries.subList(rowIndex * 3, rowIndex * 3 + 3),
                    firstSegmentIndex = rowIndex * 3,
                    activeSegment = activeSegment,
                    positionFromCenter = positionFromCenter,
                    focusRequester = focusRequester,
                    onEntryChanged = onEntryChanged,
                    modifier = Modifier.padding(vertical = ConfirmRowSpacing / 2),
                )
            }
        }

        if (hasIncorrect) {
            Text(
                text = stringResource(R.string.create_vault_key_confirm_secret_combinations_incorrect),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ConfirmCombinationRow(
    label: Int,
    expected: List<Int>,
    entries: List<String>,
    firstSegmentIndex: Int,
    activeSegment: Int,
    /** Negative above center, zero at center, positive below center. */
    positionFromCenter: Int,
    focusRequester: FocusRequester,
    onEntryChanged: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActiveRow = positionFromCenter == 0
    val rowOpacity = if (isActiveRow) 1f else 0.32f
    val shape = RoundedCornerShape(12.dp)
    val edgeColor = MaterialTheme.colorScheme.background
    val innerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    // Match the save-combinations wheel: adjacent cards fade into the screen edge,
    // while elevation keeps the centered row visually above them.
    val rowBrush = when {
        positionFromCenter < 0 -> Brush.verticalGradient(listOf(edgeColor, innerColor))
        positionFromCenter > 0 -> Brush.verticalGradient(listOf(innerColor, edgeColor))
        else -> Brush.verticalGradient(listOf(innerColor, innerColor))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ConfirmRowHeight)
            .alpha(rowOpacity),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (isActiveRow) Modifier.shadow(10.dp, shape, clip = false)
                    else Modifier,
                )
                .background(rowBrush, shape)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            expected.indices.forEach { segmentInRow ->
                val index = firstSegmentIndex + segmentInRow
                ConfirmCombinationSegment(
                    value = entries[segmentInRow],
                    expected = expected[segmentInRow].toString(),
                    enabled = index == activeSegment && isActiveRow,
                    focusRequester = if (index == activeSegment) focusRequester else null,
                    onValueChange = { onEntryChanged(index, it) },
                    modifier = Modifier.weight(1f),
                )
                if (segmentInRow < 2) {
                    Text(
                        text = "–",
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmCombinationSegment(
    value: String,
    expected: String,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    val incorrect = isCombinationSegmentIncorrect(expected.toInt(), value)
    // Invalid input is signalled by the text itself (error color, semi-bold), not a red border.
    val textColor = if (incorrect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val textWeight = if (incorrect) FontWeight.SemiBold else FontWeight.Medium
    val requestModifier = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)

    BasicTextField(
        value = value,
        onValueChange = { proposed ->
            onValueChange(sanitizeCombinationSegment(proposed))
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = TextStyle(
            color = textColor,
            fontSize = MaterialTheme.typography.titleLarge.fontSize,
            fontWeight = textWeight,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(50.dp)
            .then(requestModifier)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(2.dp, Color.Transparent, shape),
        decorationBox = { innerField ->
            Box(contentAlignment = Alignment.Center) { innerField() }
        },
    )
}

/** Numeric-only and bounded to the largest possible BIP-39 index width. */
internal fun sanitizeCombinationSegment(proposed: String): String =
    proposed.filter(Char::isDigit).take(4)

/** A partial prefix is still potentially valid; mark it wrong only once it diverges. */
internal fun isCombinationSegmentIncorrect(expected: Int, value: String): Boolean =
    value.isNotEmpty() && !expected.toString().startsWith(value)