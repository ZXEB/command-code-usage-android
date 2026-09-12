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
import top.yukonga.miuix.kmp.basic.Switch
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
                WidgetStateRow(context)

                Button(
                    onClick = { requestPinWidget(context) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                ) {
                    Text("添加到桌面")
                }
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
private fun WidgetStateRow(context: Context) {
    val manager = remember { AppWidgetManager.getInstance(context) }
    val full = remember {
        manager?.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))?.size ?: 0
    }
    val compact = remember {
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

/** 请求把小组件 pin 到桌面。部分桌面不支持时系统会静默忽略。 */
private fun requestPinWidget(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = AppWidgetManager.getInstance(context) ?: return
    if (!manager.isRequestPinAppWidgetSupported) return
    runCatching {
        manager.requestPinAppWidget(ComponentName(context, UsageWidgetProvider::class.java), null, null)
    }
}
