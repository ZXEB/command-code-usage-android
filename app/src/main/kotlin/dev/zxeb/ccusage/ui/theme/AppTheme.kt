package dev.zxeb.ccusage.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题。
 *
 * 使用 [ColorSchemeMode.MonetSystem] 走澎湃 / Material You 动态取色，
 * 跟随系统深浅色；设备不支持动态取色时 Miuix 自动回退到内置配色。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    MiuixTheme(controller = controller, content = content)
}
