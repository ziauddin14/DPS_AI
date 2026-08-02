package com.softwaremine.dps.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Application theme.
 *
 * ## Purpose
 * Material 3 colour, honouring the system light/dark setting and Android 12+
 * dynamic colour.
 *
 * ## Design intent
 * Restrained on purpose. `user_journey.md` describes a CEO using this while
 * driving, between meetings, and last thing at night — a tool consulted in
 * seconds, not an app to be admired. The palette stays close to system
 * defaults so DPS reads as part of the phone rather than as a destination.
 *
 * Dynamic colour is enabled where available for the same reason: matching the
 * user's wallpaper-derived system palette makes the app feel native rather than
 * branded at them.
 *
 * ## Future extensions
 * Day 05 voice work adds an accent for the listening state, which must be
 * distinguishable without relying on hue alone — the driving scenario means it
 * will be read at a glance and often in bright sunlight.
 *
 * ## Dependencies
 * Compose Material 3.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FCAFF),
    onPrimary = Color(0xFF00325B),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
)

@Composable
fun DpsTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        useDynamicColor && supportsDynamicColor && useDarkTheme ->
            dynamicDarkColorScheme(context)

        useDynamicColor && supportsDynamicColor ->
            dynamicLightColorScheme(context)

        useDarkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Only the icon appearance is set here.
            //
            // `window.statusBarColor` is deprecated from API 35 and is a no-op
            // under edge-to-edge, which MainActivity enables via
            // `enableEdgeToEdge()`. The bar is transparent by design and the
            // app's own surface shows through it, so the only thing left to
            // control is whether the system icons are drawn dark or light.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
