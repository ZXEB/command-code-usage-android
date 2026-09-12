package dev.zxeb.ccusage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃底栏（澎湃 4 / HyperOS 风格）。
 *
 * 做法：底栏本身不画不透明背景，而是通过 [drawBackdrop] 实时采样页面内容做高斯模糊 +
 * 颜色/饱和度混合（玻璃折射感），再用 [Highlight] 的玻璃描边预设加一圈会随倾斜偏移的边缘高光。
 *
 * 关键约束（来自 miuix-blur 官方文档）：所有效果都依赖 `RuntimeShader`，Android 上需要
 * API 33+。因此这里用 [isRuntimeShaderSupported] 门控，不支持时自动降级为不透明胶囊底栏，
 * 能力上不做任何伪装。
 *
 * @param backdrop 由页面内容容器通过 `Modifier.layerBackdrop` 录制、这里采样。
 */
@Composable
fun GlassBottomBar(
    backdrop: Backdrop,
    items: List<NavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val supported = isRuntimeShaderSupported()
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(28.dp)

    val density = LocalDensity.current
    val blurPx = with(density) { 44.dp.toPx() }

    val glassColors = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(
                color = if (dark) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.White.copy(alpha = 0.34f)
                },
                mode = BlurBlendMode.SrcOver,
            ),
        ),
        brightness = if (dark) 0.02f else 0.06f,
        contrast = 1.06f,
        saturation = 1.45f,
    )
    val glassHighlight = remember(dark) {
        if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    }

    val glassModifier = if (supported) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(radiusX = blurPx, radiusY = blurPx)
                noiseDither(BlurDefaults.NoiseCoefficient)
                blendColors(glassColors)
            },
            highlight = { glassHighlight },
        )
    } else {
        Modifier
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
            .then(glassModifier),
    ) {
        FloatingNavigationBar(
            color = Color.Transparent,
            cornerRadius = 28.dp,
            horizontalOutSidePadding = 6.dp,
            showDivider = false,
            defaultWindowInsetsPadding = false,
        ) {
            items.forEachIndexed { index, item ->
                FloatingNavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    icon = item.icon,
                    label = item.label,
                )
            }
        }
    }
}
