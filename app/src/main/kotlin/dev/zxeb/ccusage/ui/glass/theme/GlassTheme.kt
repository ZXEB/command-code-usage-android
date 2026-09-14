package dev.zxeb.ccusage.ui.glass.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the app is currently rendered in dark mode.
 *
 * The glass components need this for two decisions the theme system cannot express:
 * the pill's flat tint (black on light, white on dark) and the panel's drop-shadow
 * alpha. Provided once at the theme root; read via [isInDarkTheme].
 */
val LocalGlassDarkTheme = staticCompositionLocalOf { false }

fun isInDarkTheme(): Boolean = LocalGlassDarkTheme.current
