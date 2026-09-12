package dev.zxeb.ccusage

import android.app.Application
import dev.zxeb.ccusage.data.UsageRepository

class CcUsageApp : Application() {

    /** 进程级单例。小组件进程不会走到这里（它只读 SharedPreferences）。 */
    val repository: UsageRepository by lazy { UsageRepository(this) }

    override fun onCreate() {
        super.onCreate()
        repository.primeFromCache()
    }
}
