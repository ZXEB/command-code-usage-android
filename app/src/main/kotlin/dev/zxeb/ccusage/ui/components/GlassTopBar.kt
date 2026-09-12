package dev.zxeb.ccusage.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---------------- 按压反馈共享弹簧 ----------------

/** HyperOS 手感：按下轻微下压，松手轻弹性回弹。 */
internal val PressScaleSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 800f)

/**
 * 按压缩放反馈：按下时缩到 [pressedScale]，松手以弹簧回弹。
 * 底栏圆钮、顶栏圆钮共用，保证全应用一致的按压手感。
 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.92f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = PressScaleSpring,
        label = "pressScale",
    )
    return scale(scale)
}

// ---------------- 顶部玻璃圆钮 ----------------

private val TOP_BUTTON_SIZE = 44.dp
private val TOP_BUTTON_ICON = 22.dp

/**
 * 顶栏右侧的液态玻璃圆形按钮（澎湃 4 图库的调节/更多按钮形态）。
 *
 * 玻璃采样的是页面滚动内容，滚动时图标下的模糊实时变化；
 * 按下时整颗按钮轻微下压、松手弹簧回弹。
 */
@Composable
fun GlassTopButton(
    backdrop: Backdrop,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(TOP_BUTTON_SIZE)
            .pressScale(interaction)
            .glassSurface(backdrop, CircleShape)
            .border(0.5.dp, glassHairline(), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onSurfaceContainer,
            modifier = Modifier.size(TOP_BUTTON_ICON),
        )
    }
}

// ---------------- 锚点展开菜单 ----------------

/** 大圆角卡片：对齐澎湃 4 弹出的菜单观感（比普通卡片圆角更大）。 */
private val MENU_SHAPE = RoundedCornerShape(24.dp)

/** 行高给足。参考澎湃 4 的菜单，条目之间留白明显，不能挤在一起。 */
private val MENU_ITEM_HEIGHT = 52.dp

private val MENU_WIDTH = 200.dp

/**
 * 澎湃 4 风格的锚点弹出菜单。
 *
 * 形态对齐参考：**一张大圆角卡片，条目大字距、行高宽松、左对齐**，
 * 从触发按钮的右上角缩放展开，收起时反向缩回。
 *
 * 展开/收起动画用 [MutableTransitionState] 驱动 [AnimatedVisibility]：
 * 关键点是**退场期间不能把 Popup 从组合里摘掉**，否则收起动画没有机会播放
 * （之前写成 `if (!expanded) return`，所以只有出现动画、没有消失动画）。
 *
 * @param anchorBounds 触发按钮在窗口坐标系中的 bounds。
 */
@Composable
fun HyperMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect?,
    items: List<String>,
    onItemClick: (Int) -> Unit,
) {
    anchorBounds ?: return

    // 动画期间保持挂载：targetState 立刻跟随 expanded，currentState 要等动画结束才更新
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    // 完全不可见且不需要出现时才不渲染（此时退场动画已经播完）
    if (!visibleState.currentState && !visibleState.targetState) return

    val density = LocalDensity.current
    Popup(
        alignment = Alignment.TopEnd,
        // 父布局铺满窗口，原点即窗口原点：y 取锚点按钮底部再向下留一点，
        // x 与顶栏按钮行同一条右缘边距
        offset = IntOffset(
            x = -with(density) { 16.dp.roundToPx() },
            y = anchorBounds.bottom.toInt() + 12,
        ),
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 700f),
                transformOrigin = TransformOrigin(1f, 0f),
                initialScale = 0.82f,
            ) + fadeIn(tween(110)),
            exit = scaleOut(
                animationSpec = tween(170),
                transformOrigin = TransformOrigin(1f, 0f),
                targetScale = 0.88f,
            ) + fadeOut(tween(150)),
        ) {
            Column(
                modifier = Modifier
                    .width(MENU_WIDTH)
                    .shadow(12.dp, MENU_SHAPE)
                    .clip(MENU_SHAPE)
                    .background(MiuixTheme.colorScheme.surface)
                    .border(0.5.dp, glassHairline(), MENU_SHAPE)
                    .padding(vertical = 8.dp),
            ) {
                items.forEachIndexed { index, label ->
                    val itemInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MENU_ITEM_HEIGHT)
                            .pressScale(itemInteraction, pressedScale = 0.97f)
                            .clickable(
                                interactionSource = itemInteraction,
                                indication = null,
                            ) {
                                onItemClick(index)
                                onDismiss()
                            }
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
