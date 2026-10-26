package com.luca.classmanager.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.luca.classmanager.domain.model.ThemeMode

private val Sage = Color(0xFF7A9E8E)
private val Cream = Color(0xFFFAF8F3)
private val Accent = Color(0xFFE9A56B)
private val Warning = Color(0xFFD95C59)
private val Purple = Color(0xFF8C8CC7)

private val LightColors = lightColorScheme(
    primary = Sage,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8DF),
    onPrimaryContainer = Color(0xFF1B3A2E),
    secondary = Purple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4F7),
    onSecondaryContainer = Color(0xFF2C2C55),
    tertiary = Accent,
    onTertiary = Color(0xFF3E270C),
    tertiaryContainer = Color(0xFFFFE3C8),
    onTertiaryContainer = Color(0xFF4A2E12),
    error = Warning,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD7),
    onErrorContainer = Color(0xFF410004),
    background = Cream,
    onBackground = Color(0xFF1F1E1B),
    surface = Color(0xFFFFFCF8),
    onSurface = Color(0xFF1F1E1B),
    surfaceVariant = Color(0xFFF0EBE3),
    onSurfaceVariant = Color(0xFF5C574E),
    outline = Color(0xFFD9D1C5),
    outlineVariant = Color(0xFFE6DFD4),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7B8),
    onPrimary = Color(0xFF143028),
    primaryContainer = Color(0xFF3E5C50),
    onPrimaryContainer = Color(0xFFD7E8DF),
    secondary = Color(0xFFC5C5EA),
    onSecondary = Color(0xFF2A2A4E),
    secondaryContainer = Color(0xFF45456E),
    onSecondaryContainer = Color(0xFFE4E4F7),
    tertiary = Color(0xFFF0C093),
    onTertiary = Color(0xFF3E270C),
    tertiaryContainer = Color(0xFF6A4A2A),
    onTertiaryContainer = Color(0xFFFFE3C8),
    error = Color(0xFFFFB4AE),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C2F2E),
    onErrorContainer = Color(0xFFFFDAD7),
    background = Color(0xFF161513),
    onBackground = Color(0xFFF3EFE6),
    surface = Color(0xFF211F1C),
    onSurface = Color(0xFFF3EFE6),
    surfaceVariant = Color(0xFF2C2925),
    onSurfaceVariant = Color(0xFFD0C8BC),
    outline = Color(0xFF948C82),
    outlineVariant = Color(0xFF3C3833),
    surfaceTint = Color.Transparent,
)

val LucaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun LucaTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        shapes = LucaShapes,
        content = content,
    )
}

fun parseCourseColor(hex: String): Color {
    val clean = hex.trim().removePrefix("#")
    val value = clean.toLongOrNull(16) ?: return Sage
    return when (clean.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> Sage
    }
}
