package dev.zxeb.ccusage.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃底栏（澎湃 4 / HyperOS 风格）。
 *
 * 形态对齐 HyperOS 4 底栏：**左侧一颗悬浮胶囊放导航项 + 右侧一颗独立圆形按钮**。
 *
 * 动画（对齐 HyperOS 4 的底栏切换）：
 * - **选中态是一颗会滑动的胶囊**，切换时从旧项平滑滑到新项，而不是各画各的；
 * - 图标与文字颜色做 [animateColorAsState] 过渡；
 * - 选中图标轻微放大（1.0 → 1.06），给一点"弹"的手感。
 * 时长/曲线与 Miuix 导航项保持一致（spring，质量偏轻），避免和库内其它动画打架。
 *
 * 玻璃：胶囊由 [Modifier.drawBackdrop] 实时采样页面内容做高斯模糊，叠颜色混合得到折射感，
 * 再用 [Highlight] 加一圈玻璃描边高光。
 *
 * 为什么不直接用 Miuix 的 `FloatingNavigationBar`：它的内部修饰符顺序是
 * `.padding(bottom = 36.dp)` → `.dropShadow(...)` → `.squircleBackground(color)`。
 * 当 `color = Color.Transparent` 时，`dropShadow`（`shadowElevation` 默认 1.dp）会在
 * **全透明背景上画出一层黑色投影**，糊成灰色脏斑；同时那 36dp 底部内边距落在背景之外，
 * 底栏下方会露出一条浅带。这里自己控制几何与配色，行为完全可预期。
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
    val dark = isSystemInDarkTheme()
    val pillShape = RoundedCornerShape(percent = 50)
    val circleShape = CircleShape

    val hairline = glassHairline(dark)
    val iconColor = MiuixTheme.colorScheme.onSurfaceContainer
    val selectedPillColor = if (dark) {
        Color.White.copy(alpha = 0.20f)
    } else {
        Color.White.copy(alpha = 0.78f)
    }

    @Composable
    fun glass(shape: Shape): Modifier = glassSurface(backdrop, shape)

    // 每个导航项在胶囊坐标系里的位置，用于让选中胶囊滑动过去
    val itemBounds = remember { mutableStateMapOf<Int, Rect>() }
    // 首帧布局完成前不要播放"从 0 滑过来"的入场动画
    var settled by remember { mutableStateOf(false) }
    val targetBounds = itemBounds[selectedIndex]
    LaunchedEffect(targetBounds != null) {
        if (targetBounds != null) settled = true
    }

    // 首帧布局完成前用 snap，避免播放"从左上角滑过来"的入场动画
    val spec: AnimationSpec<Dp> = if (settled) {
        spring(dampingRatio = 0.82f, stiffness = 900f)
    } else {
        snap()
    }

    val pillLeft by animateDpAsState(targetBounds?.left?.dp ?: 0.dp, spec, label = "pillLeft")
    val pillTop by animateDpAsState(targetBounds?.top?.dp ?: 0.dp, spec, label = "pillTop")
    val pillWidth by animateDpAsState(targetBounds?.width?.dp ?: 0.dp, spec, label = "pillWidth")
    val pillHeight by animateDpAsState(targetBounds?.height?.dp ?: 0.dp, spec, label = "pillHeight")

    // 液态挤压：滑动途中胶囊按"目标宽度-当前宽度"水平拉伸、垂直微缩，到位回弹。
    // 拉伸量由动画进度（当前值与目标值的差）实时推算，无需额外状态。
    val density = LocalDensity.current
    val stretchPx = with(density) {
        (pillWidth - (targetBounds?.width?.dp ?: 0.dp)).toPx().let { if (it < 0f) -it else it }
    }
    val maxStretchPx = with(density) { 8.dp.toPx() }
    val stretchFraction = (stretchPx / maxStretchPx).coerceIn(0f, 1f)
    val scaleX = 1f + stretchFraction * 0.14f
    val scaleY = 1f - stretchFraction * 0.06f

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
                // 滑动的高亮胶囊画在内容之下；滑动中做液态拉伸形变
                .drawBehind {
                    if (targetBounds == null) return@drawBehind
                    val left = pillLeft.toPx()
                    val top = pillTop.toPx()
                    val width = pillWidth.toPx()
                    val height = pillHeight.toPx()
                    if (width <= 0f || height <= 0f) return@drawBehind
                    val cx = left + width / 2f
                    val cy = top + height / 2f
                    val w = width * scaleX
                    val h = height * scaleY
                    withTransform({
                        scale(scaleX, scaleY, pivot = Offset(cx, cy))
                    }) {
                        drawRoundRect(
                            color = selectedPillColor,
                            topLeft = Offset(cx - w / 2f, cy - h / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h / 2f),
                        )
                    }
                }
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
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val bounds = coords.boundsInParent()
                        // 避免每帧都写状态造成无谓重组
                        if (itemBounds[index] != bounds) itemBounds[index] = bounds
                    },
                )
            }
        }

        // ---------------- 独立圆形按钮 ----------------
        val actionInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .padding(start = ACTION_GAP)
                .size(PILL_HEIGHT)
                .pressScale(actionInteraction, pressedScale = 0.9f)
                .then(glass(circleShape))
                .border(0.5.dp, hairline, circleShape)
                .selectable(
                    selected = false,
                    enabled = actionEnabled,
                    role = Role.Button,
                    interactionSource = actionInteraction,
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
 * 单个导航项。选中高亮由外层的滑动胶囊统一绘制，这里只负责图标/文字的状态动画，
 * 否则切换时会出现"旧胶囊瞬间消失、新胶囊瞬间出现"的跳变。
 */
@Composable
private fun GlassNavItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    iconColor: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val interaction = remember { MutableInteractionSource() }

    val contentColor by animateColorAsState(
        targetValue = if (selected) iconColor else iconColor.copy(alpha = 0.55f),
        animationSpec = tween(durationMillis = 220),
        label = "navItemColor",
    )
    // 选中：带过冲的弹一下；按下：轻微下压
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 800f),
        label = "navItemScale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (interaction.collectIsPressedAsState().value) 0.9f else 1f,
        animationSpec = PressScaleSpring,
        label = "navItemPress",
    )

    Column(
        modifier = modifier
            .clip(shape)
            .scale(pressScale)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
            )
            .padding(horizontal = ITEM_HORIZONTAL_PADDING, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            // 相邻文字已经念出名称，图标对 TalkBack 重复朗读没有意义
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(NAV_ICON_SIZE)
                .scale(iconScale),
        )
        Text(
            text = item.label,
            style = MiuixTheme.textStyles.footnote2,
            color = contentColor,
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
