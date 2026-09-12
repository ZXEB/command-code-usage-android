package dev.zxeb.ccusage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zxeb.ccusage.data.UiState
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.Format
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import dev.zxeb.ccusage.ui.components.KeyValueRow
import dev.zxeb.ccusage.ui.components.PLACEHOLDER
import dev.zxeb.ccusage.ui.components.WindowCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant

/**
 * 概览页：套餐 → 额度池 → 本期 token（大字）→ 三个窗口 → 元信息。
 */
@Composable
fun OverviewScreen(
    state: UiState,
    now: Instant,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 未配置引导
        if (state.needsSetup && snapshot == null) {
            SmallTitle("开始使用")
            SetupGuideCard(onOpenSettings)
            return@Column
        }

        // 刷新失败提示（有旧数据时明确标注）
        state.error?.let { message ->
            if (state.source == DataSource.STALE && snapshot != null) {
                StaleBanner(message, snapshot.fetchedAt)
            }
        }

        if (snapshot == null) {
            SmallTitle("用量")
            Card {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        text = if (state.refreshing) "正在读取套餐额度…" else "暂无数据",
                        style = MiuixTheme.textStyles.main,
                    )
                    if (state.refreshing) {
                        Spacer(Modifier.height(12.dp))
                        // 官方接口慢（实测最慢 20s+），必须给出等待反馈，否则像卡死
                        LinearProgressIndicator(
                            progress = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            return@Column
        }

        PlanCard(snapshot)

        SmallTitle("额度池")
        PoolCard(snapshot)

        SmallTitle("本计费周期用量")
        TokenCard(snapshot)

        val windows = listOfNotNull(snapshot.fiveHour, snapshot.weekly, snapshot.monthly)
        if (windows.isNotEmpty()) {
            SmallTitle("用量窗口")
            windows.forEach { window ->
                WindowCard(window = window, now = now)
            }
        }

        if (snapshot.partialFailures.isNotEmpty()) {
            SmallTitle("部分数据未取到")
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    snapshot.partialFailures.forEach { item ->
                        Text(
                            text = "· $item",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        SmallTitle("数据来源")
        MetaCard(snapshot, state)

        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun PlanCard(snapshot: UsageSnapshot) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = snapshot.planName ?: "未知套餐",
                    style = MiuixTheme.textStyles.title4,
                )
                Text(
                    text = statusLabel(snapshot.subscriptionStatus),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (snapshot.planMonthlyCredits != null) {
                Text(
                    text = "标称额度 ${Format.usd(snapshot.planMonthlyCredits)}/月",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            snapshot.accountName?.let {
                Text(
                    text = "账号 $it",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun PoolCard(snapshot: UsageSnapshot) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = "剩余",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = Format.usd(snapshot.totalRemaining),
                        style = MiuixTheme.textStyles.title3,
                    )
                }
                Text(
                    text = "已用 ${Format.usd(snapshot.spent)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            LinearProgressIndicator(
                progress = snapshot.usagePercent?.let { (it / 100.0).toFloat() },
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = dev.zxeb.ccusage.ui.components.utilizationColor(snapshot.usagePercent),
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainerVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "总额 ${Format.usd(snapshot.totalPool)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "已用 ${Format.percent(snapshot.usagePercent)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            HorizontalDivider()

            KeyValueRow("月度额度", Format.usd(snapshot.monthlyRemaining))
            KeyValueRow("加油包", Format.usd(snapshot.purchasedRemaining))
            KeyValueRow("赠送额度", Format.usd(snapshot.freeRemaining))
        }
    }
}

@Composable
private fun TokenCard(snapshot: UsageSnapshot) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = Format.millions(snapshot.tokensTotal),
                    style = MiuixTheme.textStyles.title1,
                )
                Spacer(Modifier.padding(start = 6.dp))
                Text(
                    text = " tokens",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Text(
                text = "输入 ${Format.millions(snapshot.tokensIn)} · 输出 ${Format.millions(snapshot.tokensOut)}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "${Format.count(snapshot.requestCount)} 次请求 · 均次 ${Format.usd(snapshot.averageCost)}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            // 口径必须写清楚，否则用户会以为是注册至今的累计
            Text(
                text = when (snapshot.tokenBasis) {
                    TokenBasis.ACCOUNT_TOTAL -> "统计口径：账户累计（来自官方 /metrics/usage）"
                    TokenBasis.BILLING_PERIOD -> "统计口径：本计费周期（官方 usage/summary 只按当前周期聚合）"
                    TokenBasis.UNKNOWN -> "统计口径：未知"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun MetaCard(snapshot: UsageSnapshot, state: UiState) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyValueRow("刷新时间", Format.absolute(snapshot.fetchedAt))
            KeyValueRow("本次耗时", Format.durationMs(snapshot.fetchDurationMs))
            KeyValueRow(
                "数据状态",
                when (state.source) {
                    DataSource.LIVE -> "最新"
                    DataSource.STALE -> "旧数据"
                    DataSource.LOADING -> "读取中"
                    DataSource.ERROR -> "读取失败"
                    DataSource.EMPTY -> "无数据"
                },
                valueColor = if (state.source == DataSource.STALE) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            KeyValueRow("数据来源", "Command Code 官方账单接口（只读）")
        }
    }
}

@Composable
private fun StaleBanner(message: String, fetchedAt: Instant) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                text = "⚠ 本次刷新失败",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$message\n以上为 ${Format.absolute(fetchedAt)} 的旧数据。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SetupGuideCard(onOpenSettings: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "填入 API Key 即可显示真实用量",
                style = MiuixTheme.textStyles.subtitle,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "在设置页粘贴 Command Code 的 API Key（user_ 开头）。\n只读查询套餐额度，不会消耗额度。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        }
    }
}

private fun statusLabel(status: String?): String = when (status?.lowercase()) {
    null -> PLACEHOLDER
    "active" -> "生效中"
    "trialing" -> "试用中"
    "past_due" -> "逾期"
    "canceled", "cancelled" -> "已取消"
    "incomplete" -> "未完成"
    else -> status
}
