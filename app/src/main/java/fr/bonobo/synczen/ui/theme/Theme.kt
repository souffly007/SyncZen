package fr.bonobo.synczen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class SyncThemeMode { LIGHT, DARK, CYANOGEN }

private val LightColors = lightColorScheme(
    primary = ZenGreen,
    background = ZenBackground,
    surface = ZenSurface,
    error = ZenError,
    onPrimary = Color.White,
    onBackground = Color(0xFF171B2D),
    onSurface = Color(0xFF171B2D)
)

private val DarkColors = darkColorScheme(
    primary = ZenGreenDark,
    background = ZenNightBackground,
    surface = ZenNightSurface,
    onBackground = ZenNightText,
    onSurface = ZenNightText,
    error = Color(0xFFFFB4AB)
)

private val CyanogenColors = darkColorScheme(
    primary = CyanogenOrange,
    secondary = CyanogenGreen,
    background = CyanogenBackground,
    surface = CyanogenSurface,
    onBackground = Color(0xFFF1F4DF),
    onSurface = Color(0xFFF1F4DF),
    error = Color(0xFFFFB4AB)
)

@Composable
fun SyncZenTheme(
    mode: SyncThemeMode = if (isSystemInDarkTheme()) SyncThemeMode.DARK else SyncThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val colors = when (mode) {
        SyncThemeMode.LIGHT -> LightColors
        SyncThemeMode.DARK -> DarkColors
        SyncThemeMode.CYANOGEN -> CyanogenColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = ZenTypography,
        content = content
    )
}
