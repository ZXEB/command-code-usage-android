package dev.zxeb.ccusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import dev.zxeb.ccusage.MainActivity
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.data.SnapshotStore
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.UsageSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 小组件渲染器。
 *
 * **只读 `SharedPreferences`，不发网络请求、不碰 Compose** —— 因为小组件跑在
 * `:widgetProvider` 独立进程里，小米规范要求该进程内存 ≤35M、只做「内容准备和刷新」。
 * 网络刷新交给 [WidgetRefreshWorker]，它同样声明在 `:widgetProvider` 进程。
 */
object WidgetRenderer {

    /** 小米规范 §7.2 要求的内容区 id：系统据此画圆角，必须是 @android:id/background。 */
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
            renderCompact(context, views, snapshot, hasApiKey)
        } else {
            renderFull(context, views, snapshot, hasApiKey)
        }

        views.setOnClickPendingIntent(ROOT_ID, openAppIntent(context))
        return views
    }

    // ------------------------------------------------------------------
    // 4×2：套餐 · 月度剩余 · 进度 · 本期 token · 5小时窗口 · 更新时间
    // ------------------------------------------------------------------

    private fun renderFull(
        context: Context,
        views: RemoteViews,
        snapshot: UsageSnapshot,
        hasApiKey: Boolean,
    ) {
        val planLabel = buildString {
            append(snapshot.planName ?: "Command Code")
            snapshot.subscriptionStatus?.let { status ->
                if (status.isNotBlank() && !status.equals("active", ignoreCase = true)) {
                    append("（").append(statusLabel(status)).append("）")
                }
            }
        }
        views.setTextViewText(R.id.widget_plan, planLabel)

        // 剩余额度：优先显示金额，金额缺失时才退回百分比
        val remaining = snapshot.totalRemaining
        val percent = snapshot.usagePercent
        views.setTextViewText(
            R.id.widget_remaining,
            when {
                remaining != null -> dev.zxeb.ccusage.model.Format.usd(remaining)
                percent != null -> "${(100.0 - percent).toInt()}%"
                else -> "--"
            },
        )

        // 月度进度条 = 已用比例
        views.setProgressBar(
            R.id.widget_monthly_progress,
            100,
            percent?.toInt()?.coerceIn(0, 100) ?: 0,
            percent == null,
        )

        // 本期 token（官方数据，以 M 显示）
        views.setTextViewText(
            R.id.widget_tokens,
            dev.zxeb.ccusage.model.Format.millions(snapshot.tokensTotal),
        )
        views.setTextViewText(
            R.id.widget_tokens_label,
            if (snapshot.tokenBasis == dev.zxeb.ccusage.model.TokenBasis.ACCOUNT_TOTAL) {
                "累计 tokens"
            } else {
                "本期 tokens"
            },
        )

        // 5 小时窗口
        // 注意：percent 是带自定义 getter 的计算属性，不能直接智能转换，先取到局部变量
        val fiveHourPercent = snapshot.fiveHour?.percent
        views.setTextViewText(
            R.id.widget_fivehour,
            when {
                fiveHourPercent != null -> "5小时 ${fiveHourPercent.toInt()}%"
                !hasApiKey -> "未配置 API Key"
                else -> "5小时 --"
            },
        )

        // 更新时间 + 陈旧标记
        val stamp = dev.zxeb.ccusage.model.Format.resetAt(snapshot.fetchedAt, Instant.now())
        views.setTextViewText(
            R.id.widget_updated,
            if (snapshot.source == DataSource.STALE) "$stamp ⚠" else stamp,
        )
    }

    // ------------------------------------------------------------------
    // 2×2：套餐 · 剩余百分比（大字）· 进度 · 本期 token
    // ------------------------------------------------------------------

    private fun renderCompact(
        context: Context,
        views: RemoteViews,
        snapshot: UsageSnapshot,
        hasApiKey: Boolean,
    ) {
        views.setTextViewText(R.id.widget_plan, snapshot.planName ?: "Command Code")

        val percent = snapshot.usagePercent
        views.setTextViewText(
            R.id.widget_percent,
            if (percent != null) "${(100.0 - percent).toInt()}%" else "--",
        )
        views.setTextViewText(R.id.widget_percent_caption, "月度剩余")

        views.setProgressBar(
            R.id.widget_monthly_progress,
            100,
            percent?.toInt()?.coerceIn(0, 100) ?: 0,
            percent == null,
        )

        views.setTextViewText(
            R.id.widget_tokens,
            dev.zxeb.ccusage.model.Format.millions(snapshot.tokensTotal),
        )
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

    /** 渲染并推送给所有已添加的实例（4×2 与 2×2 各推各的布局）。 */
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

    private const val TAG = "CcWidget"
}

/**
 * 4×2 小组件（主）。澎湃小部件规范要求的元数据见 AndroidManifest。
 *
 * 关键适配：小米小部件**去掉了系统原有的定时刷新**，改为「曝光刷新」——
 * 用户滑到有 Widget 的桌面页时，系统发 `miui.appwidget.action.APPWIDGET_UPDATE`。
 * 因此 [onReceive] 必须显式处理这个 action（见小米规范 §2.2）。
 */
class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 先用缓存渲染，保证任何情况下都不空白
        WidgetRenderer.updateAll(context)
        // 再触发一次后台拉取，拿到新数据后会再次刷新
        WidgetRefreshWorker.enqueue(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIUI_WIDGET_UPDATE) {
            // 澎湃曝光刷新：包含被曝光实例的 id 数组（小米规范 §2.2 示例）
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            val manager = AppWidgetManager.getInstance(context)
            if (manager != null) {
                onUpdate(context, manager, ids ?: IntArray(0))
            }
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 尺寸变化后重新渲染（4×6 / 5×6 网格、有无搜索框等布局差异由 match_parent 兜住）
        WidgetRenderer.updateAll(context)
    }

    companion object {
        /** 澎湃曝光刷新 action。 */
        const val ACTION_MIUI_WIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"
    }
}

/** 2×2 精简版。与 [UsageWidgetProvider] 共用同一个 `android:label`，澎湃详情页会聚合展示。 */
class CompactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetRenderer.updateAll(context)
        WidgetRefreshWorker.enqueue(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UsageWidgetProvider.ACTION_MIUI_WIDGET_UPDATE) {
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            val manager = AppWidgetManager.getInstance(context)
            if (manager != null) {
                onUpdate(context, manager, ids ?: IntArray(0))
            }
        } else {
            super.onReceive(context, intent)
        }
    }
}

/** 抓取完成后刷新小组件（主进程调用）。 */
fun refreshWidgetsAsync(context: Context) {
    val pending = goAsyncScope()
    pending.launch { runCatching { WidgetRenderer.updateAll(context) } }
}

private var asyncScope: CoroutineScope? = null

private fun goAsyncScope(): CoroutineScope =
    asyncScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { asyncScope = it }
