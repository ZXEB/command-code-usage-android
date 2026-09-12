package dev.zxeb.ccusage.model

/**
 * 用户可见的错误。
 *
 * 原则（见 docs/QUOTA.md §6）：
 * - 不把 OkHttp / undici 的英文原文丢给用户；
 * - 错误必须可读、可操作（告诉用户下一步做什么）；
 * - 绝不因为报错就编造或沿用旧数字。
 */
sealed class UsageError(override val message: String) : Exception(message) {

    /** 没有配置 API Key。 */
    object MissingKey : UsageError("还没有配置 API Key，请到「设置」里填写后重试")

    /** API Key 被拒绝。 */
    object Unauthorized : UsageError("API Key 无效或已过期（HTTP 401），请到「设置」里更新")

    /** 服务端明确拒绝且非鉴权问题。 */
    class Http(val status: Int) : UsageError("请求被服务端拒绝（HTTP $status）")

    /** 超时：必须点名是哪条端点，并给出可调项。 */
    class Timeout(val path: String, val seconds: Long) : UsageError(
        "账单接口超时（${seconds}s 无响应）：$path。官方接口本身较慢，可稍后重试",
    )

    /** 网络不可达。 */
    class Network(val detail: String) : UsageError("网络错误：$detail")

    /** 响应结构不符合预期。 */
    class Malformed(val detail: String) : UsageError("接口返回格式异常：$detail")

    /** 核心端点失败导致整体失败。 */
    class Core(val detail: String) : UsageError("读取套餐额度失败：$detail")
}
