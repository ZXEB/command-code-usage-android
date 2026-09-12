package dev.zxeb.ccusage.model

/**
 * Command Code 套餐标称额度表。
 *
 * 服务端只用 `planId` 标识套餐，不返回「套餐总额」，所以标称额度必须内置
 * （数值与官方 CLI 一致，见 docs/QUOTA.md §2）。
 */
data class PlanInfo(
    val id: String,
    val displayName: String,
    val monthlyCredits: Double,
)

object PlanCatalog {

    /** 匹配键 -> (显示名, 标称额度)。**必须按长度倒序做前缀匹配**，见 [resolve]。 */
    private val PLANS: List<PlanInfo> = listOf(
        PlanInfo("individual-go", "Go", 10.0),
        PlanInfo("individual-provider", "Provider", 15.0),
        PlanInfo("individual-pro", "Pro", 30.0),
        PlanInfo("individual-pro-v1", "Pro", 80.0),
        PlanInfo("individual-goat", "GOAT", 70.0),
        PlanInfo("individual-max", "Max", 150.0),
        PlanInfo("individual-ultra", "Ultra", 300.0),
        PlanInfo("teams-pro", "Teams Pro", 40.0),
    )

    /**
     * 按长度倒序排列的匹配键。
     *
     * 顺序很关键：`individual-pro` 是 `individual-pro-v1` 的前缀，如果按原表顺序匹配，
     * $80 的 Pro 会被显示成 $30。倒序后长键先命中，结果才正确。
     */
    private val KEYS_BY_LENGTH_DESC: List<String> =
        PLANS.map { it.id }.sortedByDescending { it.length }

    private val BY_ID: Map<String, PlanInfo> = PLANS.associateBy { it.id }

    /** `planId` 归一化：小写 + 下划线转连字符。 */
    fun normalize(planId: String): String = planId.trim().lowercase().replace('_', '-')

    /**
     * 解析套餐。
     *
     * @return 命中返回 [PlanInfo]；未命中（新套餐或非按额度计费）返回 null —— 调用方必须
     *   如实显示「未知套餐」，不能拿 0 或猜测值冒充。
     */
    fun resolve(planId: String?): PlanInfo? {
        val normalized = planId?.takeIf { it.isNotBlank() }?.let(::normalize) ?: return null
        BY_ID[normalized]?.let { return it }
        // 前缀匹配（按长度倒序），兼容服务端加上环境/版本后缀的情况
        val key = KEYS_BY_LENGTH_DESC.firstOrNull { normalized.startsWith(it) } ?: return null
        return BY_ID[key]
    }
}
