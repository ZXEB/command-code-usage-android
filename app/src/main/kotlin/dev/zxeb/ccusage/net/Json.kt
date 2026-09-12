package dev.zxeb.ccusage.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * 容错的 JSON 取值工具。
 *
 * 服务端字段名/类型都可能变，而且 docs/QUOTA.md 里的坑（`windowLimits` 在顶层、
 * 上限字段叫 `cap`、`resetAt` 是 epoch 毫秒）都要求我们**逐字段安全地取**：
 * 取不到就返回 null，交给上层显示 `--`，绝不能默认成 0。
 */
internal val LENIENT_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal fun String?.parseJsonObjectOrNull(): JsonObject? {
    if (this.isNullOrBlank()) return null
    return runCatching { LENIENT_JSON.parseToJsonElement(this) as? JsonObject }.getOrNull()
}

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

internal fun JsonObject?.obj(key: String): JsonObject? {
    val v = this?.get(key) ?: return null
    if (v is JsonNull) return null
    return v as? JsonObject
}

internal fun JsonObject?.arr(key: String): JsonArray? {
    val v = this?.get(key) ?: return null
    if (v is JsonNull) return null
    return v as? JsonArray
}

/** 字符串取值；空串视为 null（服务端常用空串表示「没有」）。 */
internal fun JsonObject?.str(key: String): String? {
    val v = this?.get(key) ?: return null
    if (v is JsonNull) return null
    val p = v as? JsonPrimitive ?: return null
    return p.content.takeIf { it.isNotBlank() }
}

/** 数字取值，同时接受数字与数字字符串。 */
internal fun JsonObject?.num(key: String): Double? {
    val v = this?.get(key) ?: return null
    if (v is JsonNull) return null
    val p = v as? JsonPrimitive ?: return null
    p.doubleOrNull?.let { return it }
    return p.content.trim().toDoubleOrNull()
}

internal fun JsonObject?.long(key: String): Long? {
    val v = this?.get(key) ?: return null
    if (v is JsonNull) return null
    val p = v as? JsonPrimitive ?: return null
    p.longOrNull?.let { return it }
    return p.content.trim().toDoubleOrNull()?.toLong()
}

internal fun JsonObject?.bool(key: String): Boolean? {
    val v = this?.get(key) ?: return null
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.booleanOrNull
}

internal fun JsonObject?.int(key: String): Int? = long(key)?.toInt()

/**
 * 解析时间字段。
 *
 * **关键**：额度窗口的 `resetAt` 是 **epoch 毫秒**（不是 ISO 字符串）。这里同时兼容
 * 毫秒 / 秒 / ISO 字符串三种形态，避免按常识猜错类型导致 `Invalid Date`。
 */
internal fun parseInstantFlexible(element: JsonElement?): Instant? {
    if (element == null || element is JsonNull) return null

    val primitive = element as? JsonPrimitive

    // 数字：按量级判断是毫秒还是秒（> 1e11 视为毫秒）
    val numeric = primitive?.doubleOrNull ?: primitive?.content?.trim()?.toDoubleOrNull()
    if (numeric != null) {
        if (!numeric.isFinite() || numeric <= 0.0) return null
        return runCatching {
            if (numeric > 100_000_000_000.0) {
                Instant.ofEpochMilli(numeric.toLong())
            } else {
                Instant.ofEpochSecond(numeric.toLong())
            }
        }.getOrNull()
    }

    // 字符串：ISO-8601
    val text = primitive?.content?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(text) }
        .recoverCatching { java.time.OffsetDateTime.parse(text).toInstant() }
        .getOrNull()
}

internal fun JsonObject?.instant(key: String): Instant? = parseInstantFlexible(this?.get(key))
