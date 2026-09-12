package dev.zxeb.ccusage.net

import dev.zxeb.ccusage.model.TokenBasis
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 额度归一化测试。
 *
 * 用的 JSON 结构直接取自 docs/QUOTA.md 记录的**真实返回**，专门锁死三个已知的坑：
 * 1. `windowLimits` 是**顶层**字段，和 `credits` 平级，**不在** credits 里面；
 * 2. 窗口上限字段名是 **`cap`**，不是 `limit`；
 * 3. `resetAt` 是 **epoch 毫秒**，不是 ISO 字符串。
 */
class QuotaProjectorTest {

    private val fetchedAt = Instant.parse("2026-09-11T14:54:31Z")

    /** 与 docs/QUOTA.md「真实返回长什么样」一节一致。 */
    private val creditsJson = """
        {
          "credits": {
            "belowThreshold": false,
            "creditThreshold": 0,
            "monthlyCredits": 4.9590200369,
            "purchasedCredits": 0,
            "freeCredits": 0
          },
          "windowLimits": {
            "limited": true,
            "exceeded": null,
            "fiveHour": { "used": 0.07209606, "cap": 3, "exceeded": false, "resetAt": 1789139069459 },
            "weekly":   { "used": 1.833011698, "cap": 6, "exceeded": false, "resetAt": 1789199133465 }
          }
        }
    """.trimIndent()

    private val subscriptionsJson = """
        {
          "success": true,
          "data": {
            "planId": "individual-go",
            "status": "active",
            "currentPeriodStart": "2026-08-25T14:03:54.000Z",
            "currentPeriodEnd": "2026-09-25T14:03:54.000Z"
          }
        }
    """.trimIndent()

    private val summaryJson = """
        {
          "totalCost": 5.0409799631,
          "totalCount": 3012,
          "totalTokens": 233370995,
          "totalTokensIn": 231922745,
          "totalTokensOut": 1448250,
          "averageCost": 0.0017,
          "periodBasis": "billing-period"
        }
    """.trimIndent()

    private val whoamiJson = """
        { "user": { "name": "your-name" }, "org": { "id": "org_123" } }
    """.trimIndent()

    private fun project(
        whoami: String? = whoamiJson,
        credits: String = creditsJson,
        subscriptions: String? = subscriptionsJson,
        summary: String? = summaryJson,
        metrics: String? = null,
    ) = QuotaProjector.project(
        whoami = whoami?.parseJsonObjectOrNull(),
        credits = credits.parseJsonObjectOrNull()!!,
        subscriptions = subscriptions?.parseJsonObjectOrNull(),
        summary = summary?.parseJsonObjectOrNull(),
        metricsUsage = metrics?.parseJsonObjectOrNull(),
        fetchedAt = fetchedAt,
        durationMs = 6000,
        partialFailures = emptyList(),
    )

    // ---------------- 坑 1：windowLimits 在顶层 ----------------

    @Test
    fun `windowLimits is read from the top level not from inside credits`() {
        val snapshot = project()
        assertNotNull("5 小时窗口必须能解析出来（windowLimits 在顶层）", snapshot.fiveHour)
        assertNotNull("每周窗口必须能解析出来", snapshot.weekly)
        assertEquals(3.0, snapshot.fiveHour!!.cap!!, 0.0001)
        assertEquals(6.0, snapshot.weekly!!.cap!!, 0.0001)
    }

    @Test
    fun `windowLimits inside credits still parses for forward compatibility`() {
        // 万一服务端哪天挪进 credits 内部，也不能解析成空窗口
        val nested = """
            {
              "credits": {
                "monthlyCredits": 5.0,
                "purchasedCredits": 0,
                "freeCredits": 0,
                "windowLimits": {
                  "limited": true,
                  "fiveHour": { "used": 1.0, "cap": 3, "resetAt": 1789139069459 }
                }
              }
            }
        """.trimIndent()
        val snapshot = project(credits = nested)
        assertNotNull(snapshot.fiveHour)
        assertEquals(3.0, snapshot.fiveHour!!.cap!!, 0.0001)
    }

    // ---------------- 坑 2：字段名是 cap 不是 limit ----------------

    @Test
    fun `cap field is used and limit is ignored`() {
        val wrong = """
            {
              "credits": { "monthlyCredits": 5.0, "purchasedCredits": 0, "freeCredits": 0 },
              "windowLimits": {
                "limited": true,
                "fiveHour": { "used": 1.0, "limit": 3, "resetAt": 1789139069459 }
              }
            }
        """.trimIndent()
        val snapshot = project(credits = wrong)
        // limit 不是服务端字段名；cap 缺失时数值应为 null（未知），而不是拿 limit 顶上
        assertNull(snapshot.fiveHour?.cap)
        assertNull(snapshot.fiveHour?.percent)
    }

    // ---------------- 坑 3：resetAt 是 epoch 毫秒 ----------------

    @Test
    fun `resetAt epoch millis parses correctly`() {
        val snapshot = project()
        val resetAt = snapshot.fiveHour!!.resetAt
        assertNotNull(resetAt)
        // 1789139069459 ms = 2026-09-12T03:04:29.459Z
        assertEquals(1789139069459L, resetAt!!.toEpochMilli())
    }

    @Test
    fun `resetAt also accepts seconds and iso strings`() {
        val mixed = """
            {
              "credits": { "monthlyCredits": 5.0, "purchasedCredits": 0, "freeCredits": 0 },
              "windowLimits": {
                "limited": true,
                "fiveHour": { "used": 1.0, "cap": 3, "resetAt": 1789139069 },
                "weekly":   { "used": 1.0, "cap": 6, "resetAt": "2026-09-12T15:45:00Z" }
              }
            }
        """.trimIndent()
        val snapshot = project(credits = mixed)
        // 秒级时间戳（量级更小）也应当被识别
        assertEquals(1789139069_000L, snapshot.fiveHour!!.resetAt!!.toEpochMilli())
        assertEquals("2026-09-12T15:45:00Z", snapshot.weekly!!.resetAt.toString())
    }

    // ---------------- 额度池计算 ----------------

    @Test
    fun `remaining sums monthly purchased and free`() {
        val snapshot = project()
        assertEquals(4.9590200369, snapshot.totalRemaining!!, 0.0000001)
    }

    @Test
    fun `pool uses max of nominal and remaining to avoid negative progress`() {
        // 额度被补发时 monthlyCredits 可能高于标称值；直接用标称值当分母会出现
        // 「剩余 > 总额」，进度条算成负数。取两者较大值即可避免。
        val boosted = """
            {
              "credits": { "monthlyCredits": 25.0, "purchasedCredits": 0, "freeCredits": 0 },
              "windowLimits": { "limited": false }
            }
        """.trimIndent()
        val snapshot = project(credits = boosted)
        // Go 标称 $10，但剩余 $25 -> 分母必须是 25
        assertEquals(25.0, snapshot.totalPool!!, 0.0001)
        assertTrue("进度不能为负", snapshot.usagePercent!! >= 0.0)
        assertEquals(0.0, snapshot.usagePercent!!, 0.0001)
    }

    @Test
    fun `usage percent computes from pool and remaining`() {
        val snapshot = project()
        // 池 = max(10, 4.959) = 10；已用 = 10 - 4.959 = 5.041 -> 50.4%
        assertEquals(10.0, snapshot.totalPool!!, 0.0001)
        assertEquals(50.4, snapshot.usagePercent!!, 0.2)
    }

    @Test
    fun `inactive subscription falls back to spent plus remaining`() {
        val canceled = """
            {
              "success": true,
              "data": { "planId": "individual-go", "status": "canceled",
                        "currentPeriodEnd": "2026-09-25T14:03:54.000Z" }
            }
        """.trimIndent()
        val snapshot = project(subscriptions = canceled)
        // 非 active 时不拿标称额度当分母，改用 spent + remaining
        assertEquals(5.0409799631 + 4.9590200369, snapshot.totalPool!!, 0.0001)
    }

    // ---------------- 每月窗口自算 ----------------

    @Test
    fun `monthly window is derived from billing cycle when server omits it`() {
        val snapshot = project()
        assertNotNull(snapshot.monthly)
        assertTrue("应标记为本地推算", snapshot.monthly!!.derived)
        assertEquals(10.0, snapshot.monthly!!.cap!!, 0.0001)
        // 重置时间取计费周期结束时间
        assertEquals(Instant.parse("2026-09-25T14:03:54Z"), snapshot.monthly!!.resetAt)
    }

    @Test
    fun `server provided monthly window wins over derived one`() {
        val withMonthly = """
            {
              "credits": { "monthlyCredits": 5.0, "purchasedCredits": 0, "freeCredits": 0 },
              "windowLimits": {
                "limited": true,
                "monthly": { "used": 2.0, "cap": 20.0, "resetAt": 1789139069459 }
              }
            }
        """.trimIndent()
        val snapshot = project(credits = withMonthly)
        assertEquals(20.0, snapshot.monthly!!.cap!!, 0.0001)
        assertEquals(false, snapshot.monthly!!.derived)
    }

    @Test
    fun `limited false hides all windows`() {
        val unlimited = """
            {
              "credits": { "monthlyCredits": 5.0, "purchasedCredits": 0, "freeCredits": 0 },
              "windowLimits": { "limited": false }
            }
        """.trimIndent()
        val snapshot = project(credits = unlimited)
        assertNull(snapshot.fiveHour)
        assertNull(snapshot.weekly)
    }

    // ---------------- token ----------------

    @Test
    fun `tokens come from usage summary with billing period basis`() {
        val snapshot = project()
        assertEquals(233_370_995L, snapshot.tokensTotal)
        assertEquals(231_922_745L, snapshot.tokensIn)
        assertEquals(1_448_250L, snapshot.tokensOut)
        assertEquals(3012L, snapshot.requestCount)
        // 必须如实标注「本计费周期」，不能含糊写成累计
        assertEquals(TokenBasis.BILLING_PERIOD, snapshot.tokenBasis)
    }

    @Test
    fun `metrics usage overrides summary and switches basis`() {
        val metrics = """
            { "totalTokens": 9000000000, "totalTokensIn": 8000000000,
              "totalTokensOut": 1000000000, "totalCount": 99999, "averageCost": 0.0031 }
        """.trimIndent()
        val snapshot = project(metrics = metrics)
        assertEquals(9_000_000_000L, snapshot.tokensTotal)
        assertEquals(TokenBasis.ACCOUNT_TOTAL, snapshot.tokenBasis)
        assertEquals(99_999L, snapshot.requestCount)
    }

    // ---------------- 容错 / 降级 ----------------

    @Test
    fun `missing subscription still yields credits data`() {
        val snapshot = project(subscriptions = null)
        assertEquals(4.9590200369, snapshot.totalRemaining!!, 0.0000001)
        // 没有套餐时无法用标称额度当分母，退回 spent + remaining
        assertNotNull(snapshot.totalPool)
        assertNull(snapshot.cycleEnd)
    }

    @Test
    fun `missing summary yields null tokens not zero`() {
        val snapshot = project(summary = null)
        assertNull(snapshot.tokensTotal)
        assertNull(snapshot.requestCount)
        assertEquals(TokenBasis.UNKNOWN, snapshot.tokenBasis)
        // 额度仍然可用
        assertNotNull(snapshot.totalRemaining)
    }

    @Test
    fun `missing whoami does not break the rest`() {
        val snapshot = project(whoami = null)
        assertNull(snapshot.accountName)
        assertNotNull(snapshot.totalRemaining)
    }

    @Test
    fun `null credit fields stay null instead of becoming zero`() {
        // 服务端没返回额度数字时，必须提示「未知」而不是显示 $0.00
        val noCredits = """
            { "credits": { "belowThreshold": false }, "windowLimits": { "limited": false } }
        """.trimIndent()
        val snapshot = project(credits = noCredits)
        assertNull(snapshot.monthlyRemaining)
        assertNull(snapshot.totalRemaining)
        assertNull(snapshot.usagePercent)
    }

    @Test
    fun `unknown plan id keeps raw id and null nominal credits`() {
        val unknownPlan = """
            { "success": true, "data": { "planId": "individual-mystery", "status": "active" } }
        """.trimIndent()
        val snapshot = project(subscriptions = unknownPlan)
        assertEquals("individual-mystery", snapshot.planId)
        assertEquals("individual-mystery", snapshot.planName)
        assertNull(snapshot.planMonthlyCredits)
    }

    @Test
    fun `numeric strings are accepted for numeric fields`() {
        val stringy = """
            {
              "credits": { "monthlyCredits": "4.5", "purchasedCredits": "0", "freeCredits": "0" },
              "windowLimits": { "limited": true,
                "fiveHour": { "used": "1.5", "cap": "3", "resetAt": "1789139069459" } }
            }
        """.trimIndent()
        val snapshot = project(credits = stringy)
        assertEquals(4.5, snapshot.monthlyRemaining!!, 0.0001)
        assertEquals(3.0, snapshot.fiveHour!!.cap!!, 0.0001)
        assertEquals(50.0, snapshot.fiveHour!!.percent!!, 0.01)
    }

    @Test
    fun `exceeded flag is surfaced`() {
        val exceeded = """
            {
              "credits": { "monthlyCredits": 0.0, "purchasedCredits": 0, "freeCredits": 0 },
              "windowLimits": { "limited": true,
                "fiveHour": { "used": 4.0, "cap": 3, "exceeded": true, "resetAt": 1789139069459 } }
            }
        """.trimIndent()
        val snapshot = project(credits = exceeded)
        assertEquals(true, snapshot.fiveHour!!.exceeded)
        // 超限时百分比封顶在 100
        assertEquals(100.0, snapshot.fiveHour!!.percent!!, 0.0001)
    }

    @Test
    fun `account name falls back through user fields`() {
        assertEquals("your-name", project().accountName)
        val emailOnly = """{ "user": { "email": "a@b.c" } }"""
        assertEquals("a@b.c", project(whoami = emailOnly).accountName)
    }

    @Test
    fun `org id is captured`() {
        assertEquals("org_123", project().orgId)
    }

    @Test
    fun `malformed json returns null rather than throwing`() {
        assertNull("not json at all".parseJsonObjectOrNull())
        assertNull("".parseJsonObjectOrNull())
        assertNull(null.parseJsonObjectOrNull())
    }

    @Test
    fun `json helpers tolerate wrong types`() {
        val obj: JsonObject = """{"a":"x","b":true,"c":null}""".parseJsonObjectOrNull()!!
        assertNull(obj.num("a"))
        assertNull(obj.num("b"))
        assertNull(obj.num("c"))
        assertNull(obj.str("c"))
        assertEquals(true, obj.bool("b"))
    }
}
