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
 * 重点锁三件事：
 * 1. **未知不是 0**：拿不到的数值必须是 `--`，进度条必须隐藏；
 * 2. **与应用内窗口卡片同口径**：显示**已用**百分比（`used / cap`，截断），
 *    不是剩余 —— 应用里 `WindowCard` 显示的就是已用，两边必须一致；
 * 3. **配色阈值** <70 / ≥70 / ≥90 与应用内 `utilizationColor` 相同。
 */
class WidgetTextTest {

    private fun window(
        used: Double?,
        cap: Double?,
        label: String = "5 小时",
        exceeded: Boolean = false,
        resetAt: Instant? = null,
        derived: Boolean = false,
    ) = RateWindow(
        label = label,
        used = used,
        cap = cap,
        resetAt = resetAt,
        exceeded = exceeded,
        derived = derived,
    )

    /** 构造一个已用比例为 [usedPercent] 的窗口（cap 固定 100，便于按百分比思考）。 */
    private fun byPercent(usedPercent: Double) = window(used = usedPercent, cap = 100.0)

    // ------------------------------------------------------------------
    // 已用百分比
    // ------------------------------------------------------------------

    @Test
    fun `used percent truncates exactly like the app does`() {
        // 0.072 / 3.0 = 2.4% 已用 -> 截断 2（与应用内 Format.percent 的 toInt() 一致）
        assertEquals(2, WidgetText.usedPercent(window(0.072, 3.0)))
        // 0.015 / 3.0 = 0.5% -> 截断 0。注意 0 是「真实算出来的 0」，不是未知
        assertEquals(0, WidgetText.usedPercent(window(0.015, 3.0)))
        assertEquals(0, WidgetText.usedPercent(window(0.0, 3.0)))
        assertEquals(100, WidgetText.usedPercent(window(3.0, 3.0)))
    }

    @Test
    fun `used percent clamps when the window is exceeded`() {
        // used > cap：percent 已被 RateWindow 夹到 100，不能超过 100
        assertEquals(100, WidgetText.usedPercent(window(4.5, 3.0, exceeded = true)))
    }

    @Test
    fun `missing data yields null instead of zero`() {
        assertNull(WidgetText.usedPercent(window(null, 3.0)))
        assertNull(WidgetText.usedPercent(window(1.0, null)))
        assertNull(WidgetText.usedPercent(window(null, null)))
        // cap <= 0 无法算比例，同样算未知
        assertNull(WidgetText.usedPercent(window(1.0, 0.0)))
        assertNull(WidgetText.usedPercent(null as RateWindow?))
    }

    @Test
    fun `used percent text falls back to the placeholder`() {
        assertEquals("2%", WidgetText.usedPercentText(window(0.072, 3.0)))
        assertEquals("--", WidgetText.usedPercentText(window(null, 3.0)))
        assertEquals("--", WidgetText.usedPercentText(null as RateWindow?))
        assertEquals("--", WidgetText.usedPercentText(null as Double?))
    }

    @Test
    fun `double overload keeps the same semantics`() {
        // 2×2 的大字走的是额度池口径的 usagePercent（Double），口径必须与窗口一致
        assertEquals(2, WidgetText.usedPercent(2.4))
        assertEquals(0, WidgetText.usedPercent(0.0))
        assertEquals(100, WidgetText.usedPercent(100.0))
        assertNull(WidgetText.usedPercent(null as Double?))
    }

    // ------------------------------------------------------------------
    // 进度条
    // ------------------------------------------------------------------

    @Test
    fun `bar progress mirrors the number above it`() {
        val window = window(0.072, 3.0)
        assertEquals(2, WidgetText.barProgress(window))
        assertTrue(WidgetText.barVisible(window))
    }

    @Test
    fun `bar is hidden when data is missing so it never reads as zero`() {
        // 画成 0% 会让人以为「完全没用过」，与「不知道」是两回事 -> 必须隐藏
        val unknown = window(null, 3.0)
        assertEquals(0, WidgetText.barProgress(unknown))
        assertFalse(WidgetText.barVisible(unknown))
        assertFalse(WidgetText.barVisible(null))
    }

    @Test
    fun `bar progress accepts a raw percentage for the 2x2 big bar`() {
        // 2×2 的月度大字进度条手里只有 usagePercent（额度池口径），没有 RateWindow，
        // 走 Double 重载；口径必须与窗口重载一致（同样截断、同样 0..100）
        assertEquals(2, WidgetText.barProgress(2.4))
        assertEquals(87, WidgetText.barProgress(87.9))
        assertEquals(0, WidgetText.barProgress(0.0))
        assertEquals(100, WidgetText.barProgress(100.0))
        assertEquals(0, WidgetText.barProgress(null as Double?))
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
    // 明细行（对齐应用内 WindowCard）
    // ------------------------------------------------------------------

    @Test
    fun `remaining line shows remaining over cap`() {
        // 0.072 / 3.0 -> 剩余 2.93（Format.usd 保留 2 位）
        assertEquals("剩余 $2.93 / $3.00", WidgetText.remainingLine(window(0.072, 3.0)))
    }

    @Test
    fun `remaining line omits the cap when the server does not provide one`() {
        // RateWindow.remaining 需要 used 与 cap 同时存在；缺 cap 就算不出剩余 ->
        // 只显示「剩余 --」，不要拼成「剩余 -- / $x」或硬凑一个数字
        assertEquals("剩余 --", WidgetText.remainingLine(window(0.5, null)))
    }

    @Test
    fun `remaining line degrades to placeholder without data`() {
        // used 缺失 -> remaining 未知；此时即使 cap 有值也不能拼出 `剩余 -- / $3.00`
        // （既丑，也容易被读成「剩余 0、额度 3.00」）
        assertEquals("剩余 --", WidgetText.remainingLine(window(null, 3.0)))
        assertEquals("剩余 --", WidgetText.remainingLine(null))
    }

    @Test
    fun `reset line mirrors the app wording`() {
        val now = Instant.parse("2026-09-14T22:00:00Z")
        val win = window(0.0, 3.0, resetAt = now.plusSeconds(2 * 3600 + 50 * 60))
        val line = WidgetText.resetLine(win, now)
        assertTrue("应包含倒计时与「后重置」：$line", line.contains("后重置"))
        assertTrue("应包含括号里的时刻：$line", line.contains("（") && line.contains("）"))
    }

    @Test
    fun `reset line degrades to placeholder without a reset instant`() {
        // 不能拼出「--后重置」这种半截文案
        assertEquals("--", WidgetText.resetLine(window(0.0, 3.0), Instant.now()))
        assertEquals("--", WidgetText.resetLine(null, Instant.now()))
    }

    @Test
    fun `derived note is shown only for locally projected windows`() {
        // 月度窗口是本地按计费周期推算的，必须标注（否则会被当成服务端权威口径）
        assertEquals("按周期推算", WidgetText.derivedNote(window(1.0, 10.0, derived = true)))
        assertNull(WidgetText.derivedNote(window(1.0, 10.0, derived = false)))
        assertNull(WidgetText.derivedNote(null))
        // forcedDerived：调用方从额度池合成月度窗口时置位，即使 derived 没置也要标
        assertEquals("按周期推算", WidgetText.derivedNote(window(1.0, 10.0, derived = false), forcedDerived = true))
        assertEquals("按周期推算", WidgetText.derivedNote(null, forcedDerived = true))
    }

    // ------------------------------------------------------------------
    // 2×2 紧凑行
    // ------------------------------------------------------------------

    @Test
    fun `compact line joins the two windows the 2x2 can fit`() {
        val line = WidgetText.compactWindowsLine(
            fiveHour = window(0.072, 3.0),
            weekly = window(5.0, 100.0, label = "每周"),
            fiveHourLabel = "5 小时",
            weeklyLabel = "每周",
        )
        assertEquals("5 小时 2% · 每周 5%", line)
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
            "5 小时 2%",
            WidgetText.compactWindowsLine(window(0.072, 3.0), null, "5 小时", "每周"),
        )
    }

    @Test
    fun `tokens caption states the basis honestly`() {
        assertEquals("累计 tokens", WidgetText.tokensCaption(TokenBasis.ACCOUNT_TOTAL))
        assertEquals("本期 tokens", WidgetText.tokensCaption(TokenBasis.BILLING_PERIOD))
        assertEquals("本期 tokens", WidgetText.tokensCaption(TokenBasis.UNKNOWN))
    }

    @Test
    fun `2x2 token line carries the basis prefix`() {
        // 2×2 宽度放不下「本期 tokens」这句说明，但光秃秃一个数字会被误读，所以压成两字前缀
        assertEquals("本期 233.4M", WidgetText.tokensLine(TokenBasis.BILLING_PERIOD, 233_370_995L))
        assertEquals("累计 233.4M", WidgetText.tokensLine(TokenBasis.ACCOUNT_TOTAL, 233_370_995L))
        assertEquals("本期 233.4M", WidgetText.tokensLine(TokenBasis.UNKNOWN, 233_370_995L))
    }

    @Test
    fun `2x2 token line stays a placeholder without data`() {
        assertEquals("--", WidgetText.tokensLine(TokenBasis.BILLING_PERIOD, null))
        assertEquals("--", WidgetText.tokensLine(TokenBasis.ACCOUNT_TOTAL, null))
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
