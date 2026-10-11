package app.zenten.vault.ui.onboarding.recovervaultkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.onboarding.createvaultkey.sanitizeCombinationSegment
import app.zenten.vault.ui.theme.LocalExtendedColors

private val RecoveryRowHeight = 80.dp
private val RecoveryRowSpacing = 12.dp
private val RecoverySlotHeight = RecoveryRowHeight + RecoveryRowSpacing

@Composable
fun RecoverVaultKeyCombinationsScreen(
    navController: NavHostController,
    viewModel: RecoverVaultKeyViewModel = rememberRecoverVaultKeyViewModel(navController),
) {
    val state by viewModel.state.collectAsState()
    var focusedSegment by remember { mutableStateOf<Int?>(null) }
    val focusRequesters = remember { List(24) { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    BackHandler { navController.popBackStack() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecoveryBack { navController.popBackStack() }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Secret Combinations",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Enter the 8 Secret Combinations you saved when creating your VaultKey. Scroll to review and correct every value.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                RecoveryCombinationPicker(
                    entries = viewModel.entries,
                    focusedSegment = focusedSegment,
                    showFieldErrors = state.showFieldErrors,
                    focusRequesters = focusRequesters,
                    onFocused = { focusedSegment = it },
                    onEntryChanged = viewModel::updateEntry,
                    onNext = { index ->
                        if (index < viewModel.entries.lastIndex) {
                            focusRequesters[index + 1].requestFocus()
                        } else {
                            focusManager.clearFocus()
                        }
                    },
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
            }
            VaultPillButton(
                label = "Continue",
                onClick = {
                    focusManager.clearFocus()
                    if (viewModel.validateCombinations()) {
                        navController.navigate(VaultDestinations.RECOVER_VAULT_KEY_PASSPHRASE)
                    }
                },
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Scrollable three-row wheel: every field remains editable, including after validation errors. */
@Composable
private fun RecoveryCombinationPicker(
    entries: List<String>,
    focusedSegment: Int?,
    showFieldErrors: Boolean,
    focusRequesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onEntryChanged: (Int, String) -> Unit,
    onNext: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val centeredRow by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }?.index ?: 0
        }
    }
    LaunchedEffect(focusedSegment) {
        focusedSegment?.let { listState.animateScrollToItem(it / 3) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(RecoverySlotHeight * 3),
        contentPadding = PaddingValues(vertical = RecoverySlotHeight),
        flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(entries.chunked(3), key = { index, _ -> index }) { rowIndex, row ->
            RecoveryCombinationRow(
                label = rowIndex + 1,
                entries = row,
                firstSegmentIndex = rowIndex * 3,
                positionFromCenter = rowIndex.compareTo(centeredRow),
                showFieldErrors = showFieldErrors,
                focusRequesters = focusRequesters,
                onFocused = onFocused,
                onEntryChanged = onEntryChanged,
                onNext = onNext,
                modifier = Modifier.padding(vertical = RecoveryRowSpacing / 2),
            )
        }
    }
}

@Composable
private fun RecoveryCombinationRow(
    label: Int,
    entries: List<String>,
    firstSegmentIndex: Int,
    positionFromCenter: Int,
    showFieldErrors: Boolean,
    focusRequesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onEntryChanged: (Int, String) -> Unit,
    onNext: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val centered = positionFromCenter == 0
    val shape = RoundedCornerShape(12.dp)
    val edgeColor = MaterialTheme.colorScheme.background
    val innerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val brush = when {
        positionFromCenter < 0 -> Brush.verticalGradient(listOf(edgeColor, innerColor))
        positionFromCenter > 0 -> Brush.verticalGradient(listOf(innerColor, edgeColor))
        else -> Brush.verticalGradient(listOf(innerColor, innerColor))
    }
    Row(
        modifier = modifier.fillMaxWidth().height(RecoveryRowHeight).alpha(if (centered) 1f else 0.32f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
        )
        Row(
            modifier = Modifier.weight(1f)
                .then(if (centered) Modifier.shadow(10.dp, shape, clip = false) else Modifier)
                .background(brush, shape).padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.indices.forEach { segmentInRow ->
                val index = firstSegmentIndex + segmentInRow
                val numericValue = entries[segmentInRow].toIntOrNull()
                RecoveryCombinationSegment(
                    value = entries[segmentInRow],
                    isFinalSegment = index == 23,
                    incorrect = showFieldErrors && (numericValue == null || numericValue !in 1..2048),
                    focusRequester = focusRequesters[index],
                    onFocused = { onFocused(index) },
                    onValueChange = { onEntryChanged(index, it) },
                    onSubmit = { onNext(index) },
                    modifier = Modifier.weight(1f),
                )
                if (segmentInRow < 2) Text("–", color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
            }
        }
    }
}

@Composable
private fun RecoveryCombinationSegment(
    value: String,
    isFinalSegment: Boolean,
    incorrect: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    BasicTextField(
        value = value,
        onValueChange = { proposed ->
            val sanitized = sanitizeCombinationSegment(proposed)
            onValueChange(sanitized)
            if (sanitized.length == 4) onSubmit()
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = if (isFinalSegment) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onSubmit() }, onDone = { onSubmit() }),
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = MaterialTheme.typography.titleLarge.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.height(50.dp).focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(2.dp, if (incorrect) MaterialTheme.colorScheme.error else Color.Transparent, shape),
        decorationBox = { innerField -> Box(contentAlignment = Alignment.Center) { innerField() } },
    )
}

@Composable
fun RecoverVaultKeyPassphraseScreen(
    navController: NavHostController,
    viewModel: RecoverVaultKeyViewModel = rememberRecoverVaultKeyViewModel(navController),
) {
    var passphrase by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.completed) {
        if (state.completed) navController.navigate(VaultDestinations.ACCOUNTS) {
            popUpTo(VaultDestinations.GET_STARTED) { inclusive = true }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RecoveryBack { navController.popBackStack() }
        Spacer(Modifier.height(36.dp))
        Text("Enter Passphrase", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Enter the passphrase that protects your VaultKey.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it; viewModel.clearError() },
            label = { Text("VaultKey Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.inProgress,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.recover(passphrase) },
            enabled = passphrase.isNotBlank() && !state.inProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.inProgress) CircularProgressIndicator() else Text("Recover VaultKey")
        }
    }
}

@Composable
private fun RecoveryBack(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(top = 8.dp),
    ) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = LocalExtendedColors.current.link,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Back",
                color = LocalExtendedColors.current.link,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
