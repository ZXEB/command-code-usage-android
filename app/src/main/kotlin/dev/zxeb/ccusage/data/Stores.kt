package dev.zxeb.ccusage.data

import android.content.Context
import android.content.SharedPreferences
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * 本地持久化。
 *
 * 用 [SharedPreferences] 而不是数据库，理由有二：
 * 1. 数据量极小（一份快照 + 几个设置项），DB 是过度设计；
 * 2. **小组件跑在 `:widgetProvider` 独立进程**（小米规范强制要求），读 DB 要额外处理
 *    跨进程一致性，而 SharedPreferences 在 MODE_PRIVATE 下跨进程读取的开销和复杂度都低得多。
 *
 * 安全说明：API Key 只存在本机 MODE_PRIVATE 的 SharedPreferences 里，已通过
 * `backup_rules.xml` / `data_extraction_rules.xml` 排除出云备份与设备迁移。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Command Code API Key（`user_` 开头）。 */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    /** 可选的 metrics token，用于取「账户级累计 token」。 */
    var metricsToken: String
        get() = prefs.getString(KEY_METRICS_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_METRICS_TOKEN, value.trim()).apply()
        }

    /** 自动刷新间隔（分钟）。0 表示不自动刷新。 */
    var autoRefreshMinutes: Int
        get() = prefs.getInt(KEY_AUTO_REFRESH, DEFAULT_AUTO_REFRESH_MINUTES)
        set(value) {
            prefs.edit().putInt(KEY_AUTO_REFRESH, value.coerceIn(0, 24 * 60)).apply()
        }

    /** 小组件是否显示 5 小时窗口进度。 */
    var widgetShowFiveHour: Boolean
        get() = prefs.getBoolean(KEY_WIDGET_FIVE_HOUR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIDGET_FIVE_HOUR, value).apply()
        }

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    fun clearCredentials() {
        prefs.edit().remove(KEY_API_KEY).remove(KEY_METRICS_TOKEN).apply()
    }

    companion object {
        const val PREFS_NAME = "cc_usage_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_METRICS_TOKEN = "metrics_token"
        private const val KEY_AUTO_REFRESH = "auto_refresh_minutes"
        private const val KEY_WIDGET_FIVE_HOUR = "widget_five_hour"
        const val DEFAULT_AUTO_REFRESH_MINUTES = 15

        /**
         * 当前时间，供 `Instant.ofEpochMilli` 使用。
         * 单独抽出来是为了让「时钟」在测试里可控。
         */
        fun nowMillis(): Long = System.currentTimeMillis()
    }
}

/**
 * 快照缓存。
 *
 * 存最后一次成功的抓取结果，供两个地方使用：
 * 1. 小组件渲染（小组件进程不能做网络请求的兜底展示）；
 * 2. 刷新失败时向用户明确标注「这是 X 时刻的旧数据」。
 */
class SnapshotStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 写入快照（含原始 JSON，便于排查）。 */
    fun save(snapshot: UsageSnapshot) {
        prefs.edit()
            .putString(KEY_SNAPSHOT, encode(snapshot))
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 读取快照；没有缓存返回 null。 */
    fun load(): UsageSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun savedAtMillis(): Long = prefs.getLong(KEY_SAVED_AT, 0L)

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).remove(KEY_SAVED_AT).apply()
    }

    // ------------------------------------------------------------------
    // 手写编解码：字段少、结构扁平，比引入 @Serializable 更省心，
    // 也避免服务端字段变动时整个缓存反序列化失败。
    // ------------------------------------------------------------------

    private fun encode(snapshot: UsageSnapshot): String = buildJsonObject {
        snapshot.accountName?.let { put("accountName", it) }
        snapshot.planId?.let { put("planId", it) }
        snapshot.planName?.let { put("planName", it) }
        snapshot.planMonthlyCredits?.let { put("planMonthlyCredits", it) }
        snapshot.subscriptionStatus?.let { put("subscriptionStatus", it) }
        snapshot.monthlyRemaining?.let { put("monthlyRemaining", it) }
        snapshot.purchasedRemaining?.let { put("purchasedRemaining", it) }
        snapshot.freeRemaining?.let { put("freeRemaining", it) }
        snapshot.totalRemaining?.let { put("totalRemaining", it) }
        snapshot.totalPool?.let { put("totalPool", it) }
        snapshot.spent?.let { put("spent", it) }
        snapshot.usagePercent?.let { put("usagePercent", it) }
        snapshot.cycleStart?.let { put("cycleStart", it.toEpochMilli()) }
        snapshot.cycleEnd?.let { put("cycleEnd", it.toEpochMilli()) }
        snapshot.tokensTotal?.let { put("tokensTotal", it) }
        snapshot.tokensIn?.let { put("tokensIn", it) }
        snapshot.tokensOut?.let { put("tokensOut", it) }
        snapshot.requestCount?.let { put("requestCount", it) }
        snapshot.averageCost?.let { put("averageCost", it) }
        put("tokenBasis", snapshot.tokenBasis.name)
        put("fetchedAt", snapshot.fetchedAt.toEpochMilli())
        put("fetchDurationMs", snapshot.fetchDurationMs)
        snapshot.fiveHour?.let { put("fiveHour", encodeWindow(it)) }
        snapshot.weekly?.let { put("weekly", encodeWindow(it)) }
        snapshot.monthly?.let { put("monthly", encodeWindow(it)) }
        if (snapshot.partialFailures.isNotEmpty()) {
            put("partialFailures", snapshot.partialFailures.joinToString("|"))
        }
    }.toString()

    private fun encodeWindow(window: RateWindow): JsonObject = buildJsonObject {
        put("label", window.label)
        window.used?.let { put("used", it) }
        window.cap?.let { put("cap", it) }
        window.resetAt?.let { put("resetAt", it.toEpochMilli()) }
        put("exceeded", window.exceeded)
        put("derived", window.derived)
    }

    private fun decode(raw: String): UsageSnapshot {
        val obj = json.parseToJsonElement(raw) as JsonObject

        fun d(key: String): Double? = obj[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }
        fun l(key: String): Long? = obj[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }
        fun s(key: String): String? = obj[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        fun b(key: String): Boolean? = obj[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull }
        fun instant(key: String): Instant? = l(key)?.let { Instant.ofEpochMilli(it) }

        fun window(key: String): RateWindow? {
            val node = obj[key] as? JsonObject ?: return null
            fun wd(k: String): Double? = node[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }
            fun wl(k: String): Long? = node[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }
            fun wb(k: String): Boolean? = node[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull }
            return RateWindow(
                label = node["label"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                used = wd("used"),
                cap = wd("cap"),
                resetAt = wl("resetAt")?.let { Instant.ofEpochMilli(it) },
                exceeded = wb("exceeded") ?: false,
                derived = wb("derived") ?: false,
            )
        }

        return UsageSnapshot(
            accountName = s("accountName"),
            planId = s("planId"),
            planName = s("planName"),
            planMonthlyCredits = d("planMonthlyCredits"),
            subscriptionStatus = s("subscriptionStatus"),
            monthlyRemaining = d("monthlyRemaining"),
            purchasedRemaining = d("purchasedRemaining"),
            freeRemaining = d("freeRemaining"),
            totalRemaining = d("totalRemaining"),
            totalPool = d("totalPool"),
            spent = d("spent"),
            usagePercent = d("usagePercent"),
            fiveHour = window("fiveHour"),
            weekly = window("weekly"),
            monthly = window("monthly"),
            cycleStart = instant("cycleStart"),
            cycleEnd = instant("cycleEnd"),
            tokensTotal = l("tokensTotal"),
            tokensIn = l("tokensIn"),
            tokensOut = l("tokensOut"),
            requestCount = l("requestCount"),
            averageCost = d("averageCost"),
            tokenBasis = runCatching { TokenBasis.valueOf(s("tokenBasis").orEmpty()) }
                .getOrDefault(TokenBasis.UNKNOWN),
            fetchedAt = instant("fetchedAt") ?: Instant.EPOCH,
            fetchDurationMs = l("fetchDurationMs") ?: 0L,
            partialFailures = s("partialFailures")?.split("|")?.filter { it.isNotBlank() }.orEmpty(),
            source = DataSource.LIVE,
        )
    }

    companion object {
        const val PREFS_NAME = "cc_usage_cache"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_SAVED_AT = "saved_at"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
