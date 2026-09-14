package dev.zxeb.ccusage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.zxeb.ccusage.ui.glass.theme.LocalGlassDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题。
 *
 * 使用 [ColorSchemeMode.MonetSystem] 走澎湃 / Material You 动态取色，
 * 跟随系统深浅色；设备不支持动态取色时 Miuix 自动回退到内置配色。
 *
 * 同时向液态玻璃组件提供 [LocalGlassDarkTheme]：玻璃需要知道深浅色来定
 * pill 的实色降级底色与投影 alpha，这两件事主题系统表达不了。
 * 这里用 [isSystemInDarkTheme] 判断，因为当前只跑 MonetSystem 一种取色模式。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    val darkTheme = isSystemInDarkTheme()
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalGlassDarkTheme provides darkTheme, content = content)
    }
}
