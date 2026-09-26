package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.theme.LocalExtendedColors

/** Final generated-MasterKey step, shown only after every recovery segment is verified. */
@Composable
fun CreateMasterKeyCongratulationsScreen(
    navController: NavHostController,
    viewModel: CreateMasterKeyBackupViewModel = hiltViewModel(),
) {
    // Verification is complete, so returning to secret entry would only expose stale inputs.
    BackHandler(enabled = true) {}

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_congratulations_title),
        heroIcon = R.drawable.hero_icon_congratulations,
        heroIconDescription = stringResource(R.string.create_master_key_congratulations_icon_description),
        onBack = null,
        content = {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.create_master_key_congratulations_success),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LocalExtendedColors.current.success.color,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            CreateMasterKeyDescription(
                text = stringResource(R.string.create_master_key_congratulations_body),
            )
            Spacer(Modifier.height(16.dp))
            AnimatedConfetti(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
        },
        actions = {
            VaultPillButton(
                label = stringResource(R.string.create_master_key_congratulations_complete),
                onClick = {
                    viewModel.completeGeneratedMasterKeySetup {
                        navController.navigate(VaultDestinations.ACCOUNTS) {
                            popUpTo(VaultDestinations.CREATE_MASTER_KEY_GRAPH) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 36.dp),
            )
        },
    )
}

private data class ConfettiPiece(
    val xFraction: Float,
    val phase: Float,
    val rotation: Float,
    val color: Color,
    val isCircle: Boolean,
)

/** Lightweight, dependency-free celebration animation with continuously falling pieces. */
@Composable
private fun AnimatedConfetti(modifier: Modifier = Modifier) {
    val colors = listOf(
        Color(0xFF62F3B6),
        Color(0xFFFFD166),
        Color(0xFFEF476F),
        Color(0xFF4CC9F0),
        Color(0xFFB892FF),
    )
    val pieces = List(28) { index ->
        ConfettiPiece(
            xFraction = ((index * 37) % 101) / 100f,
            phase = ((index * 19) % 29) / 29f,
            rotation = (index * 47f) % 360f,
            color = colors[index % colors.size],
            isCircle = index % 4 == 0,
        )
    }
    val transition = rememberInfiniteTransition(label = "confetti")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "confetti-fall",
    )

    Canvas(modifier = modifier) {
        val pieceWidth = 7.dp.toPx()
        val pieceHeight = 12.dp.toPx()
        pieces.forEachIndexed { index, piece ->
            val progress = (time + piece.phase) % 1f
            val sway = kotlin.math.sin((progress * 6.28f) + index) * 12.dp.toPx()
            val center = Offset(
                x = piece.xFraction * size.width + sway,
                y = progress * (size.height + pieceHeight) - pieceHeight,
            )
            rotate(degrees = piece.rotation + progress * 240f, pivot = center) {
                if (piece.isCircle) {
                    drawCircle(piece.color, radius = pieceWidth / 2f, center = center)
                } else {
                    drawRoundRect(
                        color = piece.color,
                        topLeft = Offset(center.x - pieceWidth / 2f, center.y - pieceHeight / 2f),
                        size = Size(pieceWidth, pieceHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
    }
}
