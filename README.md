# Pilot

Android 系统级手机助理。语音优先，本地能力先行，本地无法完成时交由云端模型，屏幕自动化作为最后兜底。

Pilot 不是「聊天框里接一个大模型」，而是一个能自主执行任务的助理：调节音量、创建提醒、查询行情、导航、运行终端命令、打开网页，或进入某个应用的指定页面。它按可靠性从高到低选择执行路径，只有在前述路径都不可用时才模拟点击。

## 应用信息

| 项目 | 值 |
|---|---|
| 包名 | `com.pilot.agent` |
| 版本 | 0.1.2（versionCode 3） |
| 最低系统 | Android 11（minSdk 30） |
| 目标系统 | Android 15（targetSdk 35） |
| 模块 | `:app`（应用）、`:navigation`（高德导航库） |
| ABI | arm64-v8a（单架构；`-PallAbis=true` 可打全架构） |
| 调试包体积 | 约 150 MB（含语音、沙箱、导航资产） |
| 代码规模 | app 约 4.5 万行 Kotlin，navigation 约 6 千行 |
| 单元测试 | 32 个套件 / 244 例 |

## 决策流程

```
用户输入（文字，或唤醒词「小派」+ 语音）
  → 本地能力路由（CapabilityRegistry）：开应用、调音量、提醒、音乐、记忆、文件……
      命中即执行，多数不调模型；仅需补槽位的（音乐语义、写文件内容）调用一次端侧小模型
  → 查询通道（QueryEngine）：行情、天气、新闻、车票、附近、网页搜索
      命中即 HTTP 取数据，秒级出答案，不碰屏幕
  → 云端 Agent（AgentLoop + 工具表）：多步任务、跨应用操作
      每一步先尝试直达（Intent / 组件），不行再用无障碍点屏幕
  → 护栏（Guard + ConfirmChannel）：支付、删除、对外发送强制确认
```

**核心取舍**：能用 API 的不用 Intent，能用 Intent 的不用点击，能本地判定的不上云。屏幕自动化是最后手段而非主路径——它慢、脆、界面改版即失效。

端侧规划器链（规则 → 端侧小模型 → 云端）全部无法给出有效动作时，会升级云端对话模型直接作答，而不是直接报「无法处理」。

## 功能

### 1. 入口：语音、对话与系统助理

| 能力 | 实现 | 说明 |
|---|---|---|
| 唤醒词 | sherpa-onnx KWS（assets/kws，4.6 MB） | 唤醒词不写死，用 ICU 现场将汉字转为带调音素行；改词只改设置 |
| 语音识别 | sherpa-onnx SenseVoice-Small int8 | 端侧，模型不入 APK，置于 `filesDir/models/`；说话过程中增量识别，中间文字实时显示在输入框 |
| 语音合成 | MiniMax `speech-02-hd` / 系统 TTS | 设置可切换；云端失败自动降级系统 TTS |
| 声纹 | WeSpeaker `cnceleb_resnet34_LM` | 软门禁而非身份认证：乐观放行 + 事后否决，阈值可调 |
| 打断 | `interruptEnabled` + 打断词 | 播报中可被打断 |
| 系统助理位 | `AssistActivity`（`VOICE_COMMAND`） | 长按电源 / 助手键可直达 |
| 后台形态 | 前台服务常驻通知 + 可拖动状态悬浮条 | 语音可用性与任务进度锁屏可见；不含识别文本与个人数据 |

### 2. 本地能力（快路径，不经过云端）

`assistant/routing/CapabilityRegistry.kt` 是该层的唯一目录：声明的每个 action 都必须有对应执行实现，无实现者不得声明。

| 能力 | 动作 |
|---|---|
| `app_launch` | open（带降级链的 openApp，启动后回查前台） |
| `volume` | set / adjust / status（「大一点」→ +10%，「调到 60%」→ 绝对值） |
| `device` | battery / storage / info |
| `media` | play / pause / resume / next / previous / stop / status / list |
| `reminder` | schedule / list / cancel |
| `scheduled_task` | create / list / cancel |
| `memory_write` / `memory_get` / `memory_forget` | 本地加密记忆，可检索、可模糊删除（HIGH 风险先确认） |
| `file_read` / `file_write` | 写文件属 HIGH 风险 |
| `screenshot` | 截屏 |

风险分级随能力一起声明（LOW / MEDIUM / HIGH），路由层据此决定是否先弹确认，而非依赖模型自觉。

### 3. 查询通道（不碰屏幕，秒级）

`query/QueryEngine.kt` 内置 8 个 `QueryTool`，新增数据源只需增加一个实现，调度逻辑不变：

| 工具 | 数据源 | 需要 Key |
|---|---|---|
| `NotificationQueryTool` | 通知监听（内存缓存，重启清空） | 系统通知访问权限 |
| `TrainQueryTool` | 12306 余票（支持多轮补全：车次 / 时间不全则追问） | 否 |
| `StockTool` | 新浪行情（A 股指数 / 个股；需 Referer，返回 GBK） | 否 |
| `WeatherTool` | 高德天气（外层包 `GuardedWeatherTool`） | 高德 Web Key |
| `NewsSearchTool` | Google News RSS（24h 优先，不足扩到 7 天，Bing 兜底） | 否 |
| `NearbySearchTool` | 高德 POI | 高德 Web Key |
| `WebSearchTool` | Bing HTML 优先，Brave Search 兜底 | Brave Key（可选） |
| `WebPageTool` | 正文提取（网页总结 / 抓正文） | 否 |

查询失败不当答案：`QueryResult` 区分 Answer / NeedMoreInfo / NoResult / Unavailable，失败原因走 `lastFailure`，由上层如实说明或改走浏览器，不谎报「查到了」。

新闻播报只念条目与摘要，来源留在屏幕上；查询词会剥离口语问句框架（如「今天有没重要的国际新闻」→「国际 新闻」），避免把整句残片回显给用户。

### 4. 云端 Agent：多步任务与跨应用操作

- 工具表：提醒 / 待办 / 日历 / 通知 / 记忆 / 文件 / 设备 / 截屏 / SSH（jsch）/ 沙箱执行 / MCP 外部工具 / 技能回放。
- **端侧搜索源复用**：`query` 动作把上述 8 个查询工具暴露给云端 Agent，资讯 / 行情 / 天气 / 余票类查询优先走端侧，拿不到再开浏览器，避免多步硬搜。
- 技能库 `SkillStore`：成功轨迹沉淀为技能，下次直接回放；`allowSkillReplay` 可关，回放同样过 Guard。
- `Guard` 独立于模型：按目标节点文本 + 屏幕文本双向匹配风险词，支付 / 删除 / 对外发送强制二次确认，审计留痕。
- `ConfirmChannel`：确认统一走单一通道，可按前端状态切换呈现（`onAppForeground/onAppBackground`），支持语音「确认 / 取消」（`tryVoiceAnswer`）。
- 失败如实回报：`ToolOutcome{Ok | Failed | Unknown}`，不把「结果未知」说成成功。

### 5. 系统级操作：从直达退到点击

| 层 | 来源 | 覆盖 | 耗时 |
|---|---|---|---|
| 1 静态目录 | `ActionCatalog`（系统设置页 + 系统动作 + 常见 deep link） | 约 50 条高频 | 200~800 ms |
| 2 应用内入口 | `AppLaunchIndex`（枚举所有 exported Activity 的 label） | 真机实测 2532 条 | 建索引约 2.7 s（后台），查 200 ms |
| 3 模拟点击 | 无障碍节点树 + 手势注入（`DeviceActions`） | 无 action 的页面 | 5~30 s |

- `VerifiedLauncher`：启动后回查前台窗口 / 事件流，超时返回「结果未知」而非成功——后台拦截不许谎报已打开。
- `SystemOps`：音量 / 亮度 / 电话等有 API 的一律直调，失败立即终止（缺权限重试无意义）。
- 厂商劫持 action 是常态（如华为将电池相关 action 重定向到节电助手）：落点校验 + 定点组件直启 + 同一条直达失败过不再试。

### 6. 导航（`:navigation` 模块）

- 高德 SDK：`AMap3DMap_11.3.100 / AMapNavi_11.3.100 / AMapSearch_9.8.1 / AMapLocation_11.3.101`。
- 能力：地点搜索（SDK + Web Key 两条路）、算路、转向引导、HUD、限行规避、日夜模式；导航播报并入统一 TTS 队列（NAV > USER > CHAT 抢占）。
- 运行于独立进程 `:navigation`，崩溃不影响主进程。
- 边界：SDK 算路数据版本比高德 App 旧，小区内部道路可能走不对，SDK 侧无法修正。

### 7. 沙箱终端（`:sandbox` 进程，proot + Alpine）

从 Minis 移植的 proot 沙箱，约 8.9 千行：

```
jniLibs/arm64-v8a/  libproot.so + libproot-loader.so + libproot-loader32.so
assets/             alpine-minirootfs.tar.gz（Alpine 3.21）
```

- 可执行文件伪装成 `.so`：W^X 下 `app_data_file` 标签不许执行，`nativeLibraryDir` 是唯一可执行位置。
- 真 PTY（`PtyBridge`），非管道假终端；常驻会话 + 一次性命令两种模式。
- 限流（`GlobalShellThrottle`）、空闲回收（`sandboxIdleMinutes`）、超时把 `exit=124` 还原为 UNKNOWN。
- 独立进程：一条坏命令最多崩 `:sandbox`，不影响语音与主循环。
- 边界：部分 syscall（ptrace 类）在 proot 下不可用，嵌套虚拟化跑不了；DNS 需手工配置。

### 8. 内置浏览器

进程内 WebView（`BrowserEngine`），三种呈现方式由 `BrowserSessionManager.decideSurface()` 决定（有单测）：

- **小窗**（默认）：`TYPE_APPLICATION_OVERLAY` 悬浮窗，可拖动、最小化成气泡、展开全屏。
- **后台无头**：只加载 + 提取正文，不占屏幕（Agent 自查资料时用）。
- **全屏**：用户明确要「打开网页给我看 / 登录 / 扫码」时直接全屏。

Cookie 由系统 CookieManager 共享，小窗与全屏切换按 `currentUrl` 续页。

**虚拟屏离屏渲染（`HeadlessRenderHost`，移植自 Minis）**：后台无头 WebView 不挂任何窗口，Chromium 对软件 draw 拿不到 GPU 表面（WebGL/video 会空白）。截屏时临时给它一块**私有虚拟屏**（`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`，只本 App 可见、用户看不到、**无需任何权限、普通安装可用**），背后接 `ImageReader`、屏上放 `Presentation`，走正常硬件渲染管线读回一帧合成画面，再摘回 WebView。因此 `browser_screenshot` 能截到网页真实画面（含 WebGL/video），且不抢用户屏幕、不弹外部浏览器 App。`browser_open/read/click/type/scroll` 都在内置浏览器里完成；Agent 的 `open` 动作若目标带网址，会自动纠正为内置浏览器打开。

### 9. 虚拟屏执行（需平台签名）

`display/VirtualDisplayHost` 探测是否持有 `ADD_TRUSTED_DISPLAY`：有则 `createVirtualDisplay` 把目标任务 App 启动到独立显示屏，操作不抢用户屏幕；没有（普通签名）自动退回「做完切回」，Guard / 确认流程不变。

### 10. 定时任务

`scheduled_task` 能力 + `ScheduledTaskReceiver`：到点触发一次完整助理回合，也支持任务完成后再触发一次（follow-up）。提醒走 `AlarmManager.setExactAndAllowWhileIdle`（必要时降级 `setAndAllowWhileIdle`），不依赖 App 常驻。

## 工程结构

```
app/src/main/java/com/pilot/agent/
├── assistant/    本地能力路由、快路径执行、LLM 网关、隐私闸门
├── agent/        AgentLoop、Planner 链、Guard、技能库、设置
├── ui/           Compose 界面、确认通道、终端/浏览器入口、状态悬浮条
├── voice/        唤醒词、ASR、TTS、声纹、VAD、音频焦点、模型管理
├── sandbox/      proot 容器、PTY、限流、执行策略、DNS
├── action/       VerifiedLauncher、SystemOps、AppLaunchIndex、提醒/待办/日历
├── query/        QueryEngine + 8 个数据源 + 加密存储
├── tool/         云端工具表、定时任务、文件/设备工具、SSH、审计
├── perception/   无障碍节点树、截图兜底
├── browser/      WebView 引擎、悬浮窗、全屏页
├── music/        网易云 API 客户端、播放器、本地曲库、Tailscale 网关
├── extension/    MCP 客户端、用户脚本
├── runtime/      依赖容器、事件总线、取消语义
├── service/      无障碍服务、前台服务、通知监听、触发鉴权、调试指令
└── display/      虚拟屏宿主
navigation/       高德导航库（独立进程）
docs/             移植与专题文档
rom/              ROM 集成资产（权限白名单等）
models/           端侧模型落地目录（不入 git）
```

### 进程模型

| 进程 | 内容 | 分离原因 |
|---|---|---|
| `:main` | 界面、语音、Agent 主循环、无障碍 | 常驻，不能被沙箱 / 导航带崩 |
| `:sandbox` | proot 终端 | 一条坏命令只杀自己 |
| `:navigation` | 高德 SDK | 导航 SDK 内存大、易崩 |
| `:tailscale` | 内嵌 tsnet（音乐走内网 / Tailnet 时才起） | 用完解绑，不常驻 |

### 技术栈

Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 · JDK 21 · Jetpack Compose · OkHttp · kotlinx.serialization · Media3 ExoPlayer · sherpa-onnx（ASR/KWS）· MNN-LLM（端侧小模型）· jsch（SSH）

## 编译与安装

环境要求：JDK 21、Android SDK（compileSdk 35）。

```bash
# 编译（无 flavor，仅 debug / release）
./gradlew assembleDebug --console=plain

# 单元测试
./gradlew testDebugUnitTest --console=plain
```

> Windows 非控制台 stdin 下 `gradlew.bat` 可能报 `could not get console handle (errno 6)`，此时追加 `< /dev/null`。

安装到真机（EMUI / HarmonyOS 上 `adb install` 会弹审批框，`pm install` 不弹）：

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/pilot.apk
adb shell "pm install -r -t -g /data/local/tmp/pilot.apk"    # -g 直接授予运行时权限
```

首次使用需手动开启的权限：

- **无障碍**：设置 → 无障碍 → Pilot 系统操作（屏幕操作、点击、读取节点树依赖它）
- **通知访问**：读通知需要（不授也能用，只是读不到）
- **悬浮窗**：小窗浏览器 / 状态悬浮条需要
- **麦克风、定位**、电池优化白名单（后台语音）

### Release 包

Release 强制平台签名：需要 `keystore/platform.jks` 与环境变量 `PILOT_STORE_PASSWORD` / `PILOT_KEY_PASSWORD`（别名 `PILOT_KEY_ALIAS`，默认 `platform`）。密钥不入库；缺任何一个密码，`platform` 签名配置就不创建，release 任务在执行期报错而非静默出 unsigned 包——这是刻意设计：用公开 AOSP testkey 签名等于把 signature 权限送给所有人。

平台密钥转换与 ROM 集成步骤见 `rom/README.md`。

### 模型与密钥（均不入库）

| 内容 | 位置 |
|---|---|
| 端侧 ASR（SenseVoice int8，约 239 MB） | `filesDir/models/asr-sensevoice/` |
| 端侧 LLM（优先 Pocket-0.3B 164 MB，回退 Qwen3-0.6B 335 MB） | `filesDir/models/mnn-llm-0.3b/` 或 `mnn-llm/` |
| 声纹（WeSpeaker，约 25 MB） | `filesDir/models/voiceprint/` |
| 高德 Key（使用者自备）/ Brave Key / 云端模型 Key | 设置界面，存本机私有数据 |
| 构建期密钥（`sdk.dir`、签名密码） | `local.properties`（不入库） |

> 高德导航是**可选能力**：公开版本不内置作者的 Key。需要导航/天气/地点搜索时，
> 请在「设置 → 导航与地图」填入你自己在 [lbs.amap.com](https://lbs.amap.com) 申请的
> Android 平台 Key（绑定包名 `com.pilot.agent` 与设置页显示的签名 SHA1）和 Web 服务 Key。
> 未配置时相关能力会明确提示不可用，其它功能不受影响。

模型推送方法见 `HANDOFF.md`；`models/` 在 `.gitignore` 中。

## 已知限制

1. **应用内操作无 API**，只能点击。UI 自动化成功率约 60~70%，界面改版即失效。这是天花板，非实现缺陷。
2. **厂商定制会劫持系统 action**：Intent 发成功，落点不由你决定。
3. **AppFunctions** 需 Android 16（API 36）+ ROM 支持；低版本不可用，代码以反射桥接、可用则用。
4. **虚拟屏**要平台签名 + priv-app 白名单，普通签名恒不可用（自动退回）。
5. **支付类操作**不放手：护栏拦截，风险不对等。
6. **包体约 150 MB**：语音 / 沙箱 / 导航 SDK 都在内。上架须拆动态模块，ROM 预置无此限制。
7. **沙箱**在 proot 下不能跑嵌套虚拟化；DNS 需手工配置。
8. **音乐**依赖自建 `NeteaseCloudMusicApi`（`musicBaseUrl` / `musicFallbackUrl`，WiFi 优先内网、蜂窝优先 Tailnet）；不配服务只能放本地曲库。

## 安全与隐私

- **触发入口锁死**：`RunReceiver` / `TriggerActivity` 在所有构建类型都要求 signature 权限 `com.pilot.agent.permission.TRIGGER`；`TriggerAuth` 统一校验，API < 34 拿不到发送方 UID 时不再按自身 UID 放行。
- **调试指令隔离**：只在 debug 包生效，只能经 `DebugTriggerActivity` 别名（`android.permission.DUMP`）进入，还要求 `no_backup/pilot_debug_commands` 开关文件存在。
- **日志脱敏**：`LogRedactor` 统一处理，需原文时显式开 `log_verbose`。
- **隐私闸门**：锁屏 / 勿扰下不读出个人数据；本地能判定的请求不上云。
- **本地敏感存储**：提醒、待办、记忆、Cookie 走加密存储，不在日志和通知里留明文。

## 文档

| 文件 | 内容 |
|---|---|
| `ARCHITECTURE.md` | 合体架构、五能力域边界、进程 / 内存 / 权限约束、分阶段路线 |
| `HANDOFF.md` | 交接：环境、模型推法、踩过的坑 |
| `PRODUCT_HARDENING.md` | 产品加固清单 |
| `docs/ASSISTANT-FASTPATH-PORT.md` | 助手快路径移植说明 |
| `rom/README.md` | ROM 集成：权限白名单、priv-app、平台签名、虚拟屏验证步骤 |

## 实现注意事项

以下为真机验证中确认的关键约束，修改相关代码时须遵守：

1. **EMUI 丢弃第三方 App 的 logcat**：`Log.i/w` 在 `adb logcat` 中不可见，排查一律读 `filesDir/pilot.log`（`Dbg`）。
2. **`run-as` 通配符拷贝在 HarmonyOS 上静默失败**：目录建出但为空，改由 App 从外部私有目录搬模型。
3. **`OfflineRecognizer(assetManager = null)`**：传 `context.assets` 会触发 native abort，Kotlin `catch` 抓不到，进程静默消失。
4. **SenseVoice 的 ITN 必须关**：「三里屯」会被归一化成「3里屯」。
5. **MiniMax TTS 的 `data.audio` 是十六进制字符串，不是 base64**。
6. **`LlmSession.init()` 第一个参数要 `config.json` 的路径**，不是模型目录。
7. **`onProgress` 返回 `true` 表示中止**，写反则每轮只生成 1 个 token，日志一切正常。
8. **新浪行情不带 `Referer` 直接 403**，返回编码为 GBK。