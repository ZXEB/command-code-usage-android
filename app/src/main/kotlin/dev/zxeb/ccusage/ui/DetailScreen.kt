package dev.zxeb.ccusage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zxeb.ccusage.model.Format
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import dev.zxeb.ccusage.ui.components.KeyValueRow
import dev.zxeb.ccusage.ui.components.PLACEHOLDER
import dev.zxeb.ccusage.ui.components.WindowCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant

/**
 * 明细页：计费周期、额度池拆分、各窗口重置时刻、token 细分。
 */
@Composable
fun DetailScreen(
    snapshot: UsageSnapshot?,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (snapshot == null) {
            Card {
                Text(
                    text = "暂无数据，请先到「概览」下拉刷新",
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return@Column
        }

        SmallTitle("计费周期")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyValueRow("周期开始", Format.absolute(snapshot.cycleStart))
                KeyValueRow("周期结束", Format.absolute(snapshot.cycleEnd))
                KeyValueRow("还剩", Format.daysUntil(snapshot.cycleEnd, now))
                HorizontalDivider()
                KeyValueRow("套餐 ID", snapshot.planId ?: PLACEHOLDER)
                KeyValueRow("订阅状态", snapshot.subscriptionStatus ?: PLACEHOLDER)
                snapshot.orgId?.let { KeyValueRow("组织 ID", it) }
            }
        }

        SmallTitle("额度池拆分")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyValueRow("剩余总额", Format.usd(snapshot.totalRemaining))
                KeyValueRow("额度池总额", Format.usd(snapshot.totalPool))
                KeyValueRow("本周期已用", Format.usd(snapshot.spent))
                HorizontalDivider()
                KeyValueRow("月度额度", Format.usd(snapshot.monthlyRemaining))
                KeyValueRow("加油包", Format.usd(snapshot.purchasedRemaining))
                KeyValueRow("赠送额度", Format.usd(snapshot.freeRemaining))
            }
        }

        val windows = listOfNotNull(snapshot.fiveHour, snapshot.weekly, snapshot.monthly)
        if (windows.isNotEmpty()) {
            SmallTitle("窗口重置时刻")
            windows.forEach { window ->
                WindowCard(window = window, now = now)
            }
        }

        SmallTitle("Tokens")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyValueRow("合计", Format.millions(snapshot.tokensTotal))
                KeyValueRow("输入", Format.millions(snapshot.tokensIn))
                KeyValueRow("输出", Format.millions(snapshot.tokensOut))
                HorizontalDivider()
                KeyValueRow("请求次数", Format.count(snapshot.requestCount))
                KeyValueRow("均次成本", Format.usd(snapshot.averageCost))
                KeyValueRow(
                    "统计口径",
                    when (snapshot.tokenBasis) {
                        TokenBasis.ACCOUNT_TOTAL -> "账户累计"
                        TokenBasis.BILLING_PERIOD -> "本计费周期"
                        TokenBasis.UNKNOWN -> PLACEHOLDER
                    },
                )
            }
        }

        if (snapshot.partialFailures.isNotEmpty()) {
            SmallTitle("部分数据未取到")
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    snapshot.partialFailures.forEach {
                        Text(
                            text = "· $it",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        SmallTitle("关于 token 口径")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "官方 usage/summary 的 since 参数实测被服务端忽略，" +
                        "四种写法都返回同一个数字，periodBasis 恒为 billing-period。" +
                        "所以这里显示的是**本计费周期**的累计，不是注册至今的总量。\n\n" +
                        "如果配置了 metrics token，会改用官方 /metrics/usage 取账户级累计，" +
                        "并在上方口径里标明。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(90.dp))
    }
}
