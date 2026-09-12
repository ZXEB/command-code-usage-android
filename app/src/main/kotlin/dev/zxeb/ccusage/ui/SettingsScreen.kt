package dev.zxeb.ccusage.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zxeb.ccusage.data.SettingsStore
import dev.zxeb.ccusage.widget.CompactWidgetProvider
import dev.zxeb.ccusage.widget.UsageWidgetProvider
import dev.zxeb.ccusage.widget.WidgetRefreshWorker
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：API Key、可选 metrics token、自动刷新、小组件、缓存。
 */
@Composable
fun SettingsScreen(
    onSaved: () -> Unit,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var metricsToken by remember { mutableStateOf(settings.metricsToken) }
    var autoRefresh by remember { mutableStateOf(settings.autoRefreshMinutes) }
    var showFiveHour by remember { mutableStateOf(settings.widgetShowFiveHour) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var widgetHint by remember { mutableStateOf<String?>(null) }
    // 添加/移除小组件后用于强制重算实例数
    var widgetRefreshKey by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SmallTitle("凭据")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "Command Code API Key",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "以 user_ 开头，从 Command Code 控制台获取。只做只读查询，不消耗额度。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                TextField(
                    value = metricsToken,
                    onValueChange = { metricsToken = it },
                    label = "Metrics token（可选）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "填了就从官方 /metrics/usage 取账户级累计 token；不填则显示本计费周期用量。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                Button(
                    onClick = {
                        scope.launch {
                            settings.apiKey = apiKey
                            settings.metricsToken = metricsToken
                            savedHint = "已保存，正在刷新…"
                            onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存并刷新")
                }

                savedHint?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }

        SmallTitle("刷新")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "自动刷新间隔：${if (autoRefresh == 0) "关闭" else "$autoRefresh 分钟"}",
                    style = MiuixTheme.textStyles.main,
                )
                Row3(
                    options = listOf(0, 15, 30, 60),
                    current = autoRefresh,
                    labelOf = { if (it == 0) "关闭" else "${it}分" },
                    onSelect = {
                        autoRefresh = it
                        settings.autoRefreshMinutes = it
                        WidgetRefreshWorker.schedule(context, it)
                    },
                )
                Text(
                    text = "小米小部件已去掉系统定时刷新，桌面小组件依赖「曝光刷新」" +
                        "（滑到该页时触发）。这里的周期刷新是兜底路径，最短 15 分钟。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        SmallTitle("桌面小组件")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "提供 4×2（完整）与 2×2（精简）两种尺寸，在桌面添加时可选。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                WidgetStateRow(context, widgetRefreshKey)

                Button(
                    onClick = { requestPinWidget(context) { widgetHint = it } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("添加到桌面")
                }

                widgetHint?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }

                // pin 在部分 HyperOS 版本上不可用，给出手动路径作为兜底
                Text(
                    text = "如果上面没有反应：长按桌面空白处 → 添加小部件 → " +
                        "搜索「Command Code 用量」。安装后系统刷新小组件列表可能需要一点时间。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        SmallTitle("缓存")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "清除本地缓存的用量快照。API Key 不会被清除，请用下方按钮单独清。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = "清除缓存",
                    onClick = {
                        scope.launch {
                            onCleared()
                            savedHint = "缓存已清除"
                        }
                    },
                )
                TextButton(
                    text = "清除 API Key",
                    onClick = {
                        settings.clearCredentials()
                        apiKey = ""
                        metricsToken = ""
                        savedHint = "凭据已清除"
                    },
                )
            }
        }

        SmallTitle("隐私")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "API Key 只保存在本机应用私有存储里，已排除出云备份与设备迁移；" +
                        "不会上传到任何第三方，也不写入日志。\n\n" +
                        "所有用量数据来自 Command Code 官方账单接口的只读 GET 请求，不修改服务端状态。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(90.dp))
    }
}

/** 三选一的简易分段控件（Miuix 没有现成的 SegmentedControl，用 Button/TextButton 组合）。 */
@Composable
private fun Row3(
    options: List<Int>,
    current: Int,
    labelOf: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            if (option == current) {
                Button(onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) {
                    Text(labelOf(option))
                }
            } else {
                TextButton(
                    text = labelOf(option),
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WidgetStateRow(context: Context, refreshKey: Int) {
    val manager = remember { AppWidgetManager.getInstance(context) }
    // refreshKey 变化时重算，避免添加完小组件后数字还是旧的
    val full = remember(refreshKey) {
        manager?.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))?.size ?: 0
    }
    val compact = remember(refreshKey) {
        manager?.getAppWidgetIds(ComponentName(context, CompactWidgetProvider::class.java))?.size ?: 0
    }
    val total = full + compact
    Text(
        text = if (total == 0) {
            "当前桌面还没有添加小组件"
        } else {
            "已在桌面添加 $total 个（4×2：$full，2×2：$compact）"
        },
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * 请求把小组件 pin 到桌面。
 *
 * 之前这里「点了没反应」，原因是把所有失败路径都静默吞掉了。按 AOSP
 * `AppWidgetServiceImpl.requestPinAppWidget()` 的实现，失败会**返回 false 而不抛异常**，
 * 常见原因有三类，现在逐条区分并给出可操作提示：
 *
 * 1. `!isRequestPinAppWidgetSupported` —— 桌面自身不支持 pin（部分 HyperOS 版本如此）；
 * 2. `lookupProviderLocked(...) == null` —— 系统还没把本应用的小组件登记进列表
 *    （安装后需要一点时间，或需要重启桌面进程）；
 * 3. `widgetCategory` 不含 HOME_SCREEN —— 配置写错了（本项目的 XML 已显式声明）。
 *
 * 注意：pin 失败**不等于**小组件不可用 —— 用户仍然可以长按桌面 →「添加小部件」手动添加。
 * 所以失败时要把这条退路明确告诉用户，而不是什么都不显示。
 */
private fun requestPinWidget(context: Context, onResult: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onResult("当前系统版本不支持一键添加，请长按桌面 → 添加小部件")
        return
    }
    val manager = AppWidgetManager.getInstance(context)
    if (manager == null) {
        onResult("系统服务不可用，请长按桌面 → 添加小部件")
        return
    }
    if (!manager.isRequestPinAppWidgetSupported) {
        onResult(
            "当前桌面不支持一键添加。请长按桌面空白处 → 添加小部件 → " +
                "搜索「Command Code 用量」",
        )
        return
    }

    val requested = runCatching {
        manager.requestPinAppWidget(
            ComponentName(context, UsageWidgetProvider::class.java),
            null,
            null,
        )
    }.getOrElse { false }

    if (requested) {
        onResult("已发起添加请求，请在弹出的确认框里点「添加」")
    } else {
        // 走到这里说明系统登记还没完成（或桌面拒绝），给出确定可行的退路
        onResult(
            "系统暂时没能拉起添加流程（小组件列表可能还没刷新）。" +
                "请长按桌面空白处 → 添加小部件 → 搜索「Command Code 用量」",
        )
    }
}
