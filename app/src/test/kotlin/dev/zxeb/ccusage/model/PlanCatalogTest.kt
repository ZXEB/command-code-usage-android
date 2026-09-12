package dev.zxeb.ccusage.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanCatalogTest {

    @Test
    fun `individual-pro-v1 resolves to 80 not 30`() {
        // 这是最容易踩的坑：individual-pro 是 individual-pro-v1 的前缀。
        // 如果按表内顺序匹配，$80 的 Pro 会被显示成 $30。必须按长度倒序匹配。
        val plan = PlanCatalog.resolve("individual-pro-v1")
        assertEquals("Pro", plan?.displayName)
        assertEquals(80.0, plan!!.monthlyCredits, 0.001)
    }

    @Test
    fun `individual-pro resolves to 30`() {
        val plan = PlanCatalog.resolve("individual-pro")
        assertEquals("Pro", plan?.displayName)
        assertEquals(30.0, plan!!.monthlyCredits, 0.001)
    }

    @Test
    fun `individual-go resolves to 10`() {
        assertEquals(10.0, PlanCatalog.resolve("individual-go")!!.monthlyCredits, 0.001)
    }

    @Test
    fun `underscore plan ids are normalized`() {
        val plan = PlanCatalog.resolve("individual_goat")
        assertEquals("GOAT", plan?.displayName)
        assertEquals(70.0, plan!!.monthlyCredits, 0.001)
    }

    @Test
    fun `plan ids are case insensitive`() {
        assertEquals("Ultra", PlanCatalog.resolve("INDIVIDUAL-ULTRA")?.displayName)
    }

    @Test
    fun `teams-pro resolves`() {
        assertEquals(40.0, PlanCatalog.resolve("teams-pro")!!.monthlyCredits, 0.001)
    }

    @Test
    fun `suffixed plan id still resolves via prefix match`() {
        // 服务端可能加上环境/版本后缀，前缀匹配要能兜住
        assertEquals("Go", PlanCatalog.resolve("individual-go-2026")?.displayName)
    }

    @Test
    fun `unknown plan returns null instead of guessing`() {
        // 未知套餐必须返回 null，让上层显示「未知」，不能拿 0 或猜一个值冒充
        assertNull(PlanCatalog.resolve("individual-enterprise-unknown"))
        assertNull(PlanCatalog.resolve(""))
        assertNull(PlanCatalog.resolve(null))
        assertNull(PlanCatalog.resolve("   "))
    }

    @Test
    fun `normalize lowercases and replaces underscores`() {
        assertEquals("individual-pro-v1", PlanCatalog.normalize("Individual_Pro_V1"))
    }
}
