package dev.zxeb.ccusage.ui.glass.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the app is currently rendered in dark mode.
 *
 * The glass components need this for two decisions the theme system cannot express:
 * the pill's flat tint (black on light, white on dark) and the panel's drop-shadow
 * alpha. Provided once at the theme root; read via [isInDarkTheme].
 */
val LocalGlassDarkTheme = staticCompositionLocalOf { false }

/**
 * Reads [LocalGlassDarkTheme].
 *
 * 必须标 `@Composable`：`CompositionLocal.current` 本身是 `@Composable @ReadOnlyComposable`
 * 的取值器，普通函数里读它会报 "Functions which invoke @Composable functions must be
 * marked with the @Composable annotation"（上游给的这份源码就漏了这个标注）。
 * 加上 `@ReadOnlyComposable` 表明它只读、不引入重组作用域。
 */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = LocalGlassDarkTheme.current
