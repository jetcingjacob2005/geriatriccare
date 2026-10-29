package com.geriatricare.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary            = CyanAccent,
    onPrimary          = Navy900,
    primaryContainer   = Navy600,
    onPrimaryContainer = CyanLight,
    secondary          = SpO2Purple,
    onSecondary        = White,
    secondaryContainer = Navy500,
    onSecondaryContainer = SpO2Light,
    tertiary           = TempOrange,
    onTertiary         = Navy900,
    background         = Navy900,
    onBackground       = White,
    surface            = Navy700,
    onSurface          = White,
    surfaceVariant     = Navy600,
    onSurfaceVariant   = Gray300,
    error              = AlertRed,
    onError            = White,
    outline            = CardBorder
)

private val ClinicalShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun GeriatriCareTheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Navy900.toArgb()
            window.navigationBarColor = Navy900.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        shapes      = ClinicalShapes,
        content     = content
    )
}
