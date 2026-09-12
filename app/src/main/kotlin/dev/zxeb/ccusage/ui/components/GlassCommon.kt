package dev.zxeb.ccusage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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

/** 玻璃表面的一圈高光亮线（深浅色各一档）。 */
@Composable
internal fun glassHairline(dark: Boolean = isSystemInDarkTheme()): Color =
    if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.55f)

/**
 * 统一的液态玻璃配方：底栏胶囊、顶栏圆钮共用，保证全应用同一种玻璃质感。
 *
 * 实时采样 [backdrop]（页面内容通过 `Modifier.layerBackdrop` 录制）做高斯模糊，
 * 叠提亮 + 轻微提饱和的颜色混合模拟玻璃折射，再加噪点抖动与一圈玻璃描边高光。
 *
 * 参数贴近 Miuix 官方默认（`BlurDefaults.BlurRadius = 20f`、`saturation = 1f`）。
 * 之前用 50dp 模糊 + 1.55 饱和，玻璃会糊成一团灰斑、看不清背后的内容，
 * 反而不像玻璃；这里收敛到官方量级。
 *
 * 不支持 RuntimeShader 的设备降级为**半透明**底色（而不是不透明色块）：
 * 完全不透明会让顶栏/底栏看起来像一块实心灰板，「没有毛玻璃」的观感就是这么来的。
 */
@Composable
internal fun Modifier.glassSurface(
    backdrop: Backdrop,
    shape: Shape,
    blurDp: Float = 24f,
): Modifier {
    val dark = isSystemInDarkTheme()
    if (!isRuntimeShaderSupported()) {
        return clip(shape).background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f))
    }
    val blurPx = with(LocalDensity.current) { blurDp.dp.toPx() }
    val glassColors = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(
                color = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.42f),
                mode = BlurBlendMode.SrcOver,
            ),
        ),
        brightness = if (dark) 0.03f else 0.05f,
        contrast = 1.02f,
        saturation = 1.2f,
    )
    val glassHighlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(radiusX = blurPx, radiusY = blurPx)
            noiseDither(BlurDefaults.NoiseCoefficient)
            blendColors(glassColors)
        },
        highlight = { glassHighlight },
    )
}
