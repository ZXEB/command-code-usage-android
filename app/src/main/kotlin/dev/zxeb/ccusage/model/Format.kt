package dev.zxeb.ccusage.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 数值格式化。
 *
 * 统一口径（对齐官方 CLI）：
 * - token 以 **M（百万）** 显示，保留 1 位小数；
 * - 金额默认 2 位小数，小于 $0.01 时自动提高到 4 位（否则均次成本会显示成 $0.00，看着像免费）；
 * - 任何 null 一律返回 `--`，**绝不用 0 冒充**。
 */
object Format {

    const val UNKNOWN = "--"

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** `199493661` -> `"199.5M"`；null -> `"--"`。 */
    fun millions(tokens: Long?): String {
        if (tokens == null) return UNKNOWN
        val value = tokens.toDouble() / 1_000_000.0
        return "%.1fM".format(value)
    }

    /** 同上，但接受 Double。 */
    fun millions(tokens: Double?): String {
        if (tokens == null) return UNKNOWN
        return "%.1fM".format(tokens / 1_000_000.0)
    }

    /** 金额：`4.93` -> `"$4.93"`；`0.0017` -> `"$0.0017"`。 */
    fun usd(value: Double?): String {
        if (value == null) return UNKNOWN
        return "$" + amount(value)
    }

    /** 不带货币符号的金额数字。 */
    fun amount(value: Double?): String {
        if (value == null) return UNKNOWN
        val abs = kotlin.math.abs(value)
        return if (abs < 0.01) "%.4f".format(value) else "%.2f".format(value)
    }

    /** `51.234` -> `"51%"`。 */
    fun percent(value: Double?): String {
        if (value == null) return UNKNOWN
        return "${value.toInt()}%"
    }

    /** 千分位计数：`3012` -> `"3,012"`。 */
    fun count(value: Long?): String {
        if (value == null) return UNKNOWN
        return "%,d".format(value)
    }

    /**
     * 倒计时。对齐官方 CLI 的 `formatDuration`：
     * `2天3小时` / `3小时12分` / `45分`。
     */
    fun duration(from: Instant?, now: Instant = Instant.now()): String {
        if (from == null) return UNKNOWN
        val seconds = from.epochSecond - now.epochSecond
        if (seconds <= 0) return "即将重置"
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${minutes}分"
            else -> "${minutes}分"
        }
    }

    /**
     * 重置时刻。对齐官方 CLI：**当天只显示时间**（`23:04`），跨天才带日期
     * （`2026/9/12 15:45`），避免每行过长。
     */
    fun resetAt(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return UNKNOWN
        val local = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return if (local.toLocalDate() == today) {
            TIME_FORMAT.format(local)
        } else {
            DATE_TIME_FORMAT.format(local)
        }
    }

    /** 绝对时刻：`2026/9/11 22:54:31`。 */
    fun absolute(instant: Instant?): String {
        if (instant == null) return UNKNOWN
        return FULL_FORMAT.format(instant.atZone(zone))
    }

    /** 日期：`2026/9/25`。 */
    fun date(instant: Instant?): String {
        if (instant == null) return UNKNOWN
        return DATE_FORMAT.format(instant.atZone(zone))
    }

    /** 距今多少天（向上取整），用于「还剩 N 天」。 */
    fun daysUntil(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return UNKNOWN
        val seconds = instant.epochSecond - now.epochSecond
        if (seconds <= 0) return "已到期"
        val days = (seconds + 86_399) / 86_400
        return "$days 天"
    }

    /** 抓取耗时：`6s` / `1.2s` / `820ms`。 */
    fun durationMs(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 10_000 -> "%.1fs".format(ms / 1000.0)
        else -> "${ms / 1000}s"
    }

    /** 本地日期，供 UI 比较用。 */
    fun localDate(instant: Instant?): LocalDate? = instant?.atZone(zone)?.toLocalDate()

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d")
    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
    private val FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss")
}
