package dev.zxeb.ccusage.net

import dev.zxeb.ccusage.model.UsageError
import dev.zxeb.ccusage.model.UsageSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/**
 * Command Code 用量取数客户端。
 *
 * 四个只读端点（不消耗额度）：
 * | 端点 | 用途 |
 * |---|---|
 * | `GET /alpha/whoami` | 账号与组织（orgId 来源） |
 * | `GET /alpha/billing/credits` | 额度池与限流窗口（**核心**） |
 * | `GET /alpha/billing/subscriptions` | 套餐与计费周期 |
 * | `GET /alpha/usage/summary` | 本计费周期消耗与 token |
 *
 * 工程要点（docs/QUOTA.md §5）：
 * - **并行**发起 whoami / credits / subscriptions，总耗时 = 最慢那个而不是求和；
 * - `summary` 依赖 `subscriptions` 的周期起点，所以必须等前面回来；
 * - **按端点降级**：只有 credits 失败才算整体失败，其余失败只记 warning；
 * - `orgId` 记忆化：首次从 whoami 拿到后，后续刷新直接带上，省一轮往返。
 */
class CommandCodeClient(
    private val transport: HttpTransport,
    private val apiBase: String = DEFAULT_API_BASE,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /** `orgId` 记忆化。个人账号为 null 属于「已知无组织」，不会反复重查。 */
    @Volatile
    private var cachedOrgId: String? = null

    @Volatile
    private var orgIdResolved: Boolean = false

    fun resetOrgCache() {
        cachedOrgId = null
        orgIdResolved = false
    }

    /**
     * 拉取一次完整快照。
     *
     * @param apiKey `user_` 开头的 API Key
     * @param metricsToken 可选的 metrics token；有则用 `/metrics/usage` 拿**账户级累计** token
     * @throws UsageError
     */
    suspend fun fetchSnapshot(apiKey: String, metricsToken: String? = null): UsageSnapshot {
        val key = apiKey.trim()
        if (key.isEmpty()) throw UsageError.MissingKey

        val startedAt = System.currentTimeMillis()
        val failures = mutableListOf<String>()

        val orgQuery = if (orgIdResolved && cachedOrgId != null) "?orgId=$cachedOrgId" else ""

        // ---------- 并行：whoami + credits + subscriptions ----------
        val (whoami, credits, subscriptions) = coroutineScope {
            val whoamiDeferred = async { runOrNull("whoami") { getJson("/alpha/whoami", key) } }
            val creditsDeferred = async {
                // credits 是核心数据：非 4xx 错误自动重试一次；401 等明确错误直接抛出，不浪费重试
                getJsonWithRetry("/alpha/billing/credits$orgQuery", key)
            }
            val subsDeferred = async {
                runOrNull("subscriptions") { getJson("/alpha/billing/subscriptions$orgQuery", key) }
            }
            Triple(whoamiDeferred.await(), creditsDeferred.await(), subsDeferred.await())
        }

        // 首次拿到 orgId 后，用它重取一次，确保额度/周期是按正确的组织维度返回的
        var effectiveCredits = credits
        var effectiveSubs = subscriptions
        val discoveredOrgId = whoami.obj("org").str("id")
        if (!orgIdResolved) {
            orgIdResolved = true
            cachedOrgId = discoveredOrgId
            if (discoveredOrgId != null) {
                val retried = runOrNull("credits(orgId)") {
                    getJson("/alpha/billing/credits?orgId=$discoveredOrgId", key)
                }
                if (retried != null) effectiveCredits = retried
                val retriedSubs = runOrNull("subscriptions(orgId)") {
                    getJson("/alpha/billing/subscriptions?orgId=$discoveredOrgId", key)
                }
                if (retriedSubs != null) effectiveSubs = retriedSubs
            }
        }

        if (whoami == null) failures += "账号信息（whoami）"
        if (effectiveSubs == null) failures += "套餐与计费周期（subscriptions）"

        // ---------- summary：依赖 subscriptions 的周期起点，必须后置 ----------
        val cycleStart = effectiveSubs.obj("data").instant("currentPeriodStart")
        val sinceParam = cycleStart?.let { "&since=${URLEncoder.encode(it.toString(), "UTF-8")}" }
            ?: ""
        val orgParam = cachedOrgId?.let { "orgId=$it" } ?: ""
        val query = buildString {
            if (orgParam.isNotEmpty()) append("?").append(orgParam)
            if (sinceParam.isNotEmpty()) {
                if (orgParam.isEmpty()) append("?") else append("")
                append(sinceParam)
            }
        }
        // 说明：since 实测被服务端忽略（永远按当前计费周期聚合）。仍然传，
        // 万一服务端以后支持，行为会自动变正确；文案按实测口径写。
        val summary = runOrNull("usage/summary") {
            getJson("/alpha/usage/summary$query", key)
        }
        if (summary == null) failures += "本计费周期用量（usage/summary）"

        // ---------- 可选的账户级累计 token ----------
        val metrics = metricsToken?.takeIf { it.isNotBlank() }?.let { token ->
            runOrNull("metrics/usage") {
                getJson("/metrics/usage", token, metricsHeaders = true)
            }
        }

        val finishedAt = System.currentTimeMillis()

        return QuotaProjector.project(
            whoami = whoami,
            credits = effectiveCredits,
            subscriptions = effectiveSubs,
            summary = summary,
            metricsUsage = metrics,
            fetchedAt = Instant.ofEpochMilli(finishedAt),
            durationMs = finishedAt - startedAt,
            partialFailures = failures,
        )
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** credits 的核心取数：非 4xx 失败重试一次。 */
    private suspend fun getJsonWithRetry(path: String, apiKey: String): JsonObject {
        return try {
            getJson(path, apiKey)
        } catch (e: UsageError.Unauthorized) {
            throw e
        } catch (e: UsageError.Http) {
            throw e
        } catch (e: UsageError) {
            // 超时 / 网络错误：重试一次
            delay(RETRY_DELAY_MS)
            getJson(path, apiKey)
        }
    }

    /** 单个端点失败不应拖垮整体：这里把异常吞成 null，由调用方记账。 */
    private suspend fun <T> runOrNull(label: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: UsageError.Unauthorized) {
            // 鉴权失败是全局性的，必须往上抛，让用户看到「Key 失效」
            throw e
        } catch (e: UsageError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getJson(
        path: String,
        token: String,
        metricsHeaders: Boolean = false,
    ): JsonObject {
        val url = "$apiBase$path"
        val headers = if (metricsHeaders) {
            metricsHeaders(token)
        } else {
            cliHeaders(token)
        }
        val response = transport.get(url, headers, timeoutSeconds)

        when {
            response.isSuccess -> Unit
            response.isAuthFailure -> throw UsageError.Unauthorized
            else -> throw UsageError.Http(response.status)
        }

        return response.body.parseJsonObjectOrNull()
            ?: throw UsageError.Malformed("$path 返回的不是 JSON 对象")
    }

    /** 请求头照抄官方 CLI。 */
    private fun cliHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Accept" to "application/json",
        "Accept-Encoding" to "identity",
        "x-cli-environment" to "production",
        "x-command-code-version" to CLI_VERSION,
        "traceparent" to generateTraceparent(),
        "x-project-slug" to "cc-usage-android",
        "User-Agent" to "command-code/$CLI_VERSION",
    )

    private fun metricsHeaders(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
        "x-cli-environment" to "production",
        "x-command-code-version" to CLI_VERSION,
        "traceparent" to generateTraceparent(),
        "User-Agent" to "command-code/$CLI_VERSION",
    )

    /** W3C Trace Context，格式与官方一致：`00-<32hex>-<16hex>-01`。 */
    private fun generateTraceparent(): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        return "00-$traceId-$spanId-01"
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.commandcode.ai"

        /** 官方实测账单端点可慢到 20s+，45s 是文档给出的稳妥值。 */
        const val DEFAULT_TIMEOUT_SECONDS = 45L

        /** `x-command-code-version`：跟随官方 CLI 版本号即可。 */
        const val CLI_VERSION = "0.4.0"

        private const val RETRY_DELAY_MS = 800L
    }
}
