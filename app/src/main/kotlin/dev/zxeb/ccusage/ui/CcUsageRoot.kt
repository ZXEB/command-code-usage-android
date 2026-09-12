package dev.zxeb.ccusage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用根节点。
 *
 * 页面内容容器通过 [layerBackdrop] 把自己的绘制内容录进 [rememberLayerBackdrop] 生成的图层，
 * 底栏 [dev.zxeb.ccusage.ui.components.GlassBottomBar] 再对这个图层做实时模糊 —— 这就是液态玻璃。
 *
 * 内容区刻意**不**为底栏留 padding（只留顶部），这样滚动内容会从玻璃底栏下面穿过去，
 * 模糊才有东西可折射；可滚动容器自己用 contentPadding 兜住最后一项。
 */
@Composable
fun CcUsageRoot() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var taps by remember { mutableIntStateOf(0) }

    val backgroundColor = MiuixTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        // 先铺不透明底色：否则模糊会把透明像素的颜色扩散成色块（miuix-blur 文档明确提示）
        drawRect(backgroundColor)
        drawContent()
    }

    val items = remember {
        listOf(
            NavigationItem("概览", MiuixIcons.Home),
            NavigationItem("明细", MiuixIcons.Info),
            NavigationItem("设置", MiuixIcons.Settings),
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = items[selectedIndex].label) },
        bottomBar = {
            dev.zxeb.ccusage.ui.components.GlassBottomBar(
                backdrop = backdrop,
                items = items,
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .layerBackdrop(backdrop),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 占位内容：Phase 1 用于验证 Miuix + blur 依赖链路可编译可运行
                SmallTitle("构建校验")
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Miuix 组件库已接入")
                        Text(
                            "液态玻璃底栏：这是一个用于验证渲染链路的占位页面。",
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("交互测试：$taps")
                        Button(onClick = { taps++ }) {
                            Text("点我")
                        }
                    }
                }
                // 撑高页面，方便观察内容穿过玻璃底栏时的模糊效果
                repeat(6) { index ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("占位条目 ${index + 1}")
                            Text(
                                "滚动这一段文字，观察底栏玻璃的折射与高光。",
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                    }
                }
                Box(Modifier.padding(bottom = 120.dp))
            }
        }
    }
}
