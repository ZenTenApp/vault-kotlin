package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton

/** Step 6: let the user transcribe the Brave recovery words as BIP-39 positions. */
@Composable
fun CreateMasterKeySecretCombinationsScreen(
    navController: NavHostController,
    viewModel: CreateMasterKeyBackupViewModel = hiltViewModel(),
) {
    val indices = remember { viewModel.secretCombinationIndices() }
    var codesVisible by remember { mutableStateOf(true) }
    var backedUp by remember { mutableStateOf(false) }

    ScreenshotProtection()
    // Reassert the durable point from the destination itself so an immediate process stop
    // cannot send a verified user back to the clipboard screen.
    LaunchedEffect(Unit) { viewModel.recordSecretCombinationsShown() }
    // The generated identity is already committed, and returning would expose a verified
    // clipboard step again. The durable progress marker reopens this screen after restart.
    BackHandler(enabled = true) {}

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.create_master_key_secret_combinations_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            SecretCombinationsWarning()

            // This is the only flexible region: it places the compact picker near the
            // visual center while keeping acknowledgement and actions at the bottom.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (indices == null || indices.size != 24) {
                    Text(
                        text = stringResource(R.string.create_master_key_secret_combinations_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SecretCombinationPicker(
                            groups = indices.chunked(3),
                            visible = codesVisible,
                        )

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.create_master_key_secret_combinations_instruction),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = backedUp,
                    onCheckedChange = { backedUp = it },
                    enabled = indices != null,
                )
                Text(
                    text = stringResource(R.string.create_master_key_secret_combinations_consent),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            VaultPillButton(
                label = stringResource(R.string.create_master_key_secret_combinations_confirm),
                onClick = {
                    navController.navigate(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_SECRET_COMBINATIONS)
                },
                enabled = backedUp && indices != null,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Keeps the required action and the consequence together without repeating either below. */
@Composable
private fun SecretCombinationsWarning() {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.create_master_key_secret_combinations_warning_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.create_master_key_secret_combinations_warning_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private val CombinationRowHeight = 72.dp
private val CombinationRowSpacing = 10.dp
private val CombinationSlotHeight = CombinationRowHeight + CombinationRowSpacing

/**
 * Three-row snapping wheel. Empty edge padding lets A and H occupy the center target
 * without wrapping, while only this compact region—not the whole screen—scrolls.
 */
@Composable
private fun SecretCombinationPicker(groups: List<List<Int>>, visible: Boolean) {
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
                label = ('A'.code + rowIndex).toChar(),
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
    label: Char,
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
            style = MaterialTheme.typography.headlineSmall,
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
