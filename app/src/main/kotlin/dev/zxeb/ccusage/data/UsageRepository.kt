package dev.zxeb.ccusage.data

import android.content.Context
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.model.UsageError
import dev.zxeb.ccusage.model.UsageSnapshot
import dev.zxeb.ccusage.net.CommandCodeClient
import dev.zxeb.ccusage.net.OkHttpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** 界面要渲染的完整状态。 */
data class UiState(
    val snapshot: UsageSnapshot? = null,
    val source: DataSource = DataSource.EMPTY,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** 配置缺失等需要引导用户去设置页的情况。 */
    val needsSetup: Boolean = false,
) {
    val hasSnapshot: Boolean get() = snapshot != null
}

/**
 * 用量数据的单一入口。
 *
 * 职责：
 * - 拉取（委托 [CommandCodeClient]，并做并发去重）；
 * - 缓存（[SnapshotStore]）：成功写缓存；失败时**如实标注旧数据**而不是假装成功；
 * - 对 UI 暴露 [StateFlow]；
 * - 抓取成功后回调 [onSnapshotUpdated]，让调用方把新数据推给桌面小组件。
 *
 * 诚实性设计（docs/QUOTA.md §6）：刷新失败时保留旧数据但把 `source` 置为
 * [DataSource.STALE] 并把错误暴露出去，界面必须显示「⚠️ 本次刷新失败，以上为 X 时刻的旧数据」。
 */
class UsageRepository(
    private val context: Context,
    private val settings: SettingsStore = SettingsStore(context),
    private val store: SnapshotStore = SnapshotStore(context),
    private val client: CommandCodeClient = CommandCodeClient(OkHttpTransport()),
) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 并发去重：多个入口同时刷新时只跑一次。 */
    private val refreshMutex = Mutex()

    /** 抓取成功后的回调（用于刷新桌面小组件）。 */
    var onSnapshotUpdated: ((UsageSnapshot) -> Unit)? = null

    /** 应用启动时用缓存先把界面填上，避免白屏。 */
    fun primeFromCache() {
        val cached = store.load() ?: return
        if (_state.value.snapshot != null) return
        _state.value = UiState(
            snapshot = cached,
            source = DataSource.STALE,
            refreshing = false,
            needsSetup = !settings.hasApiKey,
        )
    }

    val hasApiKey: Boolean get() = settings.hasApiKey

    fun settingsStore(): SettingsStore = settings

    /**
     * 刷新一次。
     *
     * @param force 用户手动下拉刷新。当前实现两种模式行为一致（没有做时间窗缓存），
     *   保留参数是为了让小组件的「曝光刷新」与手动刷新在将来能区分节流策略。
     */
    suspend fun refresh(force: Boolean = false) {
        if (!settings.hasApiKey) {
            _state.value = _state.value.copy(
                refreshing = false,
                needsSetup = true,
                error = UsageError.MissingKey.message,
            )
            return
        }

        refreshMutex.withLock {
            _state.value = _state.value.copy(refreshing = true, error = null)

            try {
                val snapshot = client.fetchSnapshot(
                    apiKey = settings.apiKey,
                    metricsToken = settings.metricsToken.ifBlank { null },
                )
                store.save(snapshot)
                _state.value = UiState(
                    snapshot = snapshot,
                    source = DataSource.LIVE,
                    refreshing = false,
                    needsSetup = false,
                )
                onSnapshotUpdated?.invoke(snapshot)
            } catch (e: UsageError) {
                handleFailure(e.message)
            } catch (e: Exception) {
                // 未知异常也要给出可读文案，绝不把堆栈丢给用户
                handleFailure("刷新失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun handleFailure(message: String) {
        val cached = _state.value.snapshot ?: store.load()
        _state.value = UiState(
            snapshot = cached,
            // 有旧数据 -> STALE（界面标注「旧数据」）；没有任何数据 -> ERROR
            source = if (cached != null) DataSource.STALE else DataSource.ERROR,
            refreshing = false,
            error = message,
            needsSetup = !settings.hasApiKey,
        )
    }

    fun clearCache() {
        store.clear()
        _state.value = UiState(needsSetup = !settings.hasApiKey)
    }

    /** 最近一次成功抓取的时刻，供小组件与界面显示新鲜度。 */
    fun lastFetchedAt(): Instant? = _state.value.snapshot?.fetchedAt ?: store.load()?.fetchedAt
}
