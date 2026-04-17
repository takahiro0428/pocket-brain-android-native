package com.tsunaguba.corechat.ui.theme

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

// Dark-mode surface constants — kept internal to this file.
private val DarkBackground = Color(0xFF0F0F10)
private val DarkSurface = Color(0xFF17181A)
private val DarkSurfaceVariant = Color(0xFF24262B)

private val LightScheme = lightColorScheme(
    primary = AccentPrimary,
    onPrimary = AccentOnPrimary,
    background = BackgroundDefault,
    onBackground = TextPrimary,
    surface = SurfaceDefault,
    onSurface = TextPrimary,
    surfaceVariant = BubbleAi,
    onSurfaceVariant = TextSecondary,
)

private val DarkScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = AccentOnPrimary,
    background = DarkBackground,
    onBackground = SurfaceDefault,
    surface = DarkSurface,
    onSurface = SurfaceDefault,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = BubbleAi,
)

@Composable
fun CoreChatTheme(
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CoreChatTypography,
        content = content,
    )
}
