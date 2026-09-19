package dev.zxeb.ccusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import dev.zxeb.ccusage.MainActivity
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.data.SnapshotStore
import dev.zxeb.ccusage.model.Format
import dev.zxeb.ccusage.model.RateWindow
import dev.zxeb.ccusage.model.UsageSnapshot

/**
 * 小组件渲染器。
 *
 * **只读 `SharedPreferences`，不发网络请求、不碰 Compose** —— 渲染必须足够轻。
 * 网络刷新交给 [WidgetRefreshWorker]。小组件与本应用同属主进程
 * （不设 `:widgetProvider`，原因见 AndroidManifest），这里更要避免做重活。
 *
 * 三种尺寸共用同一套「窗口」绑定逻辑（[bindWindow] + [WindowViewIds]），
 * 口径由 [WidgetText] 统一决定，与应用内的 `WindowCard` 保持一致。
 */
object WidgetRenderer {

    private const val TAG = "CcWidget"

    /**
     * 小米规范 §7.1 要求的内容区 id：系统据此统一加圆角。
     * 各布局的根节点都声明了 `android:id="@android:id/background"`。
     */
    private const val ROOT_ID = android.R.id.background

    /** 进度条分母。进度值本身就是 0..100 的百分比，所以最大刻度固定 100。 */
    private const val BAR_MAX = 100

    /** 尺寸档位。4×4 是详细版，4×2 / 2×2 是紧凑版；三者都是竖排窗口行。 */
    enum class Size {
        /** 4×4（250×250dp）：三个窗口各一张卡片，带剩余与重置明细。 */
        FULL,

        /** 4×2（300×110dp）：三个窗口竖排一行一个，通栏进度条。 */
        WIDE,

        /** 2×2（110×110dp）：月度大字 + 5 小时/每周摘要。 */
        COMPACT,
    }

    /**
     * 一个窗口在某个布局里用到的那组控件 id。
     *
     * 三种布局的窗口结构相同、只是 id 前缀不同，用这个把「哪些 id 属于哪个窗口」
     * 集中声明，避免在绑定逻辑里写三遍几乎相同的代码。
     */
    private data class WindowViewIds(
        val cardId: Int,
        val titleId: Int,
        val noteId: Int,
        val percentId: Int,
        val barId: Int,
        val remainingId: Int,
        val resetId: Int,
    )

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 渲染指定尺寸。
     *
     * 旧的二参签名已改为 [Size]：新增 4×4 后「compact: Boolean」表达不下三种档位，
     * 用枚举比再叠一个 boolean 清楚。
     */
    fun render(
        context: Context,
        size: Size,
        snapshot: UsageSnapshot?,
        hasApiKey: Boolean,
    ): RemoteViews {
        // 未配置 / 无数据 -> 引导视图（小米规范 §9：必须不空白、不崩溃）
        if (snapshot == null || !snapshot.hasData) {
            val empty = RemoteViews(context.packageName, R.layout.widget_usage_empty)
            empty.setOnClickPendingIntent(ROOT_ID, openAppIntent(context))
            return empty
        }

        val layout = when (size) {
            Size.FULL -> R.layout.widget_usage_4x4
            Size.WIDE -> R.layout.widget_usage_4x2
            Size.COMPACT -> R.layout.widget_usage_2x2
        }
        val views = RemoteViews(context.packageName, layout)

        when (size) {
            Size.FULL -> renderDetailed(context, views, snapshot, hasApiKey)
            Size.WIDE -> renderWide(context, views, snapshot, hasApiKey)
            Size.COMPACT -> renderCompact(context, views, snapshot)
        }

        views.setOnClickPendingIntent(ROOT_ID, openAppIntent(context))
        return views
    }

    // ------------------------------------------------------------------
    // 4×4：三个窗口卡片（标题 + 已用% + 进度条 + 剩余 + 重置倒计时）
    // ------------------------------------------------------------------

    private fun renderDetailed(
        context: Context,
        views: RemoteViews,
        snapshot: UsageSnapshot,
        hasApiKey: Boolean,
    ) {
        views.setTextViewText(R.id.widget_plan, planLabel(snapshot))
        views.setTextViewText(R.id.widget_updated, WidgetText.updateStamp(snapshot, hasApiKey))

        bindWindow(context, views, snapshot.fiveHour, FIVE_HOUR_4X4)
        bindWindow(context, views, snapshot.weekly, WEEKLY_4X4)
        // 服务端不给月度窗口时（本接口的常态），回落到额度池口径，
        // 并标成「按周期推算」——与应用内一致，不能默认成服务端权威值。
        bindWindow(context, views, snapshot.monthly ?: pooledMonthlyWindow(snapshot), MONTHLY_4X4, forcedDerived = snapshot.monthly == null)
    }

    /** 4×4 里三个窗口各自的控件 id。 */
    private val FIVE_HOUR_4X4 = WindowViewIds(
        cardId = R.id.widget_fivehour_card,
        titleId = R.id.widget_fivehour_title,
        noteId = R.id.widget_fivehour_note,
        percentId = R.id.widget_fivehour_percent,
        barId = R.id.widget_fivehour_bar,
        remainingId = R.id.widget_fivehour_remaining,
        resetId = R.id.widget_fivehour_reset,
    )

    private val WEEKLY_4X4 = WindowViewIds(
        cardId = R.id.widget_weekly_card,
        titleId = R.id.widget_weekly_title,
        noteId = R.id.widget_weekly_note,
        percentId = R.id.widget_weekly_percent,
        barId = R.id.widget_weekly_bar,
        remainingId = R.id.widget_weekly_remaining,
        resetId = R.id.widget_weekly_reset,
    )

    private val MONTHLY_4X4 = WindowViewIds(
        cardId = R.id.widget_monthly_card,
        titleId = R.id.widget_monthly_title,
        noteId = R.id.widget_monthly_note,
        percentId = R.id.widget_monthly_percent,
        barId = R.id.widget_monthly_bar,
        remainingId = R.id.widget_monthly_remaining,
        resetId = R.id.widget_monthly_reset,
    )

    // ------------------------------------------------------------------
    // 4×2：三个窗口竖排（一行一个：名称 + 已用% + 通栏进度条）
    // ------------------------------------------------------------------

    private fun renderWide(
        context: Context,
        views: RemoteViews,
        snapshot: UsageSnapshot,
        hasApiKey: Boolean,
    ) {
        views.setTextViewText(R.id.widget_plan, planLabel(snapshot))
        views.setTextViewText(R.id.widget_updated, WidgetText.updateStamp(snapshot, hasApiKey))

        bindWideColumn(context, views, snapshot.fiveHour, R.id.widget_fivehour, R.id.widget_bar_fivehour)
        bindWideColumn(context, views, snapshot.weekly, R.id.widget_weekly, R.id.widget_bar_weekly)
        // 与 4×4 同样回落：服务端通常不给 monthly，此时用额度池口径合成，
        // 否则这一行会恒为「--」，而它恰恰是最需要看的那个数字。
        bindWideColumn(
            context,
            views,
            snapshot.monthly ?: pooledMonthlyWindow(snapshot),
            R.id.widget_monthly,
            R.id.widget_bar_monthly,
        )

        views.setTextViewText(R.id.widget_tokens, Format.millions(snapshot.tokensTotal))
        views.setTextViewText(R.id.widget_tokens_label, WidgetText.tokensCaption(snapshot.tokenBasis))
    }

    /** 4×2 的单行：已用% + 进度条（数据缺失隐藏条，不画 0%）。 */
    private fun bindWideColumn(
        context: Context,
        views: RemoteViews,
        window: RateWindow?,
        valueId: Int,
        barId: Int,
    ) {
        val color = context.getColor(WidgetText.colorRes(WidgetText.utilization(window)))
        views.setTextViewText(valueId, WidgetText.usedPercentText(window))
        setBarProgress(views, barId, WidgetText.barProgress(window))
        tintBar(views, barId, window)
        views.setViewVisibility(barId, if (WidgetText.barVisible(window)) View.VISIBLE else View.INVISIBLE)
        runCatching { views.setTextColor(valueId, color) }
    }

    // ------------------------------------------------------------------
    // 2×2：月度已用%（大字）+ 5 小时/每周摘要 + token
    // ------------------------------------------------------------------

    private fun renderCompact(context: Context, views: RemoteViews, snapshot: UsageSnapshot) {
        views.setTextViewText(
            R.id.widget_windows,
            WidgetText.compactWindowsLine(
                fiveHour = snapshot.fiveHour,
                weekly = snapshot.weekly,
                fiveHourLabel = context.getString(R.string.widget_window_fivehour_short),
                weeklyLabel = context.getString(R.string.widget_window_weekly_short),
            ),
        )

        // 月度优先取服务端窗口；不给时回落到额度池口径（同一个算法的两种来源）。
        val monthlyWindow = snapshot.monthly
        val usedPercent = monthlyWindow?.percent ?: snapshot.usagePercent
        val color = context.getColor(WidgetText.colorRes(WidgetText.utilizationOf(usedPercent)))

        views.setTextViewText(R.id.widget_percent, WidgetText.usedPercentText(usedPercent))
        setBarProgress(views, R.id.widget_monthly_progress, WidgetText.barProgress(usedPercent))
        tintBarValue(views, R.id.widget_monthly_progress, usedPercent)
        views.setViewVisibility(
            R.id.widget_monthly_progress,
            if (usedPercent != null) View.VISIBLE else View.INVISIBLE,
        )
        views.setTextViewText(
            R.id.widget_tokens,
            WidgetText.tokensLine(snapshot.tokenBasis, snapshot.tokensTotal),
        )
        runCatching { views.setTextColor(R.id.widget_percent, color) }
    }

    // ------------------------------------------------------------------
    // 共用：单个窗口卡片的绑定
    // ------------------------------------------------------------------

    /**
     * 把一个窗口绑到一组控件上（4×4 的用法）。
     *
     * @param forcedDerived true 表示这个窗口是本地按额度池推算的，即使 `window.derived`
     *   没置位也要标「按周期推算」（否则用户会以为它是服务端权威口径）。
     */
    private fun bindWindow(
        context: Context,
        views: RemoteViews,
        window: RateWindow?,
        ids: WindowViewIds,
        forcedDerived: Boolean = false,
    ) {
        val color = context.getColor(WidgetText.colorRes(WidgetText.utilization(window)))

        // 标题固定用窗口名（服务端的 label 可能带别的措辞，位置固定更利于扫读）
        views.setTextViewText(ids.titleId, window?.label?.takeIf { it.isNotBlank() } ?: defaultTitle(ids))
        // 标记文案统一走 WidgetText（有单测），不再另建一份字符串资源，避免两处漂移
        views.setTextViewText(ids.noteId, WidgetText.derivedNote(window, forcedDerived).orEmpty())
        views.setTextViewText(ids.percentId, WidgetText.usedPercentText(window))
        runCatching { views.setTextColor(ids.percentId, color) }

        tintBar(views, ids.barId, window)
        setBarProgress(views, ids.barId, WidgetText.barProgress(window))
        views.setViewVisibility(ids.barId, if (WidgetText.barVisible(window)) View.VISIBLE else View.INVISIBLE)

        views.setTextViewText(ids.remainingId, WidgetText.remainingLine(window))
        views.setTextViewText(ids.resetId, WidgetText.resetLine(window))
    }

    /**
     * 进度条上色。
     *
     * `RemoteViews` 没有公开的 `setProgressTintList`（那个方法在 AOSP 里是 `@hide`，
     * 直接调编译不过）。但 `setColorStateList(viewId, methodName, colorRes)` 是**公开 API**
     * （Android 12 起就有，见 AOSP RemoteViews#setColorStateList），
     * 它通过反射调用目标 View 上的 `@RemotableViewMethod` 方法 ——
     * `ProgressBar.setProgressTintList` 带这个标注，所以能用。
     * AndroidX Glance 给小组件进度条上色走的正是这条路径。
     *
     * 用**颜色资源 id** 重载而不是 ColorStateList 实例：它在 RemoteViews 应用到界面时
     * 才解析，能正确跟随深浅色（`values-night`）切换。整段包 runCatching：
     * 万一某些 ROM 裁剪了这个方法，只是没有着色，不能让整个小组件渲染失败。
     */
    private fun tintBar(views: RemoteViews, barId: Int, window: RateWindow?) {
        tintBarColorRes(views, barId, WidgetText.colorRes(WidgetText.utilization(window)))
    }

    /** 同上，但直接接受已用百分比（2×2 的大字进度条没有 RateWindow）。 */
    private fun tintBarValue(views: RemoteViews, barId: Int, usedPercent: Double?) {
        tintBarColorRes(views, barId, WidgetText.colorRes(WidgetText.utilizationOf(usedPercent)))
    }

    private fun tintBarColorRes(views: RemoteViews, barId: Int, colorRes: Int) {
        runCatching {
            views.setColorStateList(barId, "setProgressTintList", colorRes)
        }.onFailure { Log.w(TAG, "进度条上色失败（不影响渲染）: ${it.message}") }
    }

    /**
     * 下发进度条的实际进度（同时把 max 也写死成 100）。
     *
     * **必须显式写值**：布局里的 `android:progress="0"` 只是初值，`RemoteViews` 在桌面上
     * 只应用「布局自身 + 渲染器下发的动作」。不写这个动作，条就永远是 0%，桌面上只看得见
     * 灰色轨道 —— 这正是旧版「数字正常、进度条是空条」的原因。
     *
     * 用 `RemoteViews.setProgressBar`（**公开 API，API 1 起**，见 AOSP `core/api/current.txt`
     * 中 RemoteViews 的成员表）。它的方法体就是
     * `setIndeterminate` + `setMax` + `setProgress` 三个 `setInt`，
     * 而 `ProgressBar` 上这三个方法都带 `@android.view.RemotableViewMethod`
     * （`RemoteViews` 在 apply 时会**校验**该标注，缺失就抛 `ActionException`），所以完全可用。
     * 它比自己调 `setInt(id, "setProgress", n)` 更完整：顺带把 max 也固定住，
     * 不再依赖各布局是否声明了 `android:max="100"`。
     *
     * 这里**不包 runCatching**（与上面上色不同）：进度条画不出来是必须暴露的故障，
     * 静默降级只会让「空条」这种问题再次潜伏到线上。
     */
    private fun setBarProgress(views: RemoteViews, barId: Int, progress: Int) {
        views.setProgressBar(barId, BAR_MAX, progress.coerceIn(0, BAR_MAX), false)
    }

    private fun defaultTitle(ids: WindowViewIds): String = when (ids) {
        FIVE_HOUR_4X4 -> "5 小时"
        WEEKLY_4X4 -> "每周"
        else -> "每月"
    }

    /**
     * 服务端不给月度窗口时，用额度池口径合成一个「每月」窗口。
     *
     * 应用内 2×2/概览也是这么回落的。`derived` 置 true，界面会标「按周期推算」。
     */
    private fun pooledMonthlyWindow(snapshot: UsageSnapshot): RateWindow? {
        val cap = snapshot.totalPool
        val remaining = snapshot.totalRemaining
        val percent = snapshot.usagePercent
        // 至少要有 cap 才谈得上「额度窗口」（没有 cap 就算不出比例与剩余）
        if (cap == null) return null

        // RateWindow 的 percent/remaining 都由 used 与 cap 推导，所以这里只要求出 used：
        // - 有 cap 与 remaining    -> used = cap - remaining（同时保住剩余那一行）
        // - 只有 cap 与 usagePercent -> used = cap * percent
        // 优先用 remaining 反推：这样「剩余 $x」永远有值（百分比缺失时也不丢信息）。
        val used = when {
            remaining != null -> (cap - remaining).coerceAtLeast(0.0)
            percent != null -> cap * percent / 100.0
            else -> return null
        }

        return RateWindow(
            label = "每月",
            used = used,
            cap = cap,
            resetAt = snapshot.cycleEnd,
            derived = true,
        )
    }

    private fun planLabel(snapshot: UsageSnapshot): String = buildString {
        append(snapshot.planName ?: "Command Code")
        val status = snapshot.subscriptionStatus
        if (!status.isNullOrBlank() && !status.equals("active", ignoreCase = true)) {
            append("（").append(statusLabel(status)).append("）")
        }
    }

    private fun statusLabel(status: String): String = when (status.lowercase()) {
        "trialing" -> "试用中"
        "past_due" -> "逾期"
        "canceled", "cancelled" -> "已取消"
        "incomplete" -> "未完成"
        else -> status
    }

    /** 小米规范 §6：跳转应用页推荐用 PendingIntent.getActivity，不要用广播/服务中转。 */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 用缓存重新渲染所有已添加的实例（4×4 / 4×2 / 2×2 各推各的布局）。
     *
     * 这是小组件唯一的绘制入口 —— 它是**纯本地操作**，不联网，因此可以被曝光刷新、
     * Provider 回调、WorkManager 完成回调任意调用。
     */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val snapshot = SnapshotStore(context).load()
        val hasApiKey = SettingsStore(context).hasApiKey

        updateTarget(context, manager, DetailedWidgetProvider::class.java, Size.FULL, snapshot, hasApiKey)
        updateTarget(context, manager, UsageWidgetProvider::class.java, Size.WIDE, snapshot, hasApiKey)
        updateTarget(context, manager, CompactWidgetProvider::class.java, Size.COMPACT, snapshot, hasApiKey)
    }

    private fun updateTarget(
        context: Context,
        manager: AppWidgetManager,
        providerClass: Class<*>,
        size: Size,
        snapshot: UsageSnapshot?,
        hasApiKey: Boolean,
    ) {
        val component = ComponentName(context, providerClass)
        val ids = runCatching { manager.getAppWidgetIds(component) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return
        val views = render(context, size, snapshot, hasApiKey)
        runCatching { manager.updateAppWidget(component, views) }
            .onFailure { Log.w(TAG, "updateAppWidget 失败: ${it.message}") }
    }
}

/**
 * 小组件通用行为。
 *
 * **渲染与联网严格分离**：每次回调第一件事都是「用缓存立刻重绘」，保证任何情况下桌面
 * 都不会空白；联网刷新另有 [WidgetRefreshWorker] 负责。这样即使网络很慢或超时，
 * 小组件也始终有话可显示。
 *
 * [onReceive] 仍兼容小米的曝光刷新广播：虽然本应用声明的是标准原生小部件，
 * 但澎湃桌面若推送 `miui.appwidget.action.APPWIDGET_UPDATE` 过来，
 * 顺手响应一次没有坏处（多一条刷新路径而已）。
 */
abstract class BaseUsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 1) 本地立刻重绘，保证不空白
        WidgetRenderer.updateAll(context)
        // 2) 再排一个后台任务去拉新数据，成功后 Worker 会再重绘一次
        WidgetRefreshWorker.enqueue(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIUI_WIDGET_UPDATE) {
            // 澎湃曝光刷新：intent 里带被曝光实例的 id 数组（小米规范 §2.2 示例）
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            val manager = AppWidgetManager.getInstance(context)
            if (manager != null) {
                onUpdate(context, manager, ids ?: IntArray(0))
            } else {
                // 拿不到 manager 也要至少重绘缓存
                WidgetRenderer.updateAll(context)
            }
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 尺寸变化后重新渲染：match_parent 布局天然兼容各种网格与有无搜索框的差异
        WidgetRenderer.updateAll(context)
    }

    companion object {
        /** 澎湃曝光刷新 action。 */
        const val ACTION_MIUI_WIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
    }
}

/** 4×4 详细版（三个窗口卡片）。 */
class DetailedWidgetProvider : BaseUsageWidgetProvider()

/** 4×2 宽版（三个窗口并排）。 */
class UsageWidgetProvider : BaseUsageWidgetProvider()

/** 2×2 精简版。三个 Provider 共用 `android:label`，澎湃详情页会聚合展示。 */
class CompactWidgetProvider : BaseUsageWidgetProvider()
