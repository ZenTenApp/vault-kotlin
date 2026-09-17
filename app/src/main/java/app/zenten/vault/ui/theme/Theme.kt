package app.zenten.vault.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

object VaultColors {
    // Core surfaces
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF232323)
    val SurfaceVariant = Color(0xFF3A3A3A)

    // Brand / interaction
    val Primary = Color(0xFFD9D9D9)

    // Content
    val TextPrimary = Color(0xFFE5E5E5)
    val TextStrong = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB6B6B6)

    // Semantic states
    val Error = Color(0xFFF36262)
    val Success = Color(0xFF62F3B6)

    // iOS-style system blue used for the top-left "Back" navigation link in
    // the master-key creation flow. Kept as an app-specific accent rather
    // than colorScheme.primary because it is only used for this link style,
    // not for primary actions.
    val Link = Color(0xFF007AFF)

    // Borders
    val Outline = Color(0xFF6F6F6F)
    val OutlineVariant = Color(0xFF3A3A3A)
}

private val DarkColors = darkColorScheme(
    // -------------------------------------------------------------------------
    // Primary
    // -------------------------------------------------------------------------
    primary = VaultColors.Primary,
    onPrimary = VaultColors.Background,

    primaryContainer = VaultColors.SurfaceVariant,
    onPrimaryContainer = VaultColors.TextPrimary,

    // -------------------------------------------------------------------------
    // Secondary
    //
    // Kept intentionally neutral. This app does not have a second
    // colorful brand/accent color.
    // -------------------------------------------------------------------------
    secondary = VaultColors.TextSecondary,
    onSecondary = VaultColors.Background,

    secondaryContainer = VaultColors.SurfaceVariant,
    onSecondaryContainer = VaultColors.TextPrimary,

    // -------------------------------------------------------------------------
    // Tertiary
    //
    // No separate tertiary brand color is needed for this monochrome theme.
    // Success is kept as an app-specific semantic color instead.
    // -------------------------------------------------------------------------
    tertiary = VaultColors.TextSecondary,
    onTertiary = VaultColors.Background,

    tertiaryContainer = VaultColors.SurfaceVariant,
    onTertiaryContainer = VaultColors.TextPrimary,

    // -------------------------------------------------------------------------
    // Background
    // -------------------------------------------------------------------------
    background = VaultColors.Background,
    onBackground = VaultColors.TextPrimary,

    // -------------------------------------------------------------------------
    // Surfaces
    // -------------------------------------------------------------------------
    surface = VaultColors.Surface,
    onSurface = VaultColors.TextPrimary,

    surfaceVariant = VaultColors.SurfaceVariant,
    onSurfaceVariant = VaultColors.TextSecondary,

    // Surface hierarchy
    surfaceContainerLowest = VaultColors.Background,
    surfaceContainerLow = VaultColors.Surface,
    surfaceContainer = VaultColors.Surface,
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF303030),

    // -------------------------------------------------------------------------
    // Error
    // -------------------------------------------------------------------------
    error = VaultColors.Error,
    onError = VaultColors.Background,

    errorContainer = Color(0xFF5C2525),
    onErrorContainer = Color(0xFFFFDAD6),

    // -------------------------------------------------------------------------
    // Outlines / borders
    // -------------------------------------------------------------------------
    outline = VaultColors.Outline,
    outlineVariant = VaultColors.OutlineVariant,

    // -------------------------------------------------------------------------
    // Inverse colors
    // -------------------------------------------------------------------------
    inverseSurface = VaultColors.TextPrimary,
    inverseOnSurface = VaultColors.Background,
    inversePrimary = Color(0xFF4A4A4A),
)

@Composable
fun ZenTenVaultTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
