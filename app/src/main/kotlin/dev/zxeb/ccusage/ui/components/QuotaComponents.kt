package dev.zxeb.ccusage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zxeb.ccusage.model.Format
import dev.zxeb.ccusage.model.RateWindow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant

/** 占位符。数据缺失时界面必须显示它，而不是 0。 */
const val PLACEHOLDER = "--"

/**
 * 单个用量窗口卡片：标题 + 百分比 + 进度条 + 剩余额度 + 重置倒计时。
 *
 * 进度条颜色随占用率变化（对齐官方 CLI）：<70% 绿 / ≥70% 黄 / ≥90% 红。
 * 数据不全时进度条走「不确定」态，并显示 `--`，**不画成 0%**（那会误导用户以为没用）。
 */
@Composable
fun WindowCard(
    window: RateWindow,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val percent = window.percent
    val barColor = utilizationColor(percent)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = window.label,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                    if (window.derived) {
                        Text(
                            text = "  按周期推算",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Text(
                    text = Format.percent(percent),
                    style = MiuixTheme.textStyles.subtitle,
                    color = if (percent == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else barColor,
                )
            }

            LinearProgressIndicator(
                progress = percent?.let { (it / 100.0).toFloat() },
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = barColor,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainerVariant,
                ),
                height = ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildString {
                        append("剩余 ")
                        append(Format.usd(window.remaining))
                        if (window.cap != null) {
                            append(" / ")
                            append(Format.usd(window.cap))
                        }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = buildString {
                        append(Format.duration(window.resetAt, now))
                        append("后重置")
                        if (window.resetAt != null) {
                            append("（")
                            append(Format.resetAt(window.resetAt, now))
                            append("）")
                        }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            if (window.exceeded) {
                Text(
                    text = "⚠ 该窗口已超限",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 占用率配色，与官方 CLI 的阈值一致。 */
@Composable
fun utilizationColor(percent: Double?): Color = when {
    percent == null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    percent >= 90.0 -> MiuixTheme.colorScheme.error
    percent >= 70.0 -> WarningAmber
    else -> OkGreen
}

/** 预警黄。Miuix 配色表里没有语义化的 warning，这里取澎湃的提示色。 */
val WarningAmber = Color(0xFFF0A020)

/** 正常绿。 */
val OkGreen = Color(0xFF2FA84F)

/**
 * 「标签 / 值」一行。用于明细页的键值列表。
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.main,
            color = valueColor,
        )
    }
}
