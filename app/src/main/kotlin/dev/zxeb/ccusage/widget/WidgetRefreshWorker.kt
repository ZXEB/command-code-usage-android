package dev.zxeb.ccusage.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.data.SnapshotStore
import dev.zxeb.ccusage.model.UsageError
import dev.zxeb.ccusage.net.CommandCodeClient
import dev.zxeb.ccusage.net.OkHttpTransport
import java.util.concurrent.TimeUnit

/**
 * 小组件的后台联网刷新。
 *
 * 为什么必须有它：小米小部件**去掉了系统原有的定时刷新**（规范 §2.1），只保留曝光刷新。
 * 用户不划到那一页就不会有新数据，所以需要一条兜底路径：
 * - **周期任务**：最短 15 分钟（WorkManager 下限），跟随设置里的自动刷新间隔；
 * - **一次性任务**：曝光刷新或点击刷新时立即触发。
 *
 * 职责边界（很重要）：**只有这个 Worker 联网**。它拿到数据后写缓存，然后调
 * [WidgetRenderer.updateAll] 重绘 —— 刷新的联网部分与绘制部分是分开的。
 *
 * 这个 Worker 声明在 `:widgetProvider` 进程（见 AndroidManifest），符合小米规范
 * §1「Widget 进程只能运行 Widget 内容准备和刷新相关的逻辑」。
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        if (!settings.hasApiKey) return Result.success()

        return try {
            val client = CommandCodeClient(OkHttpTransport())
            val snapshot = client.fetchSnapshot(
                apiKey = settings.apiKey,
                metricsToken = settings.metricsToken.ifBlank { null },
            )
            SnapshotStore(applicationContext).save(snapshot)
            // 用刚写入的缓存重绘
            WidgetRenderer.updateAll(applicationContext)
            Result.success()
        } catch (e: UsageError.Unauthorized) {
            // Key 失效：重试没有意义，保留旧数据等用户去设置页更新
            Result.success()
        } catch (e: Exception) {
            // 账单接口很慢，超时是常态：退避重试，超过上限就放弃（曝光刷新还会再来）
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val ONE_TIME_WORK = "cc_widget_refresh_once"
        private const val PERIODIC_WORK = "cc_widget_refresh_periodic"
        private const val MIN_PERIODIC_MINUTES = 15L

        /** 立即刷新一次。已排队则不重复入队（曝光刷新触发很频繁）。 */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
            }
        }

        /**
         * 注册周期刷新。
         *
         * @param minutes 0 表示关掉周期刷新（只依赖曝光刷新）。
         */
        fun schedule(context: Context, minutes: Int) {
            val manager = WorkManager.getInstance(context)
            if (minutes <= 0) {
                runCatching { manager.cancelUniqueWork(PERIODIC_WORK) }
                return
            }
            // WorkManager 的周期下限就是 15 分钟，比它更短的值会被系统抬到 15
            val interval = maxOf(minutes.toLong(), MIN_PERIODIC_MINUTES)
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                interval,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                manager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }
    }
}
