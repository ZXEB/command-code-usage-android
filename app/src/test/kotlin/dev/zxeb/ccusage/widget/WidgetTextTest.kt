package dev.zxeb.ccusage.widget

import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 小组件文案/配色规则的纯 JVM 测试。
 *
 * 小组件的 `RemoteViews` 在 JVM 里做不了像素断言，所以把「显示什么」全部压进
 * [WidgetText] 这个纯函数对象，在这里正面断言。`WidgetRendererTest` 只负责
 * 「各状态都能渲染出来 + 布局 id 与渲染器引用一致」。
 *
 * 重点锁两件事：
 * 1. **未知不是 0**：拿不到的数值必须是 `--`，进度条必须走不确定态；
 * 2. **与应用内口径一致**：剩余百分比 = `(100 - 已用%).toInt()`（截断而非四舍五入），
 *    配色阈值 <70 / ≥70 / ≥90。
 */
class WidgetTextTest {

    private fun window(
        used: Double?,
        cap: Double?,
        label: String = "5 小时",
        exceeded: Boolean = false,
    ) = RateWindow(label = label, used = used, cap = cap, resetAt = null, exceeded = exceeded)

    /** 构造一个已用比例为 [usedPercent] 的窗口（cap 固定 100，便于按百分比思考）。 */
    private fun byPercent(usedPercent: Double) = window(used = usedPercent, cap = 100.0)

    // ------------------------------------------------------------------
    // 剩余百分比
    // ------------------------------------------------------------------

    @Test
    fun `remaining percent truncates exactly like the app does`() {
        // 0.072 / 3.0 = 2.4% 已用 -> 97.6 -> 截断 97（与应用内 (100.0 - percent).toInt() 一致）
        assertEquals(97, WidgetText.remainingPercent(window(0.072, 3.0)))
        // 0.015 / 3.0 = 0.5% -> 99.5 -> 99（四舍五入会得到 100，那是错的）
        assertEquals(99, WidgetText.remainingPercent(window(0.015, 3.0)))
        assertEquals(100, WidgetText.remainingPercent(window(0.0, 3.0)))
        assertEquals(0, WidgetText.remainingPercent(window(3.0, 3.0)))
    }

    @Test
    fun `remaining percent clamps when the window is exceeded`() {
        // used > cap：percent 已被 RateWindow 夹到 100，剩余必须是 0 而不是负数
        assertEquals(0, WidgetText.remainingPercent(window(4.5, 3.0, exceeded = true)))
    }

    @Test
    fun `missing data yields null instead of zero`() {
        assertNull(WidgetText.remainingPercent(window(null, 3.0)))
        assertNull(WidgetText.remainingPercent(window(1.0, null)))
        assertNull(WidgetText.remainingPercent(window(null, null)))
        // cap <= 0 无法算比例，同样算未知
        assertNull(WidgetText.remainingPercent(window(1.0, 0.0)))
        assertNull(WidgetText.remainingPercent(null as RateWindow?))
    }

    @Test
    fun `remaining percent text falls back to the placeholder`() {
        assertEquals("97%", WidgetText.remainingPercentText(window(0.072, 3.0)))
        assertEquals("--", WidgetText.remainingPercentText(window(null, 3.0)))
        assertEquals("--", WidgetText.remainingPercentText(null as RateWindow?))
        assertEquals("--", WidgetText.remainingPercentText(null as Double?))
    }

    @Test
    fun `double overload keeps the same semantics`() {
        // 2×2 的大字走的是额度池口径的 usagePercent（Double），口径必须与窗口一致
        assertEquals(97, WidgetText.remainingPercent(2.4))
        assertEquals(100, WidgetText.remainingPercent(0.0))
        assertEquals(0, WidgetText.remainingPercent(100.0))
        assertNull(WidgetText.remainingPercent(null as Double?))
    }

    // ------------------------------------------------------------------
    // 进度条
    // ------------------------------------------------------------------

    @Test
    fun `bar progress mirrors the number above it`() {
        val window = window(0.072, 3.0)
        assertEquals(97, WidgetText.barProgress(window))
        assertFalse(WidgetText.barIsIndeterminate(window))
    }

    @Test
    fun `bar is indeterminate when data is missing so it never reads as zero`() {
        val unknown = window(null, 3.0)
        assertEquals(0, WidgetText.barProgress(unknown))
        assertTrue(WidgetText.barIsIndeterminate(unknown))
        assertTrue(WidgetText.barIsIndeterminate(null))
    }

    // ------------------------------------------------------------------
    // 配色阈值
    // ------------------------------------------------------------------

    @Test
    fun `utilization thresholds match the app`() {
        assertEquals(WidgetText.Utilization.OK, WidgetText.utilization(byPercent(69.9)))
        assertEquals(WidgetText.Utilization.WARN, WidgetText.utilization(byPercent(70.0)))
        assertEquals(WidgetText.Utilization.WARN, WidgetText.utilization(byPercent(89.9)))
        assertEquals(WidgetText.Utilization.ERROR, WidgetText.utilization(byPercent(90.0)))
        assertEquals(WidgetText.Utilization.ERROR, WidgetText.utilization(byPercent(100.0)))
    }

    @Test
    fun `utilization is unknown without data`() {
        assertEquals(WidgetText.Utilization.UNKNOWN, WidgetText.utilization(window(null, 3.0)))
        assertEquals(WidgetText.Utilization.UNKNOWN, WidgetText.utilization(null))
        assertEquals(WidgetText.Utilization.UNKNOWN, WidgetText.utilizationOf(null))
    }

    @Test
    fun `each utilization maps to its own color resource`() {
        val colors = listOf(
            WidgetText.colorRes(WidgetText.Utilization.OK),
            WidgetText.colorRes(WidgetText.Utilization.WARN),
            WidgetText.colorRes(WidgetText.Utilization.ERROR),
            WidgetText.colorRes(WidgetText.Utilization.UNKNOWN),
        )
        assertEquals("四档颜色不能重合", 4, colors.toSet().size)
        assertEquals(R.color.widget_ok, colors[0])
        assertEquals(R.color.widget_warn, colors[1])
        assertEquals(R.color.widget_error, colors[2])
        assertEquals(R.color.widget_text_tertiary, colors[3])
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    @Test
    fun `compact line joins the two windows the 2x2 can fit`() {
        val line = WidgetText.compactWindowsLine(
            fiveHour = window(0.072, 3.0),
            weekly = window(5.0, 100.0, label = "每周"),
            fiveHourLabel = "5 小时",
            weeklyLabel = "每周",
        )
        assertEquals("5 小时 97% · 每周 95%", line)
    }

    @Test
    fun `compact line degrades to the placeholder when both windows are gone`() {
        // 服务端 limited=false 时 fiveHour/weekly 都是 null
        assertEquals(
            "--",
            WidgetText.compactWindowsLine(null, null, "5 小时", "每周"),
        )
        // 只有一个窗口时只显示那一个，不留孤零零的分隔符
        assertEquals(
            "5 小时 97%",
            WidgetText.compactWindowsLine(window(0.072, 3.0), null, "5 小时", "每周"),
        )
    }

    @Test
    fun `tokens caption states the basis honestly`() {
        assertEquals("累计 tokens", WidgetText.tokensCaption(TokenBasis.ACCOUNT_TOTAL))
        assertEquals("本期 tokens", WidgetText.tokensCaption(TokenBasis.BILLING_PERIOD))
        assertEquals("本期 tokens", WidgetText.tokensCaption(TokenBasis.UNKNOWN))
    }

    // ------------------------------------------------------------------
    // 更新时间戳
    // ------------------------------------------------------------------

    @Test
    fun `stamp says so when the api key is gone`() {
        val snapshot = UsageSnapshot(fetchedAt = Instant.parse("2026-09-12T14:41:00Z"))
        assertEquals("未配置 Key", WidgetText.updateStamp(snapshot, hasApiKey = false))
    }

    @Test
    fun `stamp flags stale data`() {
        val now = Instant.parse("2026-09-12T14:41:00Z")
        val fresh = UsageSnapshot(fetchedAt = now, source = DataSource.LIVE)
        val stale = UsageSnapshot(fetchedAt = now, source = DataSource.STALE)

        val freshStamp = WidgetText.updateStamp(fresh, hasApiKey = true, now = now)
        val staleStamp = WidgetText.updateStamp(stale, hasApiKey = true, now = now)

        // 具体时刻受运行环境时区影响，这里只断言「有内容」「陈旧带 ⚠」这两个不变量
        assertNotEquals("--", freshStamp)
        assertFalse(freshStamp.contains("⚠"))
        assertTrue("旧数据必须带 ⚠", staleStamp.contains("⚠"))
    }
}
