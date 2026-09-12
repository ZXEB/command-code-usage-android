package dev.zxeb.ccusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import dev.zxeb.ccusage.MainActivity
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.data.SnapshotStore
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.Format
import dev.zxeb.ccusage.model.TokenBasis
import dev.zxeb.ccusage.model.UsageSnapshot
import java.time.Instant

/**
 * 小组件渲染器。
 *
 * **只读 `SharedPreferences`，不发网络请求、不碰 Compose** —— 因为小组件跑在
 * `:widgetProvider` 独立进程里，小米规范要求该进程内存 ≤35M、只做「内容准备和刷新」。
 * 网络刷新交给 [WidgetRefreshWorker]，它同样声明在 `:widgetProvider` 进程。
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
            renderCompact(views, snapshot)
        } else {
            renderFull(views, snapshot, hasApiKey)
        }

        views.setOnClickPendingIntent(ROOT_ID, openAppIntent(context))
        return views
    }

    // ------------------------------------------------------------------
    // 4×2：套餐 · 月度剩余 · 进度 · 本期 token · 5小时窗口 · 更新时间
    // ------------------------------------------------------------------

    private fun renderFull(views: RemoteViews, snapshot: UsageSnapshot, hasApiKey: Boolean) {
        views.setTextViewText(R.id.widget_plan, planLabel(snapshot))

        val percent = snapshot.usagePercent
        views.setTextViewText(
            R.id.widget_remaining,
            when {
                snapshot.totalRemaining != null -> Format.usd(snapshot.totalRemaining)
                percent != null -> "${(100.0 - percent).toInt()}%"
                else -> Format.UNKNOWN
            },
        )

        // 进度条 = 已用比例；数据缺失时走不确定态，而不是画成 0%
        views.setProgressBar(
            R.id.widget_monthly_progress,
            100,
            percent?.toInt()?.coerceIn(0, 100) ?: 0,
            percent == null,
        )

        // 本计费周期 tokens（官方数据，以 M 显示）
        views.setTextViewText(R.id.widget_tokens, Format.millions(snapshot.tokensTotal))
        views.setTextViewText(
            R.id.widget_tokens_label,
            if (snapshot.tokenBasis == TokenBasis.ACCOUNT_TOTAL) "累计 tokens" else "本期 tokens",
        )

        // 5 小时窗口
        // 注意：RateWindow.percent 是带自定义 getter 的计算属性，无法智能转换，
        // 必须先把值取到局部变量再判空。
        val fiveHourPercent = snapshot.fiveHour?.percent
        views.setTextViewText(
            R.id.widget_fivehour,
            when {
                fiveHourPercent != null -> "5小时 ${fiveHourPercent.toInt()}%"
                !hasApiKey -> "未配置 API Key"
                else -> "5小时 ${Format.UNKNOWN}"
            },
        )

        // 更新时间；数据陈旧时补一个角标
        val stamp = Format.resetAt(snapshot.fetchedAt, Instant.now())
        views.setTextViewText(
            R.id.widget_updated,
            if (snapshot.source == DataSource.STALE) "$stamp ⚠" else stamp,
        )
    }

    // ------------------------------------------------------------------
    // 2×2：套餐 · 剩余百分比（大字）· 进度 · 本期 token
    // ------------------------------------------------------------------

    private fun renderCompact(views: RemoteViews, snapshot: UsageSnapshot) {
        views.setTextViewText(R.id.widget_plan, snapshot.planName ?: "Command Code")

        val percent = snapshot.usagePercent
        views.setTextViewText(
            R.id.widget_percent,
            if (percent != null) "${(100.0 - percent).toInt()}%" else Format.UNKNOWN,
        )

        views.setProgressBar(
            R.id.widget_monthly_progress,
            100,
            percent?.toInt()?.coerceIn(0, 100) ?: 0,
            percent == null,
        )

        views.setTextViewText(R.id.widget_tokens, Format.millions(snapshot.tokensTotal))
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
 * 关键适配：小米小部件**去掉了系统原有的定时刷新**，改为「曝光刷新」——
 * 用户滑到有 Widget 的桌面页时，系统发 `miui.appwidget.action.APPWIDGET_UPDATE`。
 * 因此 [onReceive] 必须显式处理这个 action（小米规范 §2.2）。
 *
 * **渲染与联网严格分离**：每次回调第一件事都是「用缓存立刻重绘」，保证任何情况下桌面
 * 都不会空白；联网刷新另有 [WidgetRefreshWorker] 负责。这样即使网络很慢或超时，
 * 小组件也始终有话可显示。
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
