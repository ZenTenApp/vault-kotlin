package app.zenten.vault.ui.onboarding.createvaultkey

import android.animation.ValueAnimator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private const val FrameIntervalNanos = 1_000_000_000L / 24
private const val NoiseGlyphs = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789[]{}@#>~=*/%&"

/** One truthful operation status rendered by [DecodeRevealTerminal]. */
internal data class DecodeRevealLine(
    val text: String,
    val active: Boolean,
)

/**
 * Terminal-style operation transcript whose active line resolves from harmless random glyphs.
 * The visual churn is hidden from accessibility; TalkBack receives only the resolved status.
 */
@Composable
internal fun DecodeRevealTerminal(
    lines: List<DecodeRevealLine>,
    accessibilityStatus: String,
    modifier: Modifier = Modifier,
) {
    val inspectionMode = LocalInspectionMode.current
    val animationsEnabled = !inspectionMode && ValueAnimator.areAnimatorsEnabled()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 176.dp)
            // Animated noise must never be read aloud as rapidly changing status text.
            .clearAndSetSemantics {
                contentDescription = accessibilityStatus
                liveRegion = LiveRegionMode.Polite
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lines.forEach { line ->
                DecodeRevealText(
                    line = line,
                    animationsEnabled = animationsEnabled,
                )
            }
        }
    }
}

@Composable
private fun DecodeRevealText(
    line: DecodeRevealLine,
    animationsEnabled: Boolean,
) {
    var revealedCharacters by remember(line.text, line.active, animationsEnabled) {
        mutableIntStateOf(if (line.active) 0 else line.text.length)
    }
    var noiseFrame by remember(line.text) { mutableIntStateOf(0) }

    LaunchedEffect(line.text, line.active, animationsEnabled) {
        if (!line.active) {
            revealedCharacters = line.text.length
            return@LaunchedEffect
        }
        if (!animationsEnabled) {
            // Keep pending feedback encoded without motion rather than claiming completion early.
            revealedCharacters = 0
            return@LaunchedEffect
        }

        var lastRenderedAt = 0L
        while (coroutineContext.isActive) {
            withFrameNanos { frameTime ->
                if (frameTime - lastRenderedAt >= FrameIntervalNanos) {
                    // The line remains entirely encoded until its real operation completes.
                    revealedCharacters = 0
                    noiseFrame++
                    lastRenderedAt = frameTime
                }
            }
        }
    }

    val annotatedText = decodeRevealText(
        line = line,
        revealedCharacters = revealedCharacters,
        noiseFrame = noiseFrame,
        resolvedColor = Color.White,
        noiseColor = Color.White.copy(alpha = 0.25f),
    )

    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = if (line.active) FontWeight.Normal else FontWeight.Bold,
        ),
    )
}

private fun decodeRevealText(
    line: DecodeRevealLine,
    revealedCharacters: Int,
    noiseFrame: Int,
    resolvedColor: Color,
    noiseColor: Color,
): AnnotatedString {
    return AnnotatedString.Builder().apply {
        line.text.forEachIndexed { index, target ->
            val resolved = index < revealedCharacters || target == ' '
            val output = if (resolved) target else noiseGlyph(line.text, index, noiseFrame)
            val color = when {
                !resolved -> noiseColor
                else -> resolvedColor
            }
            pushStyle(SpanStyle(color = color))
            append(output)
            pop()
        }
    }.toAnnotatedString()
}

/** Stable pseudo-random noise avoids retaining or inspecting any secret-bearing buffers. */
private fun noiseGlyph(text: String, index: Int, frame: Int): Char {
    val mixed = text.hashCode() * 31L + index * 131L + frame * 17L
    val glyphIndex = Math.floorMod(mixed, NoiseGlyphs.length.toLong()).toInt()
    return NoiseGlyphs[glyphIndex]
}
