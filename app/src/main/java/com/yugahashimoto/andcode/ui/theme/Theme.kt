package com.yugahashimoto.andcode.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val LocalThemeColors = staticCompositionLocalOf { DarkTheme }

enum class AppTheme(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    ZINC("zinc"),
    MIDNIGHT("midnight"),
    CLAUDE("claude"),
    GHOSTTY("ghostty"),
    AUTO("auto"),

    /** Material You: colors derived from the wallpaper on Android 12+, AUTO elsewhere. */
    DYNAMIC("dynamic"),
    ;

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: DARK
    }
}

fun themeColorsFor(
    theme: AppTheme,
    systemDark: Boolean,
): ThemeColors =
    when (theme) {
        AppTheme.DARK -> DarkTheme
        AppTheme.LIGHT -> LightTheme
        AppTheme.ZINC -> ZincTheme
        AppTheme.MIDNIGHT -> MidnightTheme
        AppTheme.CLAUDE -> ClaudeTheme
        AppTheme.GHOSTTY -> GhosttyTheme
        AppTheme.AUTO, AppTheme.DYNAMIC -> if (systemDark) DarkTheme else LightTheme
    }

fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Maps a wallpaper-derived Material scheme onto the app's own palette slots. Status colors, diff
 * tints and the success/warning hues stay from [base] on purpose: they carry meaning (running,
 * error, added, deleted) that must not shift with whatever the wallpaper happens to be.
 */
internal fun themeColorsFromScheme(
    scheme: ColorScheme,
    base: ThemeColors,
): ThemeColors =
    base.copy(
        surface0 = scheme.background,
        surface1 = scheme.surfaceContainerLow,
        surface2 = scheme.surfaceContainer,
        surface3 = scheme.surfaceContainerHigh,
        surface4 = scheme.surfaceContainerHighest,
        surfaceSidebar = scheme.surfaceContainerLowest,
        foreground = scheme.onSurface,
        foregroundMuted = scheme.onSurfaceVariant,
        foregroundExtraMuted = scheme.outline,
        border = scheme.outlineVariant,
        borderAccent = scheme.outline,
        accent = scheme.primary,
        accentBright = scheme.inversePrimary,
        accentForeground = scheme.onPrimary,
        secondary = scheme.tertiary,
        destructive = scheme.error,
    )

private fun buildColorScheme(
    tc: ThemeColors,
    isDark: Boolean,
) = if (isDark) {
    darkColorScheme(
        primary = tc.accent,
        onPrimary = tc.accentForeground,
        secondary = tc.secondary,
        onSecondary = tc.surface0,
        background = tc.surface0,
        onBackground = tc.foreground,
        surface = tc.surface1,
        onSurface = tc.foreground,
        surfaceVariant = tc.surface2,
        onSurfaceVariant = tc.foregroundMuted,
        outline = tc.border,
        error = tc.destructive,
        onError = tc.surface0,
        primaryContainer = tc.accent.copy(alpha = 0.14f),
        onPrimaryContainer = tc.accent,
        secondaryContainer = tc.secondary.copy(alpha = 0.14f),
        onSecondaryContainer = tc.secondary,
        // The palettes have no dedicated third hue; leaving these undefined let the Material
        // baseline purple leak through components like the queued chip in every theme.
        tertiary = tc.secondary,
        onTertiary = tc.surface0,
        tertiaryContainer = tc.secondary.copy(alpha = 0.14f),
        onTertiaryContainer = tc.secondary,
        errorContainer = tc.destructive.copy(alpha = 0.14f),
        onErrorContainer = tc.destructive,
        surfaceContainerLowest = tc.surface0,
        surfaceContainerLow = tc.surface1,
        surfaceContainer = tc.surface1,
        surfaceContainerHigh = tc.surface2,
        surfaceContainerHighest = tc.surface3,
        surfaceBright = tc.surface2,
        surfaceDim = tc.surface0,
        outlineVariant = tc.border,
        inverseSurface = tc.surface4,
        inverseOnSurface = tc.foreground,
        inversePrimary = tc.accentBright,
        scrim = tc.surface0.copy(alpha = 0.6f),
    )
} else {
    lightColorScheme(
        primary = tc.accent,
        onPrimary = tc.accentForeground,
        secondary = tc.secondary,
        onSecondary = tc.surface0,
        background = tc.surface0,
        onBackground = tc.foreground,
        surface = tc.surface1,
        onSurface = tc.foreground,
        surfaceVariant = tc.surface2,
        onSurfaceVariant = tc.foregroundMuted,
        outline = tc.border,
        error = tc.destructive,
        onError = tc.surface0,
        primaryContainer = tc.accent.copy(alpha = 0.14f),
        onPrimaryContainer = tc.accent,
        secondaryContainer = tc.secondary.copy(alpha = 0.14f),
        onSecondaryContainer = tc.secondary,
        tertiary = tc.secondary,
        onTertiary = tc.surface0,
        tertiaryContainer = tc.secondary.copy(alpha = 0.14f),
        onTertiaryContainer = tc.secondary,
        errorContainer = tc.destructive.copy(alpha = 0.14f),
        onErrorContainer = tc.destructive,
        surfaceContainerLowest = tc.surface1,
        surfaceContainerLow = tc.surface1,
        surfaceContainer = tc.surface1,
        surfaceContainerHigh = tc.surface2,
        surfaceContainerHighest = tc.surface3,
        surfaceBright = tc.surface1,
        surfaceDim = tc.surface4,
        outlineVariant = tc.border,
        // A light theme inverts against its dark foreground, not against another light surface;
        // tooltips and overlays otherwise rendered as washed-out light gray on light gray.
        inverseSurface = tc.foreground,
        inverseOnSurface = tc.surface0,
        inversePrimary = tc.accent,
        scrim = tc.surface0.copy(alpha = 0.6f),
    )
}

fun buildTypography(
    uiFontSize: Int = 16,
    codeFontSize: Int = 12,
): Typography {
    val scale = uiFontSize / 16f
    val base = Typography()

    // Material's default tracking (0.5sp on body and label text) was tuned for sparse consumer
    // screens; in a dense developer tool it makes paragraphs look loose and labels look shouty.
    // Tighter tracking, slightly taller body lines and heavier titles give the calmer, denser read
    // of desktop agent apps without shrinking anything.
    fun TextStyle.sized(
        size: Float,
        line: Float,
        tracking: Float,
        weight: FontWeight? = null,
    ) = copy(
        fontSize = (size * scale).sp,
        lineHeight = (line * scale).sp,
        letterSpacing = tracking.sp,
        fontWeight = weight ?: fontWeight,
    )
    return Typography(
        displayLarge = base.displayLarge.sized(57f, 64f, -0.25f),
        displayMedium = base.displayMedium.sized(45f, 52f, 0f),
        displaySmall = base.displaySmall.sized(36f, 44f, 0f),
        headlineLarge = base.headlineLarge.sized(30f, 38f, -0.2f, FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.sized(26f, 34f, -0.2f, FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.sized(22f, 30f, -0.1f, FontWeight.SemiBold),
        titleLarge = base.titleLarge.sized(20f, 28f, -0.1f, FontWeight.SemiBold),
        titleMedium = base.titleMedium.sized(16f, 22f, 0f, FontWeight.SemiBold),
        titleSmall = base.titleSmall.sized(14f, 20f, 0f, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.sized(16f, 25f, 0.1f),
        bodyMedium = base.bodyMedium.sized(14f, 21f, 0.1f),
        bodySmall = base.bodySmall.sized(12f, 17f, 0.15f),
        labelLarge = base.labelLarge.sized(14f, 20f, 0.05f, FontWeight.Medium),
        labelMedium = base.labelMedium.sized(12f, 16f, 0.2f, FontWeight.Medium),
        labelSmall = base.labelSmall.sized(11f, 16f, 0.25f, FontWeight.Medium),
    )
}

@Composable
fun AndCodeTheme(
    appTheme: AppTheme = AppTheme.DARK,
    uiFontSize: Int = 16,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val followsSystem = appTheme == AppTheme.AUTO || appTheme == AppTheme.DYNAMIC
    val isDark = appTheme != AppTheme.LIGHT && (!followsSystem || systemDark)
    val dynamicScheme =
        if (appTheme == AppTheme.DYNAMIC && supportsDynamicColor()) {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    val baseColors = themeColorsFor(appTheme, systemDark)
    val tc = dynamicScheme?.let { themeColorsFromScheme(it, baseColors) } ?: baseColors
    val colorScheme = buildColorScheme(tc, isDark)
    val typography = buildTypography(uiFontSize)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = tc.surface0.toArgb()
            window.navigationBarColor = tc.surface0.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalThemeColors provides tc,
        LocalContentColor provides colorScheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

@Composable
fun AndCodeAssistantTheme(content: @Composable () -> Unit) = AndCodeTheme(content = content)
