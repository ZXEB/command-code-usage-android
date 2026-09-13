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
 */
object WidgetRenderer {

    private const val TAG = "CcWidget"

    /**
     * 小米规范 §7.1 要求的内容区 id：系统据此统一加圆角。
     * 三个布局的根节点都声明了 `android:id="@android:id/background"`。
     */
    private const val ROOT_ID = android.R.id.background

    /**
     * 渲染指定尺寸。
     *
     * @param compact true = 2×2 精简版；false = 4×2 完整版
     */
    fun render(
        context: Context,
        compact: Boolean,
        snapshot: UsageSnapshot?,
        hasApiKey: Boolean,
    ): RemoteViews {
        // 未配置 / 无数据 -> 引导视图（小米规范 §9：必须不空白、不崩溃）
        if (snapshot == null || !snapshot.hasData) {
            val empty = RemoteViews(context.packageName, R.layout.widget_usage_empty)
            empty.setOnClickPendingIntent(ROOT_ID, openAppIntent(context))
            return empty
        }

        val layout = if (compact) R.layout.widget_usage_2x2 else R.layout.widget_usage_4x2
        val views = RemoteViews(context.packageName, layout)

        if (compact) {
            renderCompact(context, views, snapshot)
        } else {
            renderFull(context, views, snapshot, hasApiKey)
        }

        views.setOnClickPendingIntent(ROOT_ID, openAppIntent(context))
        return views
    }

    // ------------------------------------------------------------------
    // 4×2：套餐 · 5 小时 / 每周 / 每月 剩余% · 各窗进度条 · 本期 token · 更新时间
    // ------------------------------------------------------------------

    private fun renderFull(
        context: Context,
        views: RemoteViews,
        snapshot: UsageSnapshot,
        hasApiKey: Boolean,
    ) {
        views.setTextViewText(R.id.widget_plan, planLabel(snapshot))
        views.setTextViewText(R.id.widget_updated, WidgetText.updateStamp(snapshot, hasApiKey))

        // 三个窗口各占一列：数值 + 细进度条。文案与配色都走 WidgetText，
        // 缺失数据由它统一回落到 `--` + 不确定态进度条。
        bindWindow(context, views, snapshot.fiveHour, R.id.widget_fivehour, R.id.widget_bar_fivehour)
        bindWindow(context, views, snapshot.weekly, R.id.widget_weekly, R.id.widget_bar_weekly)
        bindWindow(context, views, snapshot.monthly, R.id.widget_monthly, R.id.widget_bar_monthly)

        // 本计费周期 tokens（官方数据，以 M 显示）
        views.setTextViewText(R.id.widget_tokens, Format.millions(snapshot.tokensTotal))
        views.setTextViewText(R.id.widget_tokens_label, WidgetText.tokensCaption(snapshot.tokenBasis))
    }

    /** 单个窗口：数值文案 + 按占用率上色 + 进度条（数据缺失时走不确定态）。 */
    private fun bindWindow(
        context: Context,
        views: RemoteViews,
        window: RateWindow?,
        valueId: Int,
        barId: Int,
    ) {
        val color = context.getColor(WidgetText.colorRes(WidgetText.utilization(window)))
        views.setTextViewText(valueId, WidgetText.remainingPercentText(window))
        views.setProgressBar(
            barId,
            100,
            WidgetText.barProgress(window),
            WidgetText.barIsIndeterminate(window),
        )
        // 上色属于锦上添花：个别桌面 / 版本对 RemoteViews 的颜色动作支持不一，
        // 失败时只是少个颜色，绝不能让整次重绘挂掉。
        runCatching { views.setTextColor(valueId, color) }
        runCatching { views.setProgressTintList(barId, ColorStateList.valueOf(color)) }
    }

    // ------------------------------------------------------------------
    // 2×2：5 小时 / 每周 一行 · 月度剩余百分比（大字）· 进度条 · 本期 token
    // ------------------------------------------------------------------

    private fun renderCompact(context: Context, views: RemoteViews, snapshot: UsageSnapshot) {
        // 110dp 高度有限：套餐名与「月度剩余」说明行已从布局里去掉，
        // 换来「5 小时 / 每周」这一行（见 README「小部件显示内容」）。
        // 宽度同样有限（86dp），所以这里用短名（「5时」「周」），
        // 完整名在 9sp 下会被 ellipsize 截成「5 小时 97% · 每周 8…」。
        views.setTextViewText(
            R.id.widget_windows,
            WidgetText.compactWindowsLine(
                fiveHour = snapshot.fiveHour,
                weekly = snapshot.weekly,
                fiveHourLabel = context.getString(R.string.widget_window_fivehour_short),
                weeklyLabel = context.getString(R.string.widget_window_weekly_short),
            ),
        )

        // 月度剩余优先取服务端的 monthly 窗口；服务端不给时（本接口的常态）
        // 回落到额度池口径的 usagePercent —— 两者本来就是同一个算法的两种来源。
        val monthlyWindow = snapshot.monthly
        val usedPercent = monthlyWindow?.percent ?: snapshot.usagePercent
        val color = context.getColor(WidgetText.colorRes(WidgetText.utilizationOf(usedPercent)))

        views.setTextViewText(R.id.widget_percent, WidgetText.remainingPercentText(usedPercent))
        views.setProgressBar(
            R.id.widget_monthly_progress,
            100,
            WidgetText.remainingPercent(usedPercent) ?: 0,
            WidgetText.remainingPercent(usedPercent) == null,
        )
        views.setTextViewText(R.id.widget_tokens, Format.millions(snapshot.tokensTotal))
        runCatching { views.setTextColor(R.id.widget_percent, color) }
        runCatching { views.setProgressTintList(R.id.widget_monthly_progress, ColorStateList.valueOf(color)) }
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
     * 用缓存重新渲染所有已添加的实例（4×2 与 2×2 各推各的布局）。
     *
     * 这是小组件唯一的绘制入口 —— 它是**纯本地操作**，不联网，因此可以被曝光刷新、
     * Provider 回调、WorkManager 完成回调任意调用。
     */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val snapshot = SnapshotStore(context).load()
        val hasApiKey = SettingsStore(context).hasApiKey

        updateTarget(context, manager, UsageWidgetProvider::class.java, compact = false, snapshot, hasApiKey)
        updateTarget(context, manager, CompactWidgetProvider::class.java, compact = true, snapshot, hasApiKey)
    }

    private fun updateTarget(
        context: Context,
        manager: AppWidgetManager,
        providerClass: Class<*>,
        compact: Boolean,
        snapshot: UsageSnapshot?,
        hasApiKey: Boolean,
    ) {
        val component = ComponentName(context, providerClass)
        val ids = runCatching { manager.getAppWidgetIds(component) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return
        val views = render(context, compact, snapshot, hasApiKey)
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
        // 尺寸变化后重新渲染：match_parent 布局天然兼容 4×6 / 5×6 网格、有无搜索框等差异
        WidgetRenderer.updateAll(context)
    }

    companion object {
        /** 澎湃曝光刷新 action。 */
        const val ACTION_MIUI_WIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
    }
}

/** 4×2 小组件（主）。澎湃小部件规范要求的元数据见 AndroidManifest。 */
class UsageWidgetProvider : BaseUsageWidgetProvider()

/** 2×2 精简版。与 [UsageWidgetProvider] 共用 `android:label`，澎湃详情页会聚合展示。 */
class CompactWidgetProvider : BaseUsageWidgetProvider()
