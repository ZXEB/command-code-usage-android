# Command Code 用量统计 · Android

澎湃风格（HyperOS）的 Command Code 用量客户端。用 [Miuix](https://github.com/compose-miuix-ui/miuix) 组件库构建，
底栏是液态玻璃质感，并附带**澎湃原生小部件**（4×2 与 2×2）。

> 数据全部来自 Command Code **官方账单接口**的只读 GET 请求，不消耗额度、不修改服务端任何状态。

---

## 功能

| 功能 | 说明 |
|---|---|
| 套餐额度 | 套餐名、订阅状态、标称额度、剩余 / 总额度池、已用百分比 |
| 三个用量窗口 | 5 小时 / 每周 / 每月，各带进度条、剩余额度、重置倒计时 |
| 本计费周期 tokens | 以 **M（百万）** 显示，并拆分输入 / 输出、请求次数、均次成本 |
| 桌面小部件 | 4×2（完整）与 2×2（精简）两种尺寸，澎湃原生小部件 |
| 液态玻璃底栏 | 实时模糊 + 玻璃折射 + 边缘高光；不支持时自动降级 |
| 明暗主题 | 跟随系统，使用 Monet 动态取色 |

---

## 数据来源

四个**只读**端点（不消耗额度）：

| 端点 | 用途 |
|---|---|
| `GET /alpha/whoami` | 账号与组织（`orgId` 来源） |
| `GET /alpha/billing/credits` | 额度池与限流窗口（核心） |
| `GET /alpha/billing/subscriptions` | 套餐与计费周期 |
| `GET /alpha/usage/summary` | 本计费周期消耗与 token |

可选：`GET /metrics/usage`（需 metrics token）用于取**账户级累计 token**。

鉴权用 Command Code 的 API Key（`user_` 开头），通过 `Authorization: Bearer` 发送。

### 实现时踩过的坑（都已处理并写进测试）

1. **`windowLimits` 是顶层字段**，和 `credits` 平级，**不在** `credits` 里面。
   官方 CLI 里写的是 `e.credits?.windowLimits`，那个 `e.credits` 指整个响应体，名字有歧义。
2. **窗口上限字段名是 `cap` 不是 `limit`**。按常识猜成 `limit` 会解析出空窗口。
3. **`resetAt` 是 epoch 毫秒**，不是 ISO 字符串。按 ISO 解析会得到 `Invalid Date`。
4. **账单端点很慢**（实测 `whoami` 7~17s、`subscriptions` 可达 20s+、`summary` ~8s）。
   因此四个请求**并行**发起，单请求超时 **45 秒**，并在等待时给出进度反馈。
5. **`usage/summary` 的 `since` 参数被服务端忽略**。传周期起点、不传、传 2020 年，
   三种写法返回**完全相同**的数字，且 `periodBasis` 恒为 `billing-period`。
   所以界面上如实标注为「**本计费周期**」，而不是含糊地写「累计」让人误以为是注册至今。
6. **套餐标称额度必须内置，且按长度倒序前缀匹配**。
   `individual-pro` 是 `individual-pro-v1` 的前缀，按表内顺序匹配会把 $80 的 Pro 显示成 $30。

### 诚实性设计

额度显示错了比不显示更糟，所以：

- **绝不编造数字**：拿不到就是拿不到，界面显示 `--`，不用 `0` 冒充；
- **旧数据明确标注**：刷新失败时保留缓存，但标为「⚠ 本次刷新失败，以上为 X 时刻的旧数据」；
- **部分失败如实列出**：单个端点挂掉不影响其余数据，缺失项列在「部分数据未取到」；
- **错误可读可操作**：401 说「API Key 无效或已过期」，超时点名是哪条端点，不把英文原文丢给用户。

---

## 澎湃小部件适配

严格按小米官方《小部件技术规范与系统能力说明》实现：

| 规范 | 实现 |
|---|---|
| §1.3 独立进程 | `android:process=":widgetProvider"`；渲染只读 `SharedPreferences`，不发网络、不碰 Compose |
| §2.1 曝光刷新 | `miuiWidgetRefresh="exposure"` + `miuiWidgetRefreshMinInterval="60000"` |
| §2.2 曝光广播 | `onReceive` 显式处理 `miui.appwidget.action.APPWIDGET_UPDATE` |
| §3 尺寸 | 4×2 = `300×110dp`，2×2 = `110×110dp` |
| §4 小米标识 / 聚合 | `miuiWidget="true"`；两个尺寸共用 `android:label`，详情页自动聚合 |
| §7.1 圆角 | 根布局 `android:id="@android:id/background"` + 不透明背景色 |
| §8 布局兼容 | 根布局 `match_parent`、内容居中、无绝对尺寸 |
| §9 清数据 | 无数据时渲染引导视图，不空白、不崩溃 |
| §10 版本号 | `<application>` 下声明 `miuiWidgetVersion` |

### 刷新机制

小米小部件**去掉了系统原有的定时刷新**，改为「曝光刷新」。所以刷新由三条路径组成：

1. **曝光刷新**：用户滑到有小组件的桌面页时系统触发；
2. **周期兜底**：`WorkManager` 每 15 分钟（可配置，最短 15 分钟）；
3. **App 主动刷新**：应用内下拉刷新拿到新数据后，直接 `AppWidgetManager.updateAppWidget()` 推送。

### 小部件显示内容

- **4×2**：套餐名 · 月度剩余金额 · 月度进度条 · 本期 tokens（大字，M）· 5 小时窗口 · 更新时间
- **2×2**：套餐名 · 月度剩余百分比（大字）· 进度条 · 本期 tokens

---

## 液态玻璃底栏

用 [`miuix-blur`](https://github.com/compose-miuix-ui/miuix) 实现：

1. 页面内容容器挂 `Modifier.layerBackdrop(backdrop)`，把绘制内容录进图形层；
2. 底栏挂 `Modifier.drawBackdrop(...)`，对图层做**高斯模糊**（44dp）+ **颜色混合**（提亮 + 饱和度 1.45）；
3. 再用 `Highlight.GlassStrokeMiddleLight/Dark` 画一圈带定向光的玻璃描边，形成边缘高光。

> ⚠️ 所有模糊效果都依赖 `RuntimeShader`，**Android 需要 API 33+**（本项目 `minSdk = 33`）。
> 代码用 `isRuntimeShaderSupported()` 门控，不支持时自动降级为不透明胶囊底栏，功能不受影响。

---

## 构建

**本项目在 GitHub Actions 上构建，不需要本地环境。**

推送 `main` 或手动触发 `workflow_dispatch` 即可产出 APK；打 `v*` tag 会自动发布 Release。

### 签名密钥

release 构建需要以下 4 个仓库 secret（缺任一个则只构建 debug）：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | keystore（PKCS#12）的 base64 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

生成方式：

```bash
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 10950 -nodes \
  -subj "/CN=Command Code Usage/O=YourOrg/C=CN"
openssl pkcs12 -export -in cert.pem -inkey key.pem -out release.p12 -name ccusage -passout pass:YOUR_PASSWORD
base64 -w0 release.p12    # Windows: certutil -encode release.p12 out.txt
```

### 技术栈

| 组件 | 版本 |
|---|---|
| Gradle | 9.6.1 |
| Android Gradle Plugin | 9.2.1（使用内置 Kotlin 支持） |
| Kotlin | 2.4.0 |
| Compose Multiplatform | 1.11.1 |
| Miuix | 0.9.3 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 33 |

> AGP 9 起**内置 Kotlin 支持**，不能再应用 `org.jetbrains.kotlin.android` 插件，否则报
> "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"。

---

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖：

- **`PlanCatalogTest`** — `individual-pro-v1` 必须命中 $80 而非 $30；下划线 / 大小写归一化；未知套餐返回 null
- **`QuotaProjectorTest`** — 三个已知坑（`windowLimits` 在顶层、`cap` 不是 `limit`、`resetAt` 是毫秒）；
  额度池 `max()` 防负进度；订阅非 active 时改用 `spent + remaining`；缺字段不置零；模糊输入容错
- **`FormatTest`** — token 以 M 显示；`null` 一律 `--`；均次成本 4 位小数；倒计时三档
- **`StoresTest`** — 缓存往返、null 不置零、缓存损坏不崩
- **`WidgetRendererTest`** — 各状态渲染不崩；**清单契约**：独立进程、`miuiWidget` 标识、
  曝光刷新配置、两尺寸 label 一致、尺寸符合官方建议、根布局 `@android:id/background`

---

## 隐私

- API Key 只保存在本机应用私有存储（`MODE_PRIVATE` 的 `SharedPreferences`）；
- 已通过 `backup_rules.xml` / `data_extraction_rules.xml` **排除出云备份与设备迁移**；
- 不写入日志、不上传任何第三方；仓库与 CI 中不含任何真实凭据。

---

## 免责声明

本项目为个人学习与研究用途，与 Command Code 官方无关联。

- 所有额度数据来自官方账单接口的**只读 GET**，不消耗额度、不修改服务端状态；
- 接口与字段可能随官方更新而变化；代码对未知字段做了容错，拿不到数据时如实报错而非伪造数值；
- 请遵守 [Command Code 服务条款](https://commandcode.ai/tos)。

## 许可

MIT
