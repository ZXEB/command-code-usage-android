package dev.zxeb.ccusage

import android.app.Application
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.data.UsageRepository
import dev.zxeb.ccusage.widget.WidgetRefreshWorker

class CcUsageApp : Application() {

    /** 进程级单例。 */
    val repository: UsageRepository by lazy { UsageRepository(this) }

    override fun onCreate() {
        super.onCreate()
        repository.primeFromCache()
        // 小组件的后台刷新是「周期任务 + 手动触发」，没有系统定时刷新兜底，
        // 所以每次冷启动都按用户设置重新登记一次（幂等，用 UPDATE 策略覆盖）。
        // 否则用户从没进过设置页时，周期任务根本不会注册，桌面小组件只能等
        // 用户打开 App 才会更新。
        runCatching {
            WidgetRefreshWorker.schedule(this, SettingsStore(this).autoRefreshMinutes)
        }
    }
}
