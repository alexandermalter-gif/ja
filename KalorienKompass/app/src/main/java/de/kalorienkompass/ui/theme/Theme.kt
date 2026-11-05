package de.kalorienkompass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import de.kalorienkompass.data.model.ThemeMode

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = OnMint,
    primaryContainer = MintContainerDark,
    onPrimaryContainer = OnMintContainerDark,
    inversePrimary = MintDark,
    secondary = Amber,
    onSecondary = OnAmber,
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = OnAmberContainerDark,
    tertiary = Sky,
    onTertiary = OnSky,
    tertiaryContainer = SkyContainerDark,
    onTertiaryContainer = OnSkyContainerDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = Mint,
    inverseSurface = DarkOnBackground,
    inverseOnSurface = DarkBackground,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkBackground,
    surfaceBright = DarkSurfaceHighest,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    surfaceContainerLow = DarkSurface,
    surfaceContainerLowest = DarkSurfaceLow,
    surfaceDim = DarkBackground,
)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = MintDark,
    onPrimary = OnMintLight,
    primaryContainer = MintContainerLight,
    onPrimaryContainer = OnMintContainerLight,
    inversePrimary = Mint,
    secondary = AmberLight,
    onSecondary = OnMintLight,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = OnAmberContainerLight,
    tertiary = SkyLight,
    onTertiary = OnMintLight,
    tertiaryContainer = SkyContainerLight,
    onTertiaryContainer = OnSkyContainerLight,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = MintDark,
    inverseSurface = LightOnBackground,
    inverseOnSurface = LightBackground,
    error = ErrorLight,
    onError = OnMintLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightOnBackground,
    surfaceBright = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainerLowest = LightSurface,
    surfaceDim = LightSurfaceHighest,
)

val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** True if [themeMode] results in a dark UI. */
@Composable
fun isAppInDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun KalorienTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(themeMode)
    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
