package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val ForgeDarkColorScheme = darkColorScheme(
    primary = StudioEmeraldBright,
    onPrimary = Color(0xFF002117),
    primaryContainer = StudioEmeraldContainerDark,
    onPrimaryContainer = StudioEmeraldOnContainerDark,
    secondary = ForgeAmberSoft,
    onSecondary = Color(0xFF281300),
    secondaryContainer = ForgeAmberContainerDark,
    onSecondaryContainer = ForgeAmberOnContainerDark,
    tertiary = StudioCyan,
    onTertiary = Color(0xFF001F24),
    tertiaryContainer = StudioCyanContainerDark,
    onTertiaryContainer = StudioCyanOnContainerDark,
    background = ObsidianBg,
    onBackground = StudioTextPrimary,
    surface = ObsidianSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceElevated,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = ForgeErrorDark,
    onError = Color(0xFF2D0002),
    errorContainer = ForgeErrorContainerDark,
    onErrorContainer = ForgeOnErrorContainerDark
)

private val ForgeLightColorScheme = lightColorScheme(
    primary = LightEmerald,
    onPrimary = Color.White,
    primaryContainer = LightEmeraldContainer,
    onPrimaryContainer = Color(0xFF022C22),
    secondary = LightAmber,
    onSecondary = Color.White,
    secondaryContainer = LightAmberContainer,
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = LightCyan,
    onTertiary = Color.White,
    tertiaryContainer = LightCyanContainer,
    onTertiaryContainer = Color(0xFF083344),
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E8F0)
)

val ForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ApkForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ForgeDarkColorScheme
        else -> ForgeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = ForgeShapes,
        content = content
    )
}
