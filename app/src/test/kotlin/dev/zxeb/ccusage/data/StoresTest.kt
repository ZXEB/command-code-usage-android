package dev.zxeb.ccusage.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoresTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun sampleSnapshot() = UsageSnapshot(
        accountName = "your-name",
        planId = "individual-go",
        planName = "Go",
        planMonthlyCredits = 10.0,
        subscriptionStatus = "active",
        monthlyRemaining = 4.959,
        purchasedRemaining = 0.0,
        freeRemaining = 0.0,
        totalRemaining = 4.959,
        totalPool = 10.0,
        spent = 5.041,
        usagePercent = 50.4,
        fiveHour = RateWindow("5 小时", 0.072, 3.0, Instant.ofEpochMilli(1789139069459), false, false),
        weekly = RateWindow("每周", 1.833, 6.0, Instant.ofEpochMilli(1789199133465), false, false),
        monthly = RateWindow("每月", 5.041, 10.0, Instant.parse("2026-09-25T14:03:54Z"), false, true),
        cycleStart = Instant.parse("2026-08-25T14:03:54Z"),
        cycleEnd = Instant.parse("2026-09-25T14:03:54Z"),
        tokensTotal = 233_370_995L,
        tokensIn = 231_922_745L,
        tokensOut = 1_448_250L,
        requestCount = 3012L,
        averageCost = 0.0017,
        tokenBasis = TokenBasis.BILLING_PERIOD,
        fetchedAt = Instant.ofEpochMilli(1789138471000L),
        fetchDurationMs = 6000L,
        partialFailures = listOf("账号信息（whoami）"),
    )

    @Test
    fun `snapshot round trips through cache`() {
        val store = SnapshotStore(context)
        store.clear()
        val original = sampleSnapshot()
        store.save(original)
        val loaded = store.load()

        assertEquals(original.accountName, loaded?.accountName)
        assertEquals(original.planName, loaded?.planName)
        assertEquals(original.planMonthlyCredits!!, loaded!!.planMonthlyCredits!!, 0.0001)
        assertEquals(original.totalRemaining!!, loaded.totalRemaining!!, 0.0001)
        assertEquals(original.usagePercent!!, loaded.usagePercent!!, 0.0001)
        assertEquals(original.tokensTotal, loaded.tokensTotal)
        assertEquals(original.tokensIn, loaded.tokensIn)
        assertEquals(original.tokensOut, loaded.tokensOut)
        assertEquals(original.requestCount, loaded.requestCount)
        assertEquals(TokenBasis.BILLING_PERIOD, loaded.tokenBasis)
        assertEquals(original.fetchedAt, loaded.fetchedAt)
        assertEquals(original.fetchDurationMs, loaded.fetchDurationMs)
        assertEquals(original.partialFailures, loaded.partialFailures)
    }

    @Test
    fun `windows survive the round trip`() {
        val store = SnapshotStore(context)
        store.clear()
        store.save(sampleSnapshot())
        val loaded = store.load()!!

        assertEquals(3.0, loaded.fiveHour!!.cap!!, 0.0001)
        assertEquals(1789139069459L, loaded.fiveHour!!.resetAt!!.toEpochMilli())
        assertEquals(6.0, loaded.weekly!!.cap!!, 0.0001)
        assertTrue(loaded.monthly!!.derived)
        assertEquals(Instant.parse("2026-09-25T14:03:54Z"), loaded.monthly!!.resetAt)
    }

    @Test
    fun `null fields stay null through the round trip`() {
        // 关键：缓存不能把「未知」变成 0
        val store = SnapshotStore(context)
        store.clear()
        store.save(UsageSnapshot(fetchedAt = Instant.EPOCH))
        val loaded = store.load()!!
        assertNull(loaded.totalRemaining)
        assertNull(loaded.tokensTotal)
        assertNull(loaded.usagePercent)
        assertNull(loaded.fiveHour)
    }

    @Test
    fun `empty cache loads as null`() {
        val store = SnapshotStore(context)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `corrupt cache does not crash`() {
        val store = SnapshotStore(context)
        store.clear()
        // 直接写入垃圾数据，模拟升级/损坏
        context.getSharedPreferences(SnapshotStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("snapshot", "{not valid json").apply()
        assertNull(store.load())
    }

    @Test
    fun `settings persist credentials and preferences`() {
        val settings = SettingsStore(context)
        settings.apiKey = "  user_test_key  "
        settings.metricsToken = "mt_123"
        settings.autoRefreshMinutes = 30
        settings.widgetShowFiveHour = false

        val reloaded = SettingsStore(context)
        assertEquals("user_test_key", reloaded.apiKey)
        assertEquals("mt_123", reloaded.metricsToken)
        assertEquals(30, reloaded.autoRefreshMinutes)
        assertEquals(false, reloaded.widgetShowFiveHour)
        assertTrue(reloaded.hasApiKey)

        reloaded.clearCredentials()
        assertEquals("", SettingsStore(context).apiKey)
    }

    @Test
    fun `auto refresh interval is clamped`() {
        val settings = SettingsStore(context)
        settings.autoRefreshMinutes = -5
        assertEquals(0, settings.autoRefreshMinutes)
        settings.autoRefreshMinutes = 99999
        assertEquals(24 * 60, settings.autoRefreshMinutes)
    }
}
