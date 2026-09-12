package dev.zxeb.ccusage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zxeb.ccusage.CcUsageApp
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.ui.components.GlassBottomBar
import dev.zxeb.ccusage.widget.WidgetRefreshWorker
import dev.zxeb.ccusage.widget.WidgetRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant

/**
 * 应用根节点。
 *
 * 页面内容容器通过 [layerBackdrop] 把绘制内容录进图层，[GlassBottomBar] 再实时模糊它 —— 这就是液态玻璃。
 *
 * 内容区刻意**不**为底栏留 padding（只留顶部），让滚动内容从玻璃底栏下穿过，模糊才有东西可折射；
 * 每个页面自己在末尾留出 90dp 空白，避免最后一项被底栏永久遮挡。
 */
@Composable
fun CcUsageRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as CcUsageApp
    val repository = remember { app.repository }

    val state by repository.state.collectAsStateSafe()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedIndex by remember { mutableIntStateOf(0) }
    // 每分钟走一次，用于倒计时刷新（不需要重新联网）
    var now by remember { mutableStateOf(Instant.now()) }

    val backgroundColor = MiuixTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        // 先铺不透明底色：否则模糊会把透明像素的颜色扩散成色块（miuix-blur 文档明确提示）
        drawRect(backgroundColor)
        drawContent()
    }

    val items = remember {
        listOf(
            NavigationItem(context.getString(R.string.tab_overview), MiuixIcons.VerticalSplit),
            NavigationItem(context.getString(R.string.tab_detail), MiuixIcons.Info),
            NavigationItem(context.getString(R.string.tab_settings), MiuixIcons.Settings),
        )
    }

    // 抓到新数据后把结果推给桌面小组件（小米规范 §10：App 主动刷新小部件）。
    // updateAll 是纯本地绘制（只读缓存 + updateAppWidget），在主进程调用没问题，
    // 跨进程写入由 AppWidgetManager 自己处理。
    DisposableEffect(repository) {
        repository.onSnapshotUpdated = { WidgetRenderer.updateAll(context) }
        onDispose { repository.onSnapshotUpdated = null }
    }

    // 首帧拉一次
    LaunchedEffect(Unit) {
        repository.primeFromCache()
        repository.refresh()
    }

    // 倒计时每分钟重算
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }

    // 刷新失败时用 Snackbar 提示（不阻塞界面，旧数据仍在）
    LaunchedEffect(state.error, state.source) {
        val error = state.error
        if (error != null && state.source != DataSource.LIVE) {
            snackbarHostState.showSnackbar(error)
        }
    }

    // 刷新动作：下拉刷新与底栏圆形按钮共用同一条路径
    val doRefresh: () -> Unit = {
        scope.launch {
            repository.refresh(force = true)
            // 顺手触发小组件的立即刷新
            WidgetRefreshWorker.enqueue(context)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = items[selectedIndex].label) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            GlassBottomBar(
                backdrop = backdrop,
                items = items,
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
                actionIcon = MiuixIcons.Refresh,
                actionLabel = context.getString(R.string.action_refresh),
                onAction = doRefresh,
                actionEnabled = !state.refreshing,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .layerBackdrop(backdrop),
        ) {
            PullToRefresh(
                isRefreshing = state.refreshing,
                onRefresh = doRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (selectedIndex) {
                        0 -> OverviewScreen(
                            state = state,
                            now = now,
                            onOpenSettings = { selectedIndex = 2 },
                        )

                        1 -> DetailScreen(snapshot = state.snapshot, now = now)

                        else -> SettingsScreen(
                            onSaved = { scope.launch { repository.refresh(force = true) } },
                            onCleared = {
                                repository.clearCache()
                                // 缓存清掉后小组件要回到引导视图
                                WidgetRenderer.updateAll(context)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * `collectAsState` 的小包装。
 *
 * 单独抽出来是为了避免在各处重复导入 lifecycle-runtime-compose；
 * Miuix 自带 `jetbrains-lifecycle-runtime`，这里用最朴素的 collect 方式即可。
 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe(): androidx.compose.runtime.State<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this) {
        collect { state.value = it }
    }
    return state
}
