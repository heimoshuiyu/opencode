package com.opencode.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = CarbonBg,
    primaryContainer = AmberContainer,
    onPrimaryContainer = Amber,
    secondary = Teal,
    onSecondary = CarbonBg,
    secondaryContainer = Color(0xFF0F3A36),
    onSecondaryContainer = Teal,
    tertiary = Teal,
    onTertiary = CarbonBg,
    error = CoralError,
    onError = CarbonBg,
    background = CarbonBg,
    onBackground = CarbonOnBg,
    surface = CarbonSurface,
    onSurface = CarbonOnSurface,
    surfaceVariant = CarbonSurfaceVariant,
    onSurfaceVariant = CarbonOnSurfaceVariant,
    outline = CarbonOutline,
    outlineVariant = CarbonOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = AmberLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AmberContainerLight,
    onPrimaryContainer = AmberLight,
    secondary = TealLight,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0F5F0),
    onSecondaryContainer = TealLight,
    tertiary = TealLight,
    onTertiary = Color(0xFFFFFFFF),
    error = CoralError,
    onError = Color(0xFFFFFFFF),
    background = CarbonBgLight,
    onBackground = CarbonOnBgLight,
    surface = CarbonSurfaceLight,
    onSurface = CarbonOnSurfaceLight,
    surfaceVariant = CarbonSurfaceVariantLight,
    onSurfaceVariant = CarbonOnSurfaceVariantLight,
    outline = CarbonOutlineLight,
    outlineVariant = CarbonOutlineLight,
)

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
