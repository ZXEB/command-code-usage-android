package dev.zxeb.ccusage.widget

import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.Format
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import java.time.Instant

/**
 * 小组件的文案与配色规则。
 *
 * **纯函数、不碰 Android 视图**：小组件的 `RemoteViews` 在 JVM 里无法做像素断言，
 * 所以把「显示什么字、用什么颜色、进度条走多少」这些规则全部抽到这里，
 * 由 `WidgetTextTest` 直接断言。`WidgetRenderer` 只负责把这些结果塞进
 * `RemoteViews`，不再自己算数。
 *
 * 口径约定（README「小部件显示内容」一节同步说明）：
 * - 窗口显示的是**剩余**（`100 - 已用%`，截断取整，与应用内 `(100.0 - percent).toInt()` 一致）；
 * - 进度条画的是**同一个剩余比例**，所以「数字大 = 条长」永远自洽；
 * - 颜色按**已用**比例分档（<70% 绿 / ≥70% 黄 / ≥90% 红），与应用内 `utilizationColor` 同阈值；
 * - 任何拿不到的数值一律 `--`，**绝不显示 0**（0 会被误读成「用完了」）。
 */
object WidgetText {

    /** 占用率档位，决定文字与进度条颜色。 */
    enum class Utilization {
        OK,
        WARN,
        ERROR,

        /** 数据缺失：既不是绿也不是红，用中性灰。 */
        UNKNOWN,
    }

    // ------------------------------------------------------------------
    // 剩余比例
    // ------------------------------------------------------------------

    /**
     * 剩余百分比（整数，0..100）。`usedPercent` 为 null 时返回 null（未知，不是 0）。
     *
     * 用**截断**而不是四舍五入：与应用内既有写法 `(100.0 - percent).toInt()` 保持一致，
     * 避免同一份数据在应用里显示 97%、在小组件里显示 98%。
     */
    fun remainingPercent(usedPercent: Double?): Int? {
        val used = usedPercent ?: return null
        return (100.0 - used).toInt().coerceIn(0, 100)
    }

    /** 窗口的剩余百分比；窗口缺失或额度未知时为 null。 */
    fun remainingPercent(window: RateWindow?): Int? = remainingPercent(window?.percent)

    /** `"97%"`；未知时为 `--`。 */
    fun remainingPercentText(usedPercent: Double?): String =
        remainingPercent(usedPercent)?.let { "$it%" } ?: Format.UNKNOWN

    /** 窗口的剩余百分比文案。 */
    fun remainingPercentText(window: RateWindow?): String =
        remainingPercent(window)?.let { "$it%" } ?: Format.UNKNOWN

    // ------------------------------------------------------------------
    // 进度条
    // ------------------------------------------------------------------

    /** 进度条进度值（0..100）：与上方数字同源。未知时返回 0 并配合 [barIsIndeterminate]。 */
    fun barProgress(window: RateWindow?): Int = remainingPercent(window) ?: 0

    /**
     * 进度条是否走不确定态。
     *
     * 数据缺失时**必须**不确定：画成 0% 会让人以为额度用光了。
     */
    fun barIsIndeterminate(window: RateWindow?): Boolean = remainingPercent(window) == null

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    /** 按已用比例定档。 */
    fun utilizationOf(usedPercent: Double?): Utilization = when {
        usedPercent == null -> Utilization.UNKNOWN
        usedPercent >= 90.0 -> Utilization.ERROR
        usedPercent >= 70.0 -> Utilization.WARN
        else -> Utilization.OK
    }

    /** 窗口的档位。 */
    fun utilization(window: RateWindow?): Utilization = utilizationOf(window?.percent)

    /** 档位对应的颜色资源。 */
    fun colorRes(utilization: Utilization): Int = when (utilization) {
        Utilization.OK -> R.color.widget_ok
        Utilization.WARN -> R.color.widget_warn
        Utilization.ERROR -> R.color.widget_error
        Utilization.UNKNOWN -> R.color.widget_text_tertiary
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    /** `"5 小时 97%"`。 */
    fun windowLine(label: String, window: RateWindow?): String =
        "$label ${remainingPercentText(window)}"

    /**
     * 2×2 里那一行紧凑的三窗口摘要：`"5 小时 97% · 每周 95%"`。
     *
     * 2×2 只有 110dp 高，放不下第三项（每月已经是下面那个大字），所以这里只拼两个窗口；
     * 两者都拿不到时返回 `--`。
     */
    fun compactWindowsLine(
        fiveHour: RateWindow?,
        weekly: RateWindow?,
        fiveHourLabel: String,
        weeklyLabel: String,
    ): String {
        val parts = ArrayList<String>(2)
        if (fiveHour != null) parts += windowLine(fiveHourLabel, fiveHour)
        if (weekly != null) parts += windowLine(weeklyLabel, weekly)
        return if (parts.isEmpty()) Format.UNKNOWN else parts.joinToString(" · ")
    }

    /**
     * token 口径说明。
     *
     * `usage/summary` 的 `since` 参数被服务端忽略，只按当前计费周期聚合；
     * 只有 `/metrics/usage` 才是账户级累计。口径写错比不写更糟，所以必须区分。
     */
    fun tokensCaption(basis: TokenBasis): String = when (basis) {
        TokenBasis.ACCOUNT_TOTAL -> "累计 tokens"
        else -> "本期 tokens"
    }

    /**
     * 右上角时间戳。
     *
     * - 没有 API Key（但缓存里还有数据）时直接说明，避免用户以为数据是新的；
     * - 刷新失败留在缓存上时加 `⚠`（与应用内「旧数据」标注同一套诚实性设计）。
     */
    fun updateStamp(
        snapshot: UsageSnapshot,
        hasApiKey: Boolean,
        now: Instant = Instant.now(),
    ): String {
        if (!hasApiKey) return "未配置 Key"
        val stamp = Format.resetAt(snapshot.fetchedAt, now)
        return if (snapshot.source == DataSource.STALE) "$stamp ⚠" else stamp
    }
}
