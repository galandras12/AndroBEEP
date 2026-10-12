package com.galandras12.droidbeep.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.galandras12.droidbeep.data.ThemeMode

private val HoneyLight = lightColorScheme(
    primary = Color(0xFF7E5700),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEAB),
    onPrimaryContainer = Color(0xFF281800),
    secondary = Color(0xFF6D5C3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E0BB),
    onSecondaryContainer = Color(0xFF251A04),
    tertiary = Color(0xFF4F6543),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1EABF),
    onTertiaryContainer = Color(0xFF0D2005),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF211B13),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF211B13),
    surfaceVariant = Color(0xFFEFE0CF),
    onSurfaceVariant = Color(0xFF4F4538),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF2E8),
    surfaceContainer = Color(0xFFF5ECE2),
    surfaceContainerHigh = Color(0xFFEFE6DC),
    surfaceContainerHighest = Color(0xFFE9E1D6),
    outline = Color(0xFF817567),
    outlineVariant = Color(0xFFD3C4B4),
)

private val HoneyDark = darkColorScheme(
    primary = Color(0xFFFFB950),
    onPrimary = Color(0xFF442C00),
    primaryContainer = Color(0xFF614000),
    onPrimaryContainer = Color(0xFFFFDEAB),
    secondary = Color(0xFFD9C4A0),
    onSecondary = Color(0xFF3B2F15),
    secondaryContainer = Color(0xFF53452A),
    onSecondaryContainer = Color(0xFFF6E0BB),
    tertiary = Color(0xFFB6CEA5),
    onTertiary = Color(0xFF223619),
    tertiaryContainer = Color(0xFF384C2D),
    onTertiaryContainer = Color(0xFFD1EABF),
    background = Color(0xFF17130B),
    onBackground = Color(0xFFEBE1D4),
    surface = Color(0xFF17130B),
    onSurface = Color(0xFFEBE1D4),
    surfaceVariant = Color(0xFF4F4538),
    onSurfaceVariant = Color(0xFFD3C4B4),
    surfaceContainerLowest = Color(0xFF110D06),
    surfaceContainerLow = Color(0xFF201B13),
    surfaceContainer = Color(0xFF241F17),
    surfaceContainerHigh = Color(0xFF2F2921),
    surfaceContainerHighest = Color(0xFF3A342B),
    outline = Color(0xFF9C8F80),
    outlineVariant = Color(0xFF4F4538),
)

private val BeeShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)

@Composable
fun BeeTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> HoneyDark
        else -> HoneyLight
    }
    MaterialTheme(colorScheme = scheme, shapes = BeeShapes, content = content)
}
