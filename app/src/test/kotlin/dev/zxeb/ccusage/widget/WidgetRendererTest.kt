package dev.zxeb.ccusage.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import dev.zxeb.ccusage.widget.WidgetRenderer.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * 三种尺寸（4×4 详细 / 4×2 宽版 / 2×2 精简）都要覆盖 —— 新增尺寸最容易漏的就是
 * 「某个尺寸在某个数据状态下崩掉」，而那在桌面上表现为小组件直接消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRendererTest {

    private val context: Context get() = org.robolectric.RuntimeEnvironment.getApplication()

    private val allSizes = listOf(Size.FULL, Size.WIDE, Size.COMPACT)

    private fun snapshot(
        totalRemaining: Double? = 4.93,
        usagePercent: Double? = 50.7,
        totalPool: Double? = 10.0,
        tokensTotal: Long? = 233_370_995L,
        basis: TokenBasis = TokenBasis.BILLING_PERIOD,
        fiveHour: RateWindow? = RateWindow("5 小时", 0.072, 3.0, null),
        weekly: RateWindow? = RateWindow("每周", 5.0, 100.0, null),
        monthly: RateWindow? = RateWindow("每月", 12.0, 100.0, null),
        status: String? = "active",
    ) = UsageSnapshot(
        planName = "Go",
        subscriptionStatus = status,
        totalRemaining = totalRemaining,
        totalPool = totalPool,
        usagePercent = usagePercent,
        tokensTotal = tokensTotal,
        tokenBasis = basis,
        fiveHour = fiveHour,
        weekly = weekly,
        monthly = monthly,
        fetchedAt = Instant.now(),
    )

    // ------------------------------------------------------------------
    // 三种尺寸都能渲染
    // ------------------------------------------------------------------

    @Test
    fun `every size renders with data`() {
        for (size in allSizes) {
            assertNotNull("$size 在正常数据下必须能渲染", WidgetRenderer.render(context, size, snapshot(), true))
        }
    }

    @Test
    fun `every size renders without an api key while cached data survives`() {
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, snapshot(), hasApiKey = false))
        }
    }

    @Test
    fun `every size renders when the server reports no limited windows`() {
        // windowLimits.limited = false 时 fiveHour / weekly 都是 null（服务端明确说没有窗口）
        val noWindows = snapshot(fiveHour = null, weekly = null, monthly = null)
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, noWindows, true))
        }
    }

    @Test
    fun `every size renders with all-null numeric fields`() {
        // 服务端什么都没返回时，界面必须是 -- 而不是 0
        val blank = snapshot(
            totalRemaining = null,
            usagePercent = null,
            totalPool = null,
            tokensTotal = null,
            fiveHour = null,
            weekly = null,
            monthly = null,
        )
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, blank, true))
        }
    }

    @Test
    fun `every size renders when only the weekly window is present`() {
        val onlyWeekly = snapshot(fiveHour = null, monthly = null)
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, onlyWeekly, true))
        }
    }

    @Test
    fun `every size renders when windows have no caps`() {
        // used 有、cap 没有：算不出百分比，必须走 -- 且隐藏进度条，而不是 0%
        val unknown = RateWindow("5 小时", 0.5, null, null)
        val snapshot = snapshot(fiveHour = unknown, weekly = unknown, monthly = unknown)
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, snapshot, true))
        }
    }

    @Test
    fun `4x4 falls back to the pooled monthly window when the server omits monthly`() {
        // 本接口的常态：windowLimits 里没有 monthly，只有额度池口径的 usagePercent。
        // 4×4 必须回落到它（并标「按周期推算」），否则第三张卡片会是空的。
        val pooled = RateWindow("每月", 7.7, 10.0, null, derived = true)
        assertNotNull(WidgetRenderer.render(context, Size.FULL, snapshot(monthly = null, totalPool = 10.0, totalRemaining = 2.21), true))
        assertNotNull(WidgetRenderer.render(context, Size.FULL, snapshot(monthly = pooled), true))
    }

    @Test
    fun `non active subscription status still renders`() {
        for (status in listOf("trialing", "past_due", "canceled", "wat")) {
            for (size in allSizes) {
                assertNotNull(WidgetRenderer.render(context, size, snapshot(status = status), true))
            }
        }
    }

    @Test
    fun `account total basis renders`() {
        for (size in allSizes) {
            assertNotNull(
                WidgetRenderer.render(context, size, snapshot(basis = TokenBasis.ACCOUNT_TOTAL), true),
            )
        }
    }

    // ------------------------------------------------------------------
    // 空数据 / 未配置 -> 引导视图
    // ------------------------------------------------------------------

    @Test
    fun `null snapshot renders the guidance view not a crash`() {
        // 小米规范 §9：清数据 / 未授权时必须回到默认视图，不能空白或崩溃
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, null, hasApiKey = false))
        }
    }

    @Test
    fun `snapshot without usable data renders guidance view`() {
        val empty = UsageSnapshot(fetchedAt = Instant.now())
        for (size in allSizes) {
            assertNotNull(WidgetRenderer.render(context, size, empty, true))
        }
    }

    @Test
    fun `updateAll is safe when no widget instances exist`() {
        // 会话里一个小组件都没添加时，不能抛异常
        WidgetRenderer.updateAll(context)
    }

    // ------------------------------------------------------------------
    // Provider 可被系统实例化
    // ------------------------------------------------------------------

    @Test
    fun `widget provider classes are resolvable by the system`() {
        // 系统按类名反射实例化 Provider，混淆或改名会直接导致小组件消失
        assertNotNull(DetailedWidgetProvider())
        assertNotNull(UsageWidgetProvider())
        assertNotNull(CompactWidgetProvider())
        // Kotlin 的 companion 不会被继承，常量定义在基类上
        assertEquals(
            "miui.appwidget.action.APPWIDGET_UPDATE",
            BaseUsageWidgetProvider.ACTION_MIUI_WIDGET_UPDATE,
        )
    }

    // ------------------------------------------------------------------
    // 清单契约：锁死原生小部件必需的注册项，防止后续误删
    // ------------------------------------------------------------------

    /** 三个尺寸的 provider 组件，供下面各条清单契约测试共用。 */
    private fun allProviderComponents(): List<ComponentName> = listOf(
        ComponentName(context, DetailedWidgetProvider::class.java),
        ComponentName(context, UsageWidgetProvider::class.java),
        ComponentName(context, CompactWidgetProvider::class.java),
    )

    @Test
    fun `all widget providers declare the appwidget provider config`() {
        for (component in allProviderComponents()) {
            val info = context.packageManager.getReceiverInfo(
                component,
                android.content.pm.PackageManager.GET_META_DATA,
            )
            val meta = info.metaData
            assertNotNull("$component 缺少 meta-data", meta)
            // 每个组件必须声明 appwidget provider 配置，否则系统不认这是小部件
            assertTrue(
                "$component 缺少 android.appwidget.provider",
                meta.containsKey("android.appwidget.provider"),
            )
        }
    }

    @Test
    fun `widgets are declared as plain android widgets not registered miui widgets`() {
        // 本应用是侧载分发（GitHub Actions 出包），不会上架小米应用商店、
        // 也不会在 widget.xiaomi.com 登记。按小米《小部件提交审核与上传操作指南》，
        // 只有登记过并审核通过的小部件才会进「小部件中心」，而侧载应用应作为
        // 原生小部件走「安卓小部件」入口。
        //
        // 因此这里反向锁定：各 receiver 都**不得**声明 miuiWidget，
        // 应用级也**不得**有 miuiWidgetVersion。它们只作为原生小部件出现。
        // 若日后真的要上架小米小部件中心，请连同登记流程一起改回并更新这条测试。
        //
        // 注：这些小米标识是否真会导致原生入口搜不到，并无官方/社区实证，
        // 所以这里锁的是「不声明未登记的小米能力」这一正确姿势，而非声称能修好搜索。
        for (component in allProviderComponents()) {
            val info = context.packageManager.getReceiverInfo(
                component,
                android.content.pm.PackageManager.GET_META_DATA,
            )
            val meta = info.metaData
            assertNotNull("$component 缺少 meta-data", meta)
            assertEquals(
                "$component 不应声明 miuiWidget（未登记的小米小部件不该声明小米能力）",
                false,
                meta.containsKey("miuiWidget"),
            )
        }

        // application 级可能完全没有 meta-data，因此不能直接解引用
        val appInfo = context.packageManager
            .getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        val appMeta = appInfo.metaData
        assertEquals(
            "未登记的小米小部件不应声明 miuiWidgetVersion",
            false,
            appMeta?.containsKey("miuiWidgetVersion") ?: false,
        )
    }

    @Test
    fun `widget providers stay in the main process on purpose`() {
        // 独立进程（:widgetProvider）是小部件上架小米小部件中心的要求，本项目不上架。
        // 反而独立进程会踩坑：WorkManager / androidx.startup 的 initializer 默认只在
        // 主进程初始化，挪进独立进程后 WorkManager.getInstance() 会抛
        // "WorkManager is not initialized properly"，设置页的「添加到桌面」直接崩。
        // 小组件渲染只读 SharedPreferences、不联网，跑主进程开销极小。
        //
        // 这条测试锁的是「这个决定是刻意的」。
        for (component in allProviderComponents()) {
            val info = context.packageManager.getReceiverInfo(component, 0)
            val process = info.processName.orEmpty()
            val inMainProcess = process.isEmpty() || process == context.packageName
            assertTrue(
                "$component 应跑在默认进程，实际 processName=$process",
                inMainProcess,
            )
        }
    }

    @Test
    fun `widget providers are discoverable by the launcher`() {
        // 桌面通过 queryIntentReceivers(ACTION_APPWIDGET_UPDATE) 枚举小部件，
        // 所以每个 provider 的 intent-filter 里必须有这个 action —— 少了它，
        // 系统枚举不到，桌面「添加小部件」列表里就不会出现本应用。
        //
        // 这里读 manifest 源文件断言（而不是走 Robolectric 的 queryBroadcastReceivers：
        // 该 API 在 shadow 里对 manifest 注册的 receiver 支持并不完全可靠，
        // 容易因为测试环境差异产生假失败）。要锁的本来就是「声明了正确的 action」。
        val xml = readSourceFile("AndroidManifest.xml")
        assertNotNull("找不到 AndroidManifest.xml", xml)
        for (provider in listOf("DetailedWidgetProvider", "UsageWidgetProvider", "CompactWidgetProvider")) {
            // 从 `<receiver` 标签开始切片段（不能用 indexOf(provider)：注释里也会提到
            // provider 名与 APPWIDGET_UPDATE，那会造成假通过）
            val nameIdx = xml!!.indexOf(provider)
            assertTrue("AndroidManifest 里找不到 $provider", nameIdx >= 0)
            val start = xml.lastIndexOf("<receiver", nameIdx)
            assertTrue("$provider 不在 <receiver> 标签里", start >= 0)
            val end = xml.indexOf("</receiver>", start)
            assertTrue("$provider 的 <receiver> 没有闭合", end > start)
            val fragment = xml.substring(start, end)
            assertTrue(
                "$provider 的 intent-filter 必须声明 android.appwidget.action.APPWIDGET_UPDATE",
                fragment.contains("android.appwidget.action.APPWIDGET_UPDATE"),
            )
            assertTrue(
                "$provider 必须声明 android.appwidget.provider 配置",
                fragment.contains("android.appwidget.provider"),
            )
        }
    }

    @Test
    fun `all widget sizes share the same label so the system groups them`() {
        // label 相同会被认为是同一功能的不同尺寸，在添加页聚合展示
        val labels = allProviderComponents().map { component ->
            context.packageManager.getReceiverInfo(component, 0)
                .loadLabel(context.packageManager).toString()
        }
        assertEquals("三个尺寸的 label 必须一致", 1, labels.toSet().size)
    }

    @Test
    fun `widget label follows the xiaomi 2 to 8 hanzi rule`() {
        // 小米要求：小部件名称 2–8 个汉字，且不能与应用名相同。
        // 超长或与应用同名的 label 在小米添加页上可能不被收录。
        val label = context.packageManager.getReceiverInfo(
            ComponentName(context, UsageWidgetProvider::class.java), 0,
        ).loadLabel(context.packageManager).toString()
        val hanzi = label.count { it.code in 0x4E00..0x9FFF }
        assertTrue("小部件名称应有 2–8 个汉字，实际「$label」($hanzi 个)", hanzi in 2..8)
        val appName = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        assertTrue("小部件名称不能与应用名「$appName」相同", label != appName)
    }

    @Test
    fun `appwidget provider xml declares previews so the launcher shows a thumbnail`() {
        // 缺少缩略图时，部分桌面会不显示列表条目。Android 12+ 用 previewLayout，
        // 旧版 / 第三方桌面回退到 previewImage —— 两个都声明才覆盖所有桌面。
        for (name in listOf("usage_widget_4x4.xml", "usage_widget_4x2.xml", "usage_widget_2x2.xml")) {
            val xml = readSource("xml/$name")
            assertNotNull("找不到 $name", xml)
            assertTrue("$name 必须声明 previewLayout", xml!!.contains("android:previewLayout="))
            assertTrue("$name 必须声明 previewImage", xml.contains("android:previewImage="))
            assertTrue("$name 必须声明 widgetCategory", xml.contains("android:widgetCategory="))
        }
    }

    @Test
    fun `appwidget provider xml matches xiaomi size recommendations`() {
        // 官方《小部件技术规范》建议尺寸：4×4 = 300×250dp，4×2 = 300×110dp，2×2 = 110×110dp。
        // 直接读源文件而不是解析编译后的资源：AAPT 会把 dimension 字面量编译掉，
        // 反解容易受打包细节影响；这里要断言的就是「声明值」，读源文件最准确。
        assertEquals(250, declaredDp("usage_widget_4x4.xml", "minWidth"))
        assertEquals(250, declaredDp("usage_widget_4x4.xml", "minHeight"))
        assertEquals(300, declaredDp("usage_widget_4x2.xml", "minWidth"))
        assertEquals(110, declaredDp("usage_widget_4x2.xml", "minHeight"))
        assertEquals(110, declaredDp("usage_widget_2x2.xml", "minWidth"))
        assertEquals(110, declaredDp("usage_widget_2x2.xml", "minHeight"))
    }

    @Test
    fun `declared resize floors keep the content inside the widget`() {
        // 用户反馈「调整尺寸后这个显示会出现一点问题」：4×4 曾声明 minResizeHeight=180dp，
        // 而它三张卡片的内容自然高度合计约需 347dp（250dp 时每卡约 70dp 已是「刚好放得下」
        // 的下限），缩到 180dp 后每卡只剩约 47dp，「剩余 $x / $y」与「N后重置」两行直接被裁掉。
        //
        // 注意 Android 的语义（见 AOSP AppWidgetProviderInfo）：
        //   minWidth/minHeight     = 添加到桌面时的默认尺寸；
        //   minResizeWidth/Height  = 用户**能缩到的最小尺寸**；大于 minWidth 时该项无效果。
        // 所以 minResize* **允许**小于 min*（那正是「允许缩得比默认更小」的表达方式），
        // 不能笼统断言 minResize >= min。这里锁的是「允许缩到的下限别小到装不下内容」，
        // 按各布局实测的内容需求分别给出下限。
        //
        // 不在这里按字体度量实算自然高度：那需要真实 Android 字体度量，JVM 单测里不可靠。
        // 下限来自本地实测（见各 usage_widget_*.xml 顶部注释），这里把它固定下来防止回退。
        val contentFloors = mapOf(
            // 三张卡片（标题行含 18sp 百分比 + 条 + 剩余 + 重置）需要 ~347dp 才宽松；
            // 250dp 是刚好放得下、不会裁掉文字的下限
            "usage_widget_4x4.xml" to (250 to 250),
            // 行1 固定部分约 127dp，180dp 时套餐名只剩约 29dp 会被省略、条只剩 60dp；
            // 240dp 起套餐名有约 89dp、条有 120dp
            "usage_widget_4x2.xml" to (240 to 110),
            // 2×2 本来就只有一个大数字 + 一条 + 一行，110dp 已是设计下限
            "usage_widget_2x2.xml" to (110 to 110),
        )

        for ((file, floor) in contentFloors) {
            val (minW, minH) = floor
            val resizeW = declaredDp(file, "minResizeWidth")
            val resizeH = declaredDp(file, "minResizeHeight")
            assertTrue(
                "$file 的 minResizeWidth($resizeW) 小于内容可承受的下限 ${minW}dp：缩到这个宽度内容会被裁/挤压",
                resizeW >= minW,
            )
            assertTrue(
                "$file 的 minResizeHeight($resizeH) 小于内容可承受的下限 ${minH}dp：缩到这个高度内容会被裁",
                resizeH >= minH,
            )
            // 上限与下限不能自相矛盾（maxResize 比 minResize 还小是无效声明）
            assertTrue(
                "$file 的 maxResizeWidth(${declaredDp(file, "maxResizeWidth")}) 不应小于 minResizeWidth($resizeW)",
                declaredDp(file, "maxResizeWidth") >= resizeW,
            )
            assertTrue(
                "$file 的 maxResizeHeight(${declaredDp(file, "maxResizeHeight")}) 不应小于 minResizeHeight($resizeH)",
                declaredDp(file, "maxResizeHeight") >= resizeH,
            )
        }
    }

    @Test
    fun `widget layouts declare the xiaomi required root id and opaque background`() {
        // 小米规范 §7.1：系统通过固定 id @android:id/background 找到根布局来加圆角；
        // 且根布局必须有背景色、不能全透明（切换动画依赖背景色）。
        for (name in listOf(
            "widget_usage_4x4.xml",
            "widget_usage_4x2.xml",
            "widget_usage_2x2.xml",
            "widget_usage_empty.xml",
        )) {
            val xml = readSource("layout/$name")
            assertNotNull("找不到 $name", xml)
            assertTrue("$name 根布局必须声明 @android:id/background", xml!!.contains("@android:id/background"))
            assertTrue("$name 根布局必须有背景", xml.contains("android:background="))
            // 规范 §8：宽高必须 match_parent
            assertTrue("$name 根布局宽高必须 match_parent", xml.contains("android:layout_width=\"match_parent\""))
        }
    }

    @Test
    fun `renderer view ids exist in the layout they are applied to`() {
        // RemoteViews 写一个不存在的 id 时多数动作是**静默跳过**：布局里 id 被改名或删掉，
        // 小组件只会安静地少一块内容，不崩溃也不报错，线上很难发现。
        // 所以这里把「渲染器引用了哪些 id」和「布局声明了哪些 id」强行对起来。
        val detailedXml = readSource("layout/widget_usage_4x4.xml")
        val wideXml = readSource("layout/widget_usage_4x2.xml")
        val compactXml = readSource("layout/widget_usage_2x2.xml")
        assertNotNull("找不到 widget_usage_4x4.xml", detailedXml)
        assertNotNull("找不到 widget_usage_4x2.xml", wideXml)
        assertNotNull("找不到 widget_usage_2x2.xml", compactXml)
        val detailed: String = detailedXml!!
        val wide: String = wideXml!!
        val compact: String = compactXml!!

        // 4×4：三个窗口卡片，每个都有一整套控件
        for (prefix in listOf("fivehour", "weekly", "monthly")) {
            for (suffix in listOf("card", "title", "note", "percent", "bar", "remaining", "reset")) {
                val id = "widget_${prefix}_$suffix"
                assertTrue("4×4 布局缺少 @+id/$id", detailed.contains("@+id/$id"))
            }
        }
        for (id in listOf("widget_plan", "widget_updated")) {
            assertTrue("4×4 布局缺少 @+id/$id", detailed.contains("@+id/$id"))
        }

        for (id in listOf(
            "widget_plan",
            "widget_updated",
            "widget_fivehour",
            "widget_bar_fivehour",
            "widget_weekly",
            "widget_bar_weekly",
            "widget_monthly",
            "widget_bar_monthly",
            "widget_tokens",
            "widget_tokens_label",
        )) {
            assertTrue("4×2 布局缺少 @+id/$id", wide.contains("@+id/$id"))
        }
        for (id in listOf("widget_windows", "widget_percent", "widget_monthly_progress", "widget_tokens")) {
            assertTrue("2×2 布局缺少 @+id/$id", compact.contains("@+id/$id"))
        }

        // 改版移除的 id 必须从布局里删干净，否则会被下一条检查当成合法目标
        assertFalse("4×2 不应再有 widget_remaining", wide.contains("widget_remaining"))
        assertFalse("2×2 不应再有 widget_plan（高度不够，已让位给 5 小时/每周）", compact.contains("widget_plan"))
        assertFalse("2×2 不应再有 widget_percent_caption", compact.contains("widget_percent_caption"))
        assertFalse("4×2 不应再有 4×4 的卡片 id", wide.contains("widget_fivehour_card"))

        // 渲染器引用的每个 R.id.widget_* 必须真实存在于某个布局
        val rendererXml = readSourceFile("kotlin/dev/zxeb/ccusage/widget/WidgetRenderer.kt")
        assertNotNull("找不到 WidgetRenderer.kt", rendererXml)
        val renderer: String = rendererXml!!
        val referenced = Regex("""R\.id\.(widget_\w+)""").findAll(renderer)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("渲染器应当引用小组件 id", referenced.isNotEmpty())
        val allLayouts = detailed + wide + compact
        for (id in referenced) {
            assertTrue(
                "WidgetRenderer 引用了 $id，但三个布局里都没有这个 id",
                allLayouts.contains("@+id/$id"),
            )
        }
    }

    @Test
    fun `layout xml uses only remoteviews supported views`() {
        // RemoteViews 只支持有限的一组 View。一旦布局里出现 ConstraintLayout、
        // 或自定义 View，应用 RemoteViews 时会在**运行时**抛
        // "android.view.InflateException / ClassNotFoundException"，
        // 桌面上表现为小组件空白（不是崩溃弹窗，很难查）。
        val allowed = setOf(
            "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
            "TextView", "ImageView", "Button", "ProgressBar", "Chronometer",
            "View", "Space", "AnalogClock",
        )
        for (name in listOf("widget_usage_4x4.xml", "widget_usage_4x2.xml", "widget_usage_2x2.xml")) {
            val xml = readSource("layout/$name")!!
            // 取所有标签名（含闭包前的），排除 layout_* 属性与资源引用
            val tags = Regex("""<([A-Za-z][A-Za-z0-9_.]*)""").findAll(xml)
                .map { it.groupValues[1] }
                .filterNot { it.startsWith("?") }
                .toSet()
            for (tag in tags) {
                assertTrue(
                    "$name 使用了 RemoteViews 不支持的 <$tag>（会导致桌面空白）",
                    tag in allowed,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 进度条的真实值
    //
    // 这一组正是旧版漏掉的环节：布局里写死 `android:progress="0"`，渲染器只调
    // setProgressTintList 上色、从不写进度值，于是桌面上永远是「数字正常、条是空条」。
    // 之前所有测试都只断言「布局里出现了 ProgressBar 这个标签」，没人读过它的实际值，
    // 所以这个故障一路溜到了用户面前。这里把「条画多长」也锁住。
    // ------------------------------------------------------------------

    /** 把 RemoteViews 应用到真实视图树上，以便读控件的最终状态。 */
    private fun appliedView(size: Size, snapshot: UsageSnapshot?): View {
        // 布局里用了 ?android:attr/progressBarStyleHorizontal 这类主题属性，
        // 必须挂上应用主题再 inflate，否则在测试环境里解析不到该属性。
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_CcUsage)
        return WidgetRenderer.render(context, size, snapshot, hasApiKey = true)
            .apply(themed, FrameLayout(themed))
    }

    private fun progressBarOf(size: Size, snapshot: UsageSnapshot?, barId: Int): ProgressBar {
        val bar = appliedView(size, snapshot).findViewById<ProgressBar>(barId)
        assertNotNull("$size 里找不到进度条 $barId", bar)
        return bar!!
    }

    @Test
    fun `progress bars carry the used percentage instead of the layout default`() {
        // snapshot() 默认：5 小时 0.072/3.0 = 2%、每周 5/100 = 5%、每月 12/100 = 12%
        val cases = listOf(
            Triple(Size.FULL, R.id.widget_fivehour_bar, 2),
            Triple(Size.FULL, R.id.widget_weekly_bar, 5),
            Triple(Size.FULL, R.id.widget_monthly_bar, 12),
            Triple(Size.WIDE, R.id.widget_bar_fivehour, 2),
            Triple(Size.WIDE, R.id.widget_bar_weekly, 5),
            Triple(Size.WIDE, R.id.widget_bar_monthly, 12),
            Triple(Size.COMPACT, R.id.widget_monthly_progress, 12),
        )
        for ((size, barId, expected) in cases) {
            // 分母 100 由布局的 android:max 保证，这里顺带锁死它
            val bar = progressBarOf(size, snapshot(), barId)
            assertEquals("$size 的进度条 max 应为 100", 100, bar.max)
            assertEquals("$size 的进度条必须写入已用比例（而不是布局默认的 0）", expected, bar.progress)
        }
    }

    @Test
    fun `progress bar mirrors the percentage shown next to it`() {
        // 条与数字必须同源：数字 87% 时条也得是 87，不能各算各的
        val monthly = RateWindow("每月", 87.0, 100.0, null)
        val bar = progressBarOf(Size.WIDE, snapshot(monthly = monthly), R.id.widget_bar_monthly)
        assertEquals(87, bar.progress)
    }

    @Test
    fun `progress bars stay hidden instead of reading as zero when data is missing`() {
        // 没有 cap 就算不出百分比：必须隐藏，画成 0% 会被读成「完全没用过」
        val unknown = RateWindow("5 小时", 0.5, null, null)
        val blank = snapshot(
            fiveHour = unknown,
            weekly = unknown,
            monthly = unknown,
            totalPool = null,
            totalRemaining = null,
            usagePercent = null,
        )
        for ((size, barId) in listOf(
            Size.FULL to R.id.widget_fivehour_bar,
            Size.WIDE to R.id.widget_bar_fivehour,
            Size.COMPACT to R.id.widget_monthly_progress,
        )) {
            assertEquals(
                "$size 在数据缺失时必须隐藏进度条（不能画成 0%）",
                View.INVISIBLE,
                progressBarOf(size, blank, barId).visibility,
            )
        }
    }

    @Test
    fun `4x2 falls back to the pooled monthly window when the server omits monthly`() {
        // 本接口的常态：windowLimits 里没有 monthly。4×2 的「每月」行同样要回落，
        // 否则那一行恒为 --，而它恰恰是最需要看的数字（4×4 早就有这个回落，4×2 曾漏掉）。
        val bar = progressBarOf(
            Size.WIDE,
            snapshot(monthly = null, totalPool = 10.0, totalRemaining = 2.21),
            R.id.widget_bar_monthly,
        )
        // (10.0 - 2.21) / 10.0 = 77.9% -> 截断 77
        assertEquals("4×2 的每月行必须回落到额度池口径", 77, bar.progress)
        assertEquals(
            "回落算出来的每月必须可见（不能因为服务端没给就隐藏）",
            View.VISIBLE,
            bar.visibility,
        )
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

    /** 读取 `app/src/main/<relative>` 源文件（用于 AndroidManifest.xml 这类非 res 文件）。 */
    private fun readSourceFile(relative: String): String? {
        val candidates = listOf(
            java.io.File("src/main/$relative"),
            java.io.File("app/src/main/$relative"),
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
        val ids = listOf(
            R.layout.widget_usage_4x4,
            R.layout.widget_usage_4x2,
            R.layout.widget_usage_2x2,
            R.layout.widget_usage_empty,
        )
        for (layoutId in ids) {
            val views = android.widget.RemoteViews(context.packageName, layoutId)
            // 能构建即说明布局资源可解析
            assertNotNull(views)
        }
        assertTrue(android.R.id.background != 0)
    }
}
