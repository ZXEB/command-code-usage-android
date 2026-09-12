package dev.zxeb.ccusage.net

import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * 把 Command Code 官方四个端点的原始响应拍平成一个 [UsageSnapshot]。
 *
 * 计算完全对齐官方 CLI 的 `projectUsageView`（见 docs/QUOTA.md §2）：
 * ```
 * remaining   = monthly + purchased + free
 * totalPool   = active ? max(标称额度, monthly) + purchased + free : spent + remaining
 * usagePercent= (totalPool - remaining) / totalPool * 100
 * ```
 * 用 `max()` 是因为额度可能被补发/叠加，直接拿标称额度当分母会出现「剩余 > 总额」，
 * 进度条算成负数。
 */
object QuotaProjector {

    /**
     * @param whoami `/alpha/whoami` 响应，可为 null（该端点失败只影响账号显示）
     * @param credits `/alpha/billing/credits` 响应，**必须非 null**
     * @param subscriptions `/alpha/billing/subscriptions` 响应，可为 null
     * @param summary `/alpha/usage/summary` 响应，可为 null
     * @param metricsUsage `/metrics/usage` 响应，可为 null（账户级累计，可选增强）
     */
    fun project(
        whoami: JsonObject?,
        credits: JsonObject,
        subscriptions: JsonObject?,
        summary: JsonObject?,
        metricsUsage: JsonObject?,
        fetchedAt: Instant,
        durationMs: Long,
        partialFailures: List<String>,
    ): UsageSnapshot {
        // ---------- 1. 额度池 ----------
        // 注意：credits 字段在 credits.credits 下；而 windowLimits 在**顶层**，与 credits 平级。
        val creditsObj = credits.obj("credits")
        val monthly = creditsObj.num("monthlyCredits")
        val purchased = creditsObj.num("purchasedCredits")
        val free = creditsObj.num("freeCredits")

        val totalRemaining = if (monthly == null && purchased == null && free == null) {
            null
        } else {
            (monthly ?: 0.0) + (purchased ?: 0.0) + (free ?: 0.0)
        }

        // ---------- 2. 套餐与周期 ----------
        val subscriptionData = subscriptions.obj("data")
        val planId = subscriptionData.str("planId")
        val status = subscriptionData.str("status")
        val cycleStart = subscriptionData.instant("currentPeriodStart")
        val cycleEnd = subscriptionData.instant("currentPeriodEnd")

        val plan = dev.zxeb.ccusage.model.PlanCatalog.resolve(planId)
        val nominalMonthly = plan?.monthlyCredits

        val subscriptionActive = status?.lowercase() in setOf("active", "trialing", "past_due")

        // ---------- 3. 已用金额 ----------
        val summaryData = summary.obj("data") ?: summary
        val spent = summaryData.num("totalCost")

        // ---------- 4. 额度池总额与百分比 ----------
        val totalPool = if (subscriptionActive && nominalMonthly != null && totalRemaining != null) {
            maxOf(nominalMonthly, monthly ?: 0.0) + (purchased ?: 0.0) + (free ?: 0.0)
        } else if (spent != null && totalRemaining != null) {
            spent + totalRemaining
        } else {
            null
        }

        val usagePercent = if (totalPool != null && totalPool > 0.0 && totalRemaining != null) {
            (((totalPool - totalRemaining) / totalPool) * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }

        // ---------- 5. 三个窗口 ----------
        // windowLimits 是**顶层字段**（和 credits 平级），不在 credits 里面。
        // 官方 CLI 里写的是 e.credits?.windowLimits，那个 e.credits 指整个响应体，名字有歧义。
        val windowLimits = credits.obj("windowLimits")
            // 向前兼容：万一服务端哪天挪进 credits 内部
            ?: creditsObj.obj("windowLimits")

        val limited = windowLimits.bool("limited")

        val fiveHour = if (limited == false) null else windowLimits.parseWindow("fiveHour", "5 小时")
        val weekly = if (limited == false) null else windowLimits.parseWindow("weekly", "每周")

        // 每月窗口：服务端通常**没有**，用计费周期的额度池自算。
        // 若服务端将来真的加了 monthly，优先用服务端的（不静默吞掉）。
        val serverMonthly = if (limited == false) null else windowLimits.parseWindow("monthly", "每月")
        val monthlyWindow = serverMonthly ?: if (totalPool != null && totalPool > 0.0 &&
            totalRemaining != null
        ) {
            RateWindow(
                label = "每月",
                used = (totalPool - totalRemaining).coerceAtLeast(0.0),
                cap = totalPool,
                resetAt = cycleEnd,
                exceeded = usagePercent != null && usagePercent >= 100.0,
                derived = true,
            )
        } else {
            null
        }

        // ---------- 6. 账号 ----------
        val accountName = whoami.obj("user").str("name")
            ?: whoami.obj("user").str("email")
            ?: whoami.obj("user").str("username")
        val orgId = whoami.obj("org").str("id")

        // ---------- 7. 累计 token ----------
        // 优先用 /metrics/usage（账户级）；没有就退回 usage/summary，
        // 后者**只统计当前计费周期**（since 参数被服务端忽略），必须如实标注口径。
        val metricsTokens = metricsUsage.long("totalTokens")
        val tokensTotal: Long?
        val tokensIn: Long?
        val tokensOut: Long?
        val tokenBasis: TokenBasis
        val requestCount: Long?
        val averageCost: Double?

        if (metricsTokens != null) {
            tokensTotal = metricsTokens
            tokensIn = metricsUsage.long("totalTokensIn")
            tokensOut = metricsUsage.long("totalTokensOut")
            tokenBasis = TokenBasis.ACCOUNT_TOTAL
            requestCount = metricsUsage.long("totalCount")
            averageCost = metricsUsage.num("averageCost")
        } else {
            tokensTotal = summaryData.long("totalTokens")
            tokensIn = summaryData.long("totalTokensIn")
            tokensOut = summaryData.long("totalTokensOut")
            tokenBasis = if (tokensTotal != null) TokenBasis.BILLING_PERIOD else TokenBasis.UNKNOWN
            requestCount = summaryData.long("totalCount")
            averageCost = summaryData.num("averageCost")
        }

        return UsageSnapshot(
            accountName = accountName,
            orgId = orgId,
            planId = planId,
            planName = plan?.displayName ?: planId,
            planMonthlyCredits = nominalMonthly,
            subscriptionStatus = status,
            monthlyRemaining = monthly,
            purchasedRemaining = purchased,
            freeRemaining = free,
            totalRemaining = totalRemaining,
            totalPool = totalPool,
            spent = spent,
            usagePercent = usagePercent,
            fiveHour = fiveHour,
            weekly = weekly,
            monthly = monthlyWindow,
            cycleStart = cycleStart,
            cycleEnd = cycleEnd,
            tokensTotal = tokensTotal,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            requestCount = requestCount,
            averageCost = averageCost,
            tokenBasis = tokenBasis,
            fetchedAt = fetchedAt,
            fetchDurationMs = durationMs,
            partialFailures = partialFailures,
        )
    }

    /**
     * 解析单个限流窗口。
     *
     * 字段名是 **`cap` 不是 `limit`**；`resetAt` 是 **epoch 毫秒**。
     * 按常识猜字段名会解析出空窗口，这里按服务端实际字段取。
     */
    private fun JsonObject?.parseWindow(key: String, label: String): RateWindow? {
        val node = this.obj(key) ?: return null
        val used = node.num("used")
        val cap = node.num("cap")
        if (used == null && cap == null) return null
        return RateWindow(
            label = label,
            used = used,
            cap = cap,
            resetAt = node.instant("resetAt"),
            exceeded = node.bool("exceeded") ?: false,
            derived = false,
        )
    }
}
