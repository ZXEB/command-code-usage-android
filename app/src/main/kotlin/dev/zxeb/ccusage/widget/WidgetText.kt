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
 * 口径与应用内的窗口卡片（`ui/components/QuotaComponents.kt` 的 `WindowCard`）**逐字对齐**：
 * - 显示的是**已用**百分比（`used / cap`，截断取整），不是剩余 —— 应用里也是这个口径，
 *   两边必须一致，否则同一份数据在应用里 7%、在桌面上 93%，用户没法判断该信哪个；
 * - 进度条画的是**同一个已用比例**，所以「用得越多、条越长」，与数字同向；
 * - 颜色按**已用**比例分档（<70% 绿 / ≥70% 黄 / ≥90% 红），与应用内 `utilizationColor` 同阈值；
 * - 明细行照抄应用：「剩余 $2.77 / $3.00」「2小时50分后重置（2026/9/15 00:08）」「按周期推算」；
 * - 任何拿不到的数值一律 `--`，**绝不显示 0**（0 会被误读成「没用过 / 用完了」），
 *   进度条在数据缺失时隐藏而不是画成 0。
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
    // 已用比例（与应用内 WindowCard 同口径）
    // ------------------------------------------------------------------

    /**
     * 已用百分比（整数，0..100）。`usedPercent` 为 null 时返回 null（未知，不是 0）。
     *
     * 用**截断**而不是四舍五入：与应用内 `Format.percent`（`value.toInt()`）保持一致，
     * 避免同一份数据在应用里显示 7%、在小组件里显示 8%。
     */
    fun usedPercent(usedPercent: Double?): Int? = usedPercent?.toInt()?.coerceIn(0, 100)

    /** 窗口的已用百分比；窗口缺失或额度未知时为 null。 */
    fun usedPercent(window: RateWindow?): Int? = usedPercent(window?.percent)

    /** `"7%"`；未知时为 `--`。 */
    fun usedPercentText(usedPercent: Double?): String =
        usedPercent?.let { Format.percent(it) } ?: Format.UNKNOWN

    /** 窗口的已用百分比文案。 */
    fun usedPercentText(window: RateWindow?): String = usedPercentText(window?.percent)

    // ------------------------------------------------------------------
    // 进度条
    // ------------------------------------------------------------------

    /**
     * 进度条进度值（0..100）：与上方数字同源（都是已用比例）。未知时为 0。
     *
     * 直接接受百分比的重载：2×2 的大字进度条走的是额度池口径的 `usagePercent`，
     * 手里没有 `RateWindow`。参数名不叫 `usedPercent`，否则会和上面那个函数重名。
     *
     * 返回 0 不代表「没用过」——它只表示条该画多长，未知时由 [barVisible] 决定**隐藏**。
     */
    fun barProgress(percent: Double?): Int = usedPercent(percent) ?: 0

    /** 窗口的进度条进度值。 */
    fun barProgress(window: RateWindow?): Int = barProgress(window?.percent)

    /**
     * 进度条是否可见。
     *
     * 数据缺失时**必须隐藏**：画成 0% 会让人以为「完全没用」，与「不知道」是两回事。
     * （应用内对应的是不确定态进度条，语义同样是「别把它读成一个具体数值」。）
     */
    fun barVisible(window: RateWindow?): Boolean = usedPercent(window) != null

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

    /** 档位对应的颜色资源（数值文字与进度条fill 共用）。 */
    fun colorRes(utilization: Utilization): Int = when (utilization) {
        Utilization.OK -> R.color.widget_ok
        Utilization.WARN -> R.color.widget_warn
        Utilization.ERROR -> R.color.widget_error
        Utilization.UNKNOWN -> R.color.widget_text_tertiary
    }

    // ------------------------------------------------------------------
    // 明细行（照抄应用内 WindowCard 的文案）
    // ------------------------------------------------------------------

    /**
     * `"剩余 $2.77 / $3.00"`。
     *
     * 剩余算不出来时（缺 used 或 cap）只给 `"剩余 --"`，**不拼出 `剩余 -- / $3.00`**
     * 这种半截文案 —— 那既不好看，也容易被读成「剩余是 0、额度是 3.00」。
     */
    fun remainingLine(window: RateWindow?): String {
        val remaining = window?.remaining ?: return "剩余 ${Format.UNKNOWN}"
        return buildString {
            append("剩余 ")
            append(Format.usd(remaining))
            window.cap?.let {
                append(" / ")
                append(Format.usd(it))
            }
        }
    }

    /**
     * `"2小时50分后重置（2026/9/15 00:08）"`。
     *
     * 拿不到重置时刻直接给 `--`，不拼出「--后重置」这种半截文案。
     */
    fun resetLine(window: RateWindow?, now: Instant = Instant.now()): String {
        val resetAt = window?.resetAt ?: return Format.UNKNOWN
        return "${Format.duration(resetAt, now)}后重置（${Format.resetAt(resetAt, now)}）"
    }

    /**
     * 窗口标题旁的推算标记，不需要时返回 null。
     *
     * 月度窗口是本地按计费周期推算的（服务端 `windowLimits` 里没有 `monthly`），
     * 应用内会标「按周期推算」，小组件必须同样标注，否则会被当成服务端权威口径。
     *
     * @param forcedDerived 调用方已知是本地推算的（例如从额度池回落后合成出来的月度窗口），
     *   此时即使 `window.derived` 没置位也要标。
     */
    fun derivedNote(window: RateWindow?, forcedDerived: Boolean = false): String? =
        if (forcedDerived || window?.derived == true) "按周期推算" else null

    // ------------------------------------------------------------------
    // 2×2 紧凑行
    // ------------------------------------------------------------------

    /** `"5 小时 7%"`。 */
    fun windowLine(label: String, window: RateWindow?): String =
        "$label ${usedPercentText(window)}"

    /**
     * 2×2 里那一行紧凑的窗口摘要：`"5时 7% · 周 31%"`。
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
     * 2×2 的 token 行：`"本期 233.4M"` / `"累计 233.4M"`。
     *
     * 4×2 里数字后面跟得下「本期 tokens」这句说明，2×2 的 86dp 宽度放不下，
     * 但光秃秃一个 `233.4M` 又会被误读，所以把口径压成两个字的前缀。
     * 没有数据时就是 `--`（不去掉前缀硬凑一个 0）。
     */
    fun tokensLine(basis: TokenBasis, total: Long?): String {
        val value = Format.millions(total)
        if (value == Format.UNKNOWN) return Format.UNKNOWN
        val prefix = if (basis == TokenBasis.ACCOUNT_TOTAL) "累计" else "本期"
        return "$prefix $value"
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
