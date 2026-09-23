package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.theme.LocalExtendedColors

/** Step 7: sequentially verify every recovery-code segment before setup can continue. */
@Composable
fun CreateMasterKeyConfirmSecretCombinationsScreen(
    navController: NavHostController,
    viewModel: CreateMasterKeyBackupViewModel = hiltViewModel(),
    onCompleted: () -> Unit = {},
) {
    val expected = remember { viewModel.secretCombinationIndices() }
    // Deliberately not saveable: leaving this destination must erase every re-entry.
    val entries = remember { mutableStateListOf(*Array(24) { "" }) }
    var activeSegment by remember { mutableIntStateOf(0) }
    var completed by remember { mutableStateOf(false) }

    ScreenshotProtection()
    BackHandler {
        entries.indices.forEach { entries[it] = "" }
        navController.popBackStack()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ConfirmCombinationsBackLink {
                entries.indices.forEach { entries[it] = "" }
                navController.popBackStack()
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.create_master_key_confirm_secret_combinations_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.create_master_key_confirm_secret_combinations_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))

            if (expected == null || expected.size != 24) {
                Text(
                    text = stringResource(R.string.create_master_key_secret_combinations_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            } else {
                ConfirmCombinationPicker(
                    expected = expected,
                    entries = entries,
                    activeSegment = activeSegment,
                    onEntryChanged = { index, value ->
                        entries[index] = value
                        if (value == expected[index].toString()) {
                            if (index == expected.lastIndex) {
                                completed = true
                                onCompleted()
                            } else {
                                activeSegment = index + 1
                            }
                        }
                    },
                    completed = completed,
                )
            }
        }
    }
}

@Composable
private fun ConfirmCombinationsBackLink(onClick: () -> Unit) {
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
                text = stringResource(R.string.create_master_key_back),
                color = LocalExtendedColors.current.link,
                style = MaterialTheme.typography.titleLarge,
            )
        }
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
private fun ConfirmCombinationPicker(
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
                label = ('A'.code + rowIndex).toChar(),
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
}

@Composable
private fun ConfirmCombinationRow(
    label: Char,
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
    val borderColor = if (incorrect) MaterialTheme.colorScheme.error else Color.Transparent
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
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = MaterialTheme.typography.titleLarge.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(50.dp)
            .then(requestModifier)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(2.dp, borderColor, shape),
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
