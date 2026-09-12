package dev.zxeb.ccusage.model

import java.time.Instant

/**
 * 用量窗口（5 小时 / 每周 / 每月）。
 *
 * 所有数值字段都可为 null：接口没返回就是「未知」，界面必须显示 `--`，
 * 绝不能用 0 冒充（见 docs/QUOTA.md §6 诚实性设计）。
 */
data class RateWindow(
    val label: String,
    /** 已用额度（美元）或已用百分比对应的数值。 */
    val used: Double?,
    /** 额度上限。服务端字段名是 `cap`，不是 `limit`。 */
    val cap: Double?,
    /** 重置时刻。服务端 `resetAt` 是 epoch 毫秒。 */
    val resetAt: Instant?,
    /** 服务端标记该窗口是否已超限。 */
    val exceeded: Boolean = false,
    /** 该窗口是否由本地按计费周期自算（而非服务端提供）。 */
    val derived: Boolean = false,
) {
    /** 使用率百分比；数据不全时为 null。 */
    val percent: Double?
        get() {
            val u = used ?: return null
            val c = cap ?: return null
            if (c <= 0.0) return null
            return (u / c * 100.0).coerceIn(0.0, 100.0)
        }

    /** 剩余额度；数据不全时为 null。 */
    val remaining: Double?
        get() {
            val u = used ?: return null
            val c = cap ?: return null
            return (c - u).coerceAtLeast(0.0)
        }

    /** 数据是否完整到可以画进度条。 */
    val hasData: Boolean get() = percent != null
}

/** token 数值的来源口径，界面必须如实标注。 */
enum class TokenBasis {
    /** 官方 `usage/summary`：只按当前计费周期聚合（`since` 参数被服务端忽略）。 */
    BILLING_PERIOD,

    /** 官方 `/metrics/usage`：账户级累计。 */
    ACCOUNT_TOTAL,

    /** 尚未取到。 */
    UNKNOWN,
}

/** 数据来源状态。 */
enum class DataSource {
    /** 还没有任何数据。 */
    EMPTY,

    /** 正在抓取。 */
    LOADING,

    /** 真实数据，新鲜。 */
    LIVE,

    /** 刷新失败，展示的是缓存里的旧数据。 */
    STALE,

    /** 抓取失败且无缓存。 */
    ERROR,
}

/**
 * 一次抓取结果的完整快照。
 *
 * 所有业务字段可空，null 一律表示「服务端没给 / 没取到」。
 */
data class UsageSnapshot(
    // ---- 账号与套餐 ----
    val accountName: String? = null,
    val orgId: String? = null,
    val planId: String? = null,
    val planName: String? = null,
    val planMonthlyCredits: Double? = null,
    val subscriptionStatus: String? = null,

    // ---- 额度池 ----
    val monthlyRemaining: Double? = null,
    val purchasedRemaining: Double? = null,
    val freeRemaining: Double? = null,
    val totalRemaining: Double? = null,
    val totalPool: Double? = null,
    val spent: Double? = null,
    val usagePercent: Double? = null,

    // ---- 三个窗口 ----
    val fiveHour: RateWindow? = null,
    val weekly: RateWindow? = null,
    val monthly: RateWindow? = null,

    // ---- 计费周期 ----
    val cycleStart: Instant? = null,
    val cycleEnd: Instant? = null,

    // ---- 累计用量 ----
    val tokensTotal: Long? = null,
    val tokensIn: Long? = null,
    val tokensOut: Long? = null,
    val requestCount: Long? = null,
    val averageCost: Double? = null,
    val tokenBasis: TokenBasis = TokenBasis.UNKNOWN,

    // ---- 元信息 ----
    val fetchedAt: Instant = Instant.EPOCH,
    val fetchDurationMs: Long = 0L,
    /** 部分端点失败的如实记录，界面列在「部分数据未取到」里。 */
    val partialFailures: List<String> = emptyList(),
    val source: DataSource = DataSource.EMPTY,
    val error: String? = null,
) {
    /** 是否拿到了可展示的核心数据。 */
    val hasData: Boolean
        get() = totalRemaining != null || totalPool != null || fiveHour?.hasData == true ||
            weekly?.hasData == true || tokensTotal != null

    /** 套餐是否处于有效状态（决定分母取值方式）。 */
    val subscriptionActive: Boolean
        get() = subscriptionStatus?.lowercase() in ACTIVE_STATUSES

    companion object {
        private val ACTIVE_STATUSES = setOf("active", "trialing", "past_due")
    }
}
