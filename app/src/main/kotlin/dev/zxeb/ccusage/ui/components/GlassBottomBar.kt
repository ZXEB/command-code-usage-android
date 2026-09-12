package dev.zxeb.ccusage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
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
 * 形态对齐 HyperOS 4 的底栏：**左侧一颗悬浮胶囊放导航项 + 右侧一颗独立圆形按钮**。
 * 胶囊由 [Modifier.drawBackdrop] 实时采样页面内容做高斯模糊，叠一层颜色混合得到玻璃折射感，
 * 再用 [Highlight] 的玻璃描边预设加一圈边缘高光。
 *
 * 为什么不直接用 Miuix 的 `FloatingNavigationBar`：它的内部修饰符顺序是
 * `.padding(bottom = 36.dp)` → `.dropShadow(...)` → `.squircleBackground(color)`。
 * 当 `color = Color.Transparent` 时，`dropShadow`（`shadowElevation` 默认 1.dp）会在
 * **全透明背景上画出一层黑色投影**，糊成一块灰色脏斑；同时那 36dp 底部内边距落在背景之外，
 * 底栏底部会露出一条浅带。这两个行为叠加正是之前的显示异常。
 * 这里自己控制几何与配色，行为完全可预期。
 *
 * 交互与无障碍仍沿用 Miuix 的做法：`selectable` + `Role.Tab` / `Role.Button`，文字与图标用
 * Miuix 的 [Text] / [Icon] 与 [MiuixTheme]。
 *
 * @param backdrop 由页面内容容器通过 `Modifier.layerBackdrop` 录制、这里采样。
 * @param actionIcon 右侧独立圆形按钮的图标（本应用用作「刷新」）。
 */
@Composable
fun GlassBottomBar(
    backdrop: Backdrop,
    items: List<NavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    actionIcon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
) {
    val supported = isRuntimeShaderSupported()
    val dark = isSystemInDarkTheme()
    val pillShape = RoundedCornerShape(percent = 50)
    val circleShape = CircleShape

    val blurPx = with(LocalDensity.current) { GLASS_BLUR_DP.dp.toPx() }

    // 玻璃合成：提亮 + 提饱和，模拟玻璃对背景的折射与聚色
    val glassColors = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(
                color = if (dark) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    Color.White.copy(alpha = 0.52f)
                },
                mode = BlurBlendMode.SrcOver,
            ),
        ),
        brightness = if (dark) 0.03f else 0.05f,
        contrast = 1.05f,
        saturation = 1.55f,
    )
    val glassHighlight = remember(dark) {
        if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    }

    // Highlight 负责随倾斜偏移的内高光；再补一道极细实边，
    // 让玻璃在浅色壁纸上也有清晰轮廓（HyperOS 的玻璃边缘就是这个观感）。
    val hairline = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.55f)
    val fallbackColor = MiuixTheme.colorScheme.surfaceContainer
    val iconColor = MiuixTheme.colorScheme.onSurfaceContainer
    val selectedPillColor = if (dark) {
        Color.White.copy(alpha = 0.20f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    fun glass(shape: Shape): Modifier =
        if (supported) {
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
            Modifier.clip(shape).background(fallbackColor)
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BAR_HORIZONTAL_PADDING, vertical = BAR_BOTTOM_PADDING),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---------------- 导航胶囊 ----------------
        Row(
            modifier = Modifier
                .height(PILL_HEIGHT)
                .then(glass(pillShape))
                .border(0.5.dp, hairline, pillShape)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEachIndexed { index, item ->
                GlassNavItem(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    iconColor = iconColor,
                    selectedPillColor = selectedPillColor,
                )
            }
        }

        // ---------------- 独立圆形按钮 ----------------
        Box(
            modifier = Modifier
                .padding(start = ACTION_GAP)
                .size(PILL_HEIGHT)
                .then(glass(circleShape))
                .border(0.5.dp, hairline, circleShape)
                .selectable(
                    selected = false,
                    enabled = actionEnabled,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onAction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = actionIcon,
                contentDescription = actionLabel,
                tint = if (actionEnabled) iconColor else iconColor.copy(alpha = 0.4f),
                modifier = Modifier.size(ACTION_ICON_SIZE),
            )
        }
    }
}

/**
 * 单个导航项。选中时在图标 + 文字背后垫一颗浅色胶囊 —— 这是 HyperOS 底栏最显著的识别特征。
 */
@Composable
private fun GlassNavItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    iconColor: Color,
    selectedPillColor: Color,
) {
    val shape = RoundedCornerShape(percent = 50)
    Column(
        modifier = Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(selectedPillColor) else Modifier)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
            .padding(horizontal = ITEM_HORIZONTAL_PADDING, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (selected) iconColor else iconColor.copy(alpha = 0.55f),
            modifier = Modifier.size(NAV_ICON_SIZE),
        )
        Text(
            text = item.label,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) iconColor else iconColor.copy(alpha = 0.55f),
        )
    }
}

// ---------------- 尺寸常量（对齐 HyperOS 4 底栏比例） ----------------

/** 胶囊与圆形按钮的统一高度，圆角取一半即为全圆。 */
private val PILL_HEIGHT = 54.dp

/** 底栏内容距屏幕左右边缘的留白。 */
private val BAR_HORIZONTAL_PADDING = 16.dp

/** 底栏距手势条的距离。 */
private val BAR_BOTTOM_PADDING = 10.dp

/** 胶囊与圆形按钮之间的间距。 */
private val ACTION_GAP = 10.dp

private val NAV_ICON_SIZE = 22.dp
private val ACTION_ICON_SIZE = 24.dp
private val ITEM_HORIZONTAL_PADDING = 15.dp

/** 玻璃模糊半径。越大越「厚」，HyperOS 的底栏属于偏厚的一档。 */
private const val GLASS_BLUR_DP = 50f
