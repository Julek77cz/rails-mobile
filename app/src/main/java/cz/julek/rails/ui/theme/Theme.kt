package cz.julek.rails.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = RailsPrimary,
    onPrimary = RailsOnPrimary,
    secondary = RailsSecondary,
    onSecondary = RailsOnSecondary,
    tertiary = RailsTertiary,
    onTertiary = RailsOnTertiary,
    error = RailsError,
    onError = RailsOnError,
    background = RailsBackgroundLight,
    surface = RailsSurfaceLight,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    surfaceContainer = Color(0xFFF0F0F3),
    surfaceContainerLow = Color(0xFFF6F6F9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBFC6FF),       // Lighter indigo for dark mode
    onPrimary = Color(0xFF00005E),
    secondary = Color(0xFFFFB74D),     // Lighter amber
    onSecondary = Color(0xFF452B00),
    tertiary = Color(0xFF4DB6AC),      // Lighter teal
    onTertiary = Color(0xFF003731),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF690005),
    background = RailsBackgroundDark,
    surface = RailsSurfaceDark,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    surfaceContainer = Color(0xFF1C2128),
    surfaceContainerLow = Color(0xFF161B22),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
)

@Composable
fun RailsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
