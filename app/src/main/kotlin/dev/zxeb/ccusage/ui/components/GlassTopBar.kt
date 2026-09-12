package dev.zxeb.ccusage.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

private val MENU_SHAPE = RoundedCornerShape(18.dp)
private val MENU_ITEM_HEIGHT = 42.dp

/**
 * HyperOS 风格的锚点弹出菜单：从触发按钮的右上角缩放展开，
 * 圆角卡片，收起时反向缩回。
 *
 * 必须放在触发按钮的同一父 Box 内 —— Popup 的 alignment=TopEnd + 向下偏移
 * 使它恰好出现在按钮下方右对齐的位置。
 *
 * @param anchorBounds 触发按钮在窗口坐标系中的 bounds（由 onGloballyPositioned 提供的状态）。
 */
@Composable
fun HyperMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: Rect?,
    items: List<String>,
    onItemClick: (Int) -> Unit,
) {
    if (!expanded) return
    anchorBounds ?: return

    val density = LocalDensity.current
    Popup(
        alignment = Alignment.TopEnd,
        // HyperMenu 放在根 Box（铺满窗口）里，父布局原点即窗口原点：
        // y 用锚点按钮的窗口 bottom 直接算向下偏移（按钮正下方），
        // x 留出与按钮行一致的右缘边距
        offset = IntOffset(
            x = -with(density) { 16.dp.roundToPx() },
            y = anchorBounds.bottom.toInt() + 12,
        ),
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
    ) {
        val enter = expandIn(
            expandFrom = Alignment.TopEnd,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        ) + fadeIn(tween(120))
        val exit = shrinkOut(
            shrinkTowards = Alignment.TopEnd,
            animationSpec = tween(160),
        ) + fadeOut(tween(140))

        Column(
            modifier = Modifier
                .width(160.dp)
                .clip(MENU_SHAPE)
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.97f))
                .border(0.5.dp, glassHairline(), MENU_SHAPE)
                .padding(vertical = 4.dp),
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
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
