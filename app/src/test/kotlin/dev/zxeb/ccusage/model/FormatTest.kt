package dev.zxeb.ccusage.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormatTest {

    // ---------------- token：以 M 为单位 ----------------

    @Test
    fun `tokens render in millions with one decimal`() {
        assertEquals("199.5M", Format.millions(199_493_661L))
        assertEquals("233.4M", Format.millions(233_370_995L))
        assertEquals("1.4M", Format.millions(1_448_250L))
        assertEquals("0.0M", Format.millions(0L))
    }

    @Test
    fun `null tokens render as placeholder not zero`() {
        // 关键：拿不到数据时必须显示 --，绝不能用 0 冒充
        assertEquals("--", Format.millions(null as Long?))
        assertEquals("--", Format.millions(null as Double?))
    }

    // ---------------- 金额 ----------------

    @Test
    fun `normal amounts use two decimals`() {
        assertEquals("$4.93", Format.usd(4.93))
        assertEquals("$10.00", Format.usd(10.0))
    }

    @Test
    fun `tiny amounts escalate to four decimals`() {
        // 均次成本常年小于 1 分钱，用 2 位小数会显示成 $0.00，看着像免费
        assertEquals("$0.0017", Format.usd(0.0017))
        assertEquals("$0.0090", Format.usd(0.009))
    }

    @Test
    fun `null amount renders as placeholder`() {
        assertEquals("--", Format.usd(null))
        assertEquals("--", Format.amount(null))
    }

    @Test
    fun `percent truncates and handles null`() {
        assertEquals("51%", Format.percent(51.234))
        assertEquals("3%", Format.percent(3.9))
        assertEquals("--", Format.percent(null))
    }

    @Test
    fun `count uses thousands separators`() {
        assertEquals("3,012", Format.count(3012))
        assertEquals("--", Format.count(null))
    }

    // ---------------- 倒计时 ----------------

    private val now = Instant.parse("2026-09-11T15:00:00Z")

    @Test
    fun `duration switches unit by magnitude`() {
        assertEquals("2天3小时", Format.duration(now.plusSeconds(2 * 86400 + 3 * 3600), now))
        assertEquals("3小时12分", Format.duration(now.plusSeconds(3 * 3600 + 12 * 60), now))
        assertEquals("45分", Format.duration(now.plusSeconds(45 * 60), now))
    }

    @Test
    fun `past reset time says imminent`() {
        assertEquals("即将重置", Format.duration(now.minusSeconds(10), now))
    }

    @Test
    fun `null duration renders as placeholder`() {
        assertEquals("--", Format.duration(null, now))
    }

    // ---------------- 重置时刻 ----------------

    @Test
    fun `reset today shows time only`() {
        // 对齐官方 CLI：当天只显示时间，避免每行过长
        val sameDay = Instant.parse("2026-09-11T15:04:00Z")
        val text = Format.resetAt(sameDay, now)
        assertEquals(5, text.length)
        assertEquals(":", text.substring(2, 3))
    }

    @Test
    fun `reset on another day includes date`() {
        val otherDay = Instant.parse("2026-09-25T06:03:00Z")
        val text = Format.resetAt(otherDay, now)
        assertEquals(true, text.contains("/"))
    }

    @Test
    fun `null reset renders as placeholder`() {
        assertEquals("--", Format.resetAt(null, now))
    }

    @Test
    fun `daysUntil rounds up and handles expiry`() {
        assertEquals("14 天", Format.daysUntil(now.plusSeconds(14 * 86400), now))
        assertEquals("1 天", Format.daysUntil(now.plusSeconds(3600), now))
        assertEquals("已到期", Format.daysUntil(now.minusSeconds(60), now))
        assertEquals("--", Format.daysUntil(null, now))
    }

    @Test
    fun `durationMs formats fetch cost`() {
        assertEquals("820ms", Format.durationMs(820))
        assertEquals("1.2s", Format.durationMs(1200))
        assertEquals("6s", Format.durationMs(6000))
    }
}
