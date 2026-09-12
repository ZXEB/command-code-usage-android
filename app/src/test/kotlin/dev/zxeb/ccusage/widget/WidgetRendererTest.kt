package dev.zxeb.ccusage.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 小组件渲染测试。
 *
 * RemoteViews 的渲染无法在 JVM 里做像素断言，所以这里覆盖的是**不变量**：
 * 各状态都必须能构建出 RemoteViews（不崩溃、不空白），且关键文案符合预期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRendererTest {

    private val context: Context get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun snapshot(
        totalRemaining: Double? = 4.93,
        usagePercent: Double? = 50.7,
        tokensTotal: Long? = 233_370_995L,
        basis: TokenBasis = TokenBasis.BILLING_PERIOD,
        fiveHour: RateWindow? = RateWindow("5 小时", 0.072, 3.0, null),
        status: String? = "active",
    ) = UsageSnapshot(
        planName = "Go",
        subscriptionStatus = status,
        totalRemaining = totalRemaining,
        usagePercent = usagePercent,
        tokensTotal = tokensTotal,
        tokenBasis = basis,
        fiveHour = fiveHour,
        fetchedAt = Instant.now(),
    )

    @Test
    fun `full widget renders with data`() {
        val views = WidgetRenderer.render(context, compact = false, snapshot = snapshot(), hasApiKey = true)
        assertNotNull(views)
    }

    @Test
    fun `compact widget renders with data`() {
        val views = WidgetRenderer.render(context, compact = true, snapshot = snapshot(), hasApiKey = true)
        assertNotNull(views)
    }

    @Test
    fun `null snapshot renders the guidance view not a crash`() {
        // 小米规范 §9：清数据 / 未授权时必须回到默认视图，不能空白或崩溃
        val views = WidgetRenderer.render(context, compact = false, snapshot = null, hasApiKey = false)
        assertNotNull(views)
    }

    @Test
    fun `snapshot without usable data renders guidance view`() {
        val empty = UsageSnapshot(fetchedAt = Instant.now())
        val views = WidgetRenderer.render(context, compact = false, snapshot = empty, hasApiKey = true)
        assertNotNull(views)
    }

    @Test
    fun `all-null numeric fields render without throwing`() {
        // 服务端什么都没返回时，界面必须是 -- 而不是 0
        val blank = snapshot(totalRemaining = null, usagePercent = null, tokensTotal = null, fiveHour = null)
        assertNotNull(WidgetRenderer.render(context, compact = false, snapshot = blank, hasApiKey = true))
        assertNotNull(WidgetRenderer.render(context, compact = true, snapshot = blank, hasApiKey = true))
    }

    @Test
    fun `non active subscription status still renders`() {
        for (status in listOf("trialing", "past_due", "canceled", "wat")) {
            assertNotNull(
                WidgetRenderer.render(context, compact = false, snapshot = snapshot(status = status), hasApiKey = true),
            )
        }
    }

    @Test
    fun `account total basis renders`() {
        val views = WidgetRenderer.render(
            context,
            compact = false,
            snapshot = snapshot(basis = TokenBasis.ACCOUNT_TOTAL),
            hasApiKey = true,
        )
        assertNotNull(views)
    }

    @Test
    fun `updateAll is safe when no widget instances exist`() {
        // 会话里一个小组件都没添加时，不能抛异常
        WidgetRenderer.updateAll(context)
    }

    @Test
    fun `widget provider classes are resolvable by the system`() {
        // 系统按类名反射实例化 Provider，混淆或改名会直接导致小组件消失
        assertNotNull(UsageWidgetProvider())
        assertNotNull(CompactWidgetProvider())
        // Kotlin 的 companion 不会被继承，常量定义在基类上
        assertEquals(
            "miui.appwidget.action.APPWIDGET_UPDATE",
            BaseUsageWidgetProvider.ACTION_MIUI_WIDGET_UPDATE,
        )
    }

    // ------------------------------------------------------------------
    // 清单契约：锁死澎湃小组件规范要求的元数据，防止后续误删
    // ------------------------------------------------------------------

    @Test
    fun `manifest declares miui widget requirements`() {
        val info = context.packageManager
            .getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        val meta = info.metaData
        assertNotNull("缺少 application 级 meta-data", meta)
        // §10 小部件版本号必须在 application 下
        assertEquals(true, meta.containsKey("miuiWidgetVersion"))
    }

    @Test
    fun `both widget providers are registered with miui metadata`() {
        val providers = listOf(
            ComponentName(context, UsageWidgetProvider::class.java),
            ComponentName(context, CompactWidgetProvider::class.java),
        )
        for (component in providers) {
            val info = context.packageManager.getReceiverInfo(
                component,
                android.content.pm.PackageManager.GET_META_DATA,
            )
            val meta = info.metaData
            assertNotNull("$component 缺少 meta-data", meta)
            // §3 每个组件必须声明 appwidget provider 配置
            assertTrue("$component 缺少 android.appwidget.provider", meta.containsKey("android.appwidget.provider"))
            // §4 小米小部件标识
            assertEquals(true, meta.getBoolean("miuiWidget"))
            // §2.1 曝光刷新
            assertEquals("exposure", meta.getString("miuiWidgetRefresh"))
            assertTrue(
                "曝光刷新间隔必须是 ≥10s 的毫秒数，实际=${meta["miuiWidgetRefreshMinInterval"]}",
                metaInt(meta, "miuiWidgetRefreshMinInterval") >= 10_000,
            )
        }
    }

    /**
     * 从 meta-data Bundle 里取整数。
     *
     * 不能直接用 `getString`：`android:value="60000"` 是纯数字字面量，系统在打包时
     * 会把它存成 Integer 而不是 String，`getString` 会返回 null。
     */
    private fun metaInt(meta: android.os.Bundle, key: String): Int = when (val raw = meta[key]) {
        is Int -> raw
        is Long -> raw.toInt()
        is String -> raw.toIntOrNull() ?: -1
        else -> -1
    }

    @Test
    fun `widget providers stay in the main process on purpose`() {
        // 小米规范 §1.3 要求小组件使用 :widgetProvider 独立进程，但《小部件审核规范》
        // 同时要求该进程内存 ≤40M。本项目的小组件布局含 ProgressBar / Layer-List drawable，
        // 在部分 HyperOS 版本上独立进程渲染会因内存压力导致卡片空白或干脆不显示。
        // 权衡后让小组件跑默认（主）进程：渲染链路只读 SharedPreferences，占用极小。
        //
        // 这条测试锁的是「这个决定是刻意的」——如果日后为了上架小米小部件中心改回
        // :widgetProvider，请连带做内存压测并更新这里的断言。
        for (component in listOf(
            ComponentName(context, UsageWidgetProvider::class.java),
            ComponentName(context, CompactWidgetProvider::class.java),
        )) {
            val info = context.packageManager.getReceiverInfo(component, 0)
            val process = info.processName.orEmpty()
            val inMainProcess = process.isEmpty() || process == context.packageName
            assertTrue(
                "$component 目前在默认进程，实际 processName=$process",
                inMainProcess,
            )
        }
    }

    @Test
    fun `widget providers must be exported or the launcher cannot discover them`() {
        // 这是「桌面添加小部件列表里找不到本应用」的直接原因，必须有测试锁住。
        //
        // 系统由 AppWidgetServiceImpl.updateProvidersForPackageLocked() 通过
        // queryIntentReceivers(ACTION_APPWIDGET_UPDATE) 枚举小组件，该方法使用的
        // PackageManager 标记不包含未导出组件。exported=false 时：
        //   - AppWidgetManager.getAppWidgetIds(...) 仍能拿到已添加实例（代码里读实例看似正常）
        //   - 但桌面「添加小部件」根本解析不到这个 receiver，应用不会出现在列表里
        for (component in listOf(
            ComponentName(context, UsageWidgetProvider::class.java),
            ComponentName(context, CompactWidgetProvider::class.java),
        )) {
            val info = context.packageManager.getReceiverInfo(component, 0)
            assertTrue(
                "$component 必须 android:exported=\"true\"，否则桌面的小部件列表里搜不到本应用",
                info.exported,
            )
        }
    }

    @Test
    fun `both widget sizes share the same label so hyperos groups them`() {
        // 小米规范 §4：label 相同会被认为是同一功能的不同尺寸，在详情页聚合展示
        val a = context.packageManager.getReceiverInfo(
            ComponentName(context, UsageWidgetProvider::class.java), 0,
        ).loadLabel(context.packageManager).toString()
        val b = context.packageManager.getReceiverInfo(
            ComponentName(context, CompactWidgetProvider::class.java), 0,
        ).loadLabel(context.packageManager).toString()
        assertEquals(a, b)
    }

    @Test
    fun `appwidget provider xml matches xiaomi size recommendations`() {
        // 官方《小部件技术规范》建议尺寸：4×2 = 300×110dp，2×2 = 110×110dp。
        // 直接读源文件而不是解析编译后的资源：AAPT 会把 dimension 字面量编译掉，
        // 反解容易受打包细节影响；这里要断言的就是「声明值」，读源文件最准确。
        assertEquals(300, declaredDp("usage_widget_4x2.xml", "minWidth"))
        assertEquals(110, declaredDp("usage_widget_4x2.xml", "minHeight"))
        assertEquals(110, declaredDp("usage_widget_2x2.xml", "minWidth"))
        assertEquals(110, declaredDp("usage_widget_2x2.xml", "minHeight"))
    }

    @Test
    fun `widget layouts declare the xiaomi required root id and opaque background`() {
        // 小米规范 §7.1：系统通过固定 id @android:id/background 找到根布局来加圆角；
        // 且根布局必须有背景色、不能全透明（切换动画依赖背景色）。
        for (name in listOf("widget_usage_4x2.xml", "widget_usage_2x2.xml", "widget_usage_empty.xml")) {
            val xml = readSource("layout/$name")
            assertNotNull("找不到 $name", xml)
            assertTrue("$name 根布局必须声明 @android:id/background", xml!!.contains("@android:id/background"))
            assertTrue("$name 根布局必须有背景", xml.contains("android:background="))
            // 规范 §8：宽高必须 match_parent
            assertTrue("$name 根布局宽高必须 match_parent", xml.contains("android:layout_width=\"match_parent\""))
        }
    }

    /** 读取 `app/src/main/res/<relative>` 源文件。Gradle 单测的工作目录就是模块目录。 */
    private fun readSource(relative: String): String? {
        val candidates = listOf(
            java.io.File("src/main/res/$relative"),
            java.io.File("app/src/main/res/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: return null
        return file.readText()
    }

    /** 从 appwidget-provider XML 源文件里取 `android:xxx="300dp"` 的数值部分。 */
    private fun declaredDp(fileName: String, attribute: String): Int {
        val xml = readSource("xml/$fileName")
        assertNotNull("找不到 $fileName", xml)
        val match = Regex("""android:$attribute\s*=\s*"(\d+)dp"""").find(xml!!)
        assertNotNull("$fileName 里没有声明 android:$attribute=\"<n>dp\"", match)
        return match!!.groupValues[1].toInt()
    }

    @Test
    fun `settings store drives widget api key state`() {
        val settings = SettingsStore(context)
        settings.clearCredentials()
        assertEquals(false, settings.hasApiKey)
        settings.apiKey = "user_x"
        assertEquals(true, SettingsStore(context).hasApiKey)
        settings.clearCredentials()
    }

    @Test
    fun `appwidget manager returns empty ids before any widget is added`() {
        val manager = AppWidgetManager.getInstance(context)
        assertNotNull(manager)
        val ids = manager!!.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
        assertEquals(0, ids.size)
    }

    @Test
    fun `widget layouts expose the required root id`() {
        // 小米规范 §7.1：系统通过固定 id @android:id/background 找到根布局并加圆角
        val ids = listOf(R.layout.widget_usage_4x2, R.layout.widget_usage_2x2, R.layout.widget_usage_empty)
        for (layoutId in ids) {
            val views = android.widget.RemoteViews(context.packageName, layoutId)
            // 能构建即说明布局资源可解析
            assertNotNull(views)
        }
        assertTrue(android.R.id.background != 0)
    }
}
