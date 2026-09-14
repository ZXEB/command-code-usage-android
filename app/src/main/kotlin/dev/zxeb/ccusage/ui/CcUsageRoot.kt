package dev.zxeb.ccusage.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zxeb.ccusage.CcUsageApp
import dev.zxeb.ccusage.R
import dev.zxeb.ccusage.model.DataSource
import dev.zxeb.ccusage.ui.components.GlassTopButton
import dev.zxeb.ccusage.ui.components.HyperMenu
import dev.zxeb.ccusage.ui.glass.component.FloatingBottomBar
import dev.zxeb.ccusage.ui.glass.component.FloatingBottomBarItem
import dev.zxeb.ccusage.widget.WidgetRefreshWorker
import dev.zxeb.ccusage.widget.WidgetRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant

/**
 * 应用根节点（澎湃 4 图库布局）。
 *
 * - 页面内容通过 [layerBackdrop] 录进图层，底栏玻璃与顶栏圆钮实时模糊它 —— 这就是液态玻璃；
 * - 顶栏没有实色底：右上角两颗玻璃圆钮悬浮在滚动内容之上，内容从它们下方穿过；
 * - 大标题（「概览」等）放进滚动内容顶部，随页面一起被滑走/滑入；
 * - 页面切换用 [HorizontalPager]：点底栏后两页同屏横向滑动，与玻璃折射联动。
 *
 * 内容区刻意不为底栏留 padding，让滚动内容从玻璃底栏下穿过；
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

    val pageCount = 3
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })
    // 刷新按钮上的动画下标（页面滑到一半时就跟着切，与内容同步）
    var selectedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }.distinctUntilChanged().collect {
            selectedIndex = it
        }
    }
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
            TabSpec(context.getString(R.string.tab_overview), MiuixIcons.VerticalSplit),
            TabSpec(context.getString(R.string.tab_detail), MiuixIcons.Info),
            TabSpec(context.getString(R.string.tab_settings), MiuixIcons.Settings),
        )
    }

    // 液态玻璃需要两类 GPU 能力都在：RenderEffect 做背景模糊采样、AGSL 跑折射着色器。
    // 缺任意一项就退回实色 pill —— 组件保证这个降级是完整可用的（保留位移/缩放动效）。
    val glassSupported = remember { isRenderEffectSupported() && isRuntimeShaderSupported() }

    // ⋮ 菜单
    var menuExpanded by remember { mutableStateOf(false) }
    var moreButtonBounds by remember { mutableStateOf<Rect?>(null) }

    // 抓到新数据后把结果推给桌面小组件（小米规范 §10：App 主动刷新小部件）。
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

    // 刷新动作：下拉刷新与顶栏/菜单共用同一条路径
    val doRefresh: () -> Unit = {
        scope.launch {
            repository.refresh(force = true)
            WidgetRefreshWorker.enqueue(context)
        }
    }

    val goto: (Int) -> Unit = { page ->
        scope.launch {
            pagerState.animateScrollToPage(
                page,
                animationSpec = tween(340),
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
            // ---- 页面内容（录进 backdrop，供底栏/顶钮采样） ----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                PullToRefresh(
                    isRefreshing = state.refreshing,
                    onRefresh = doRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        // 每页独立滚动容器；大标题在滚动内容顶部，随页面滑走。
                        // edge-to-edge 下必须自己避开状态栏，否则大标题会被顶到状态栏
                        // 底下、上沿被裁掉（「上面的字显示不全」就是这个原因）。
                        // 只加内边距、不裁剪：内容向上滚动时仍从顶钮下方穿过，
                        // 顶栏玻璃才有内容可采样，模糊效果才看得出来。
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .statusBarsPadding(),
                        ) {
                            LargePageTitle(title = items[page].label)
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                when (page) {
                                    0 -> OverviewScreen(
                                        state = state,
                                        now = now,
                                        onOpenSettings = { goto(2) },
                                    )

                                    1 -> DetailScreen(snapshot = state.snapshot, now = now)

                                    else -> SettingsScreen(
                                        onSaved = { scope.launch { repository.refresh(force = true) } },
                                        onCleared = {
                                            repository.clearCache()
                                            WidgetRenderer.updateAll(context)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 顶部悬浮层：两颗玻璃圆钮（内容从下方穿过并被采样模糊） ----
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassTopButton(
                    backdrop = backdrop,
                    icon = MiuixIcons.Refresh,
                    contentDescription = context.getString(R.string.action_refresh),
                    onClick = doRefresh,
                )
                GlassTopButton(
                    backdrop = backdrop,
                    icon = MiuixIcons.More,
                    contentDescription = context.getString(R.string.action_more),
                    onClick = { menuExpanded = !menuExpanded },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        moreButtonBounds = coords.boundsInWindow()
                    },
                )
            }

            // ---- ⋮ 锚点展开菜单 ----
            HyperMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                anchorBounds = moreButtonBounds,
                items = listOf("前往设置", "立即刷新"),
                onItemClick = { index ->
                    when (index) {
                        0 -> goto(2)
                        1 -> doRefresh()
                    }
                },
            )

            // ---- 底部液态玻璃悬浮导航 ----
            //
            // 悬浮形态：左右留 28dp 让玻璃边缘有内容可采样，底部避开手势条。
            // 内容区不为它留 padding，滚动内容从玻璃下方穿过 —— 这正是折射的来源。
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 28.dp, end = 28.dp, bottom = 16.dp),
                selectedIndex = selectedIndex,
                // 组件约定：外部改 selectedIndex 只驱动 pill 动画，不会回调；
                // 只有用户点按/拖拽结束才走 onSelected。这里统一交给 goto() 滑页，
                // pill 会随 pager 的 targetPage 回流（LaunchedEffect 同步）。
                onSelected = goto,
                backdrop = backdrop,
                tabsCount = items.size,
                isBlurEnabled = glassSupported,
            ) { activateTab ->
                items.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        selected = selectedIndex == index,
                        // 必须调组件给的 activateTab，不要自己去改 selectedIndex：
                        // 它会同时驱动 pill 与拖拽状态，自己改会和 pager 互相打架。
                        onClick = { activateTab(index) },
                        // ⚠️ 必需：item 用 weight(1f)，而父 Row 是 IntrinsicSize.Min 测量，
                        // 此时 weight 子项宽度算出来是 0 —— 没有 minWidth 兜底整条栏会塌缩。
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个底栏 tab 的描述（标题 + 图标）。
 *
 * 不复用 Miuix 的 `NavigationItem`：那个类型是给 `FloatingNavigationBar` 用的，
 * 这里换成自绘的液态玻璃底栏后只需要这两个字段，用最小结构避免耦合库内类型。
 */
private data class TabSpec(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * 滚动内容顶部的大标题（澎湃 4 图库的「最近/浏览」形态）。
 *
 * 顶部留白要能容纳右上角两颗悬浮圆钮（44dp 高 + 8dp 上边距 = 52dp），
 * 否则标题会和圆钮叠在同一水平线上。参考图里标题是落在圆钮下方的。
 */
@Composable
private fun LargePageTitle(title: String) {
    Text(
        text = title,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
    )
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
