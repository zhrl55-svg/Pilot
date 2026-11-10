# 交接文档

> 最后更新：2026-11-10 18:15
> 写给明天的自己（或者接手的人）。看完这一份就能接着干。

---

## 怎么接手（新会话先看这段）

**这个工程不需要读代码就能上手。** 按顺序做三件事：

1. 读本文件的「一句话现状」和「明天的开工清单」
2. 跑 `bash scripts/deploy-device.sh MDX0220A21035172`（编译+装+推模型+自检一条龙）
3. 跑「开工清单」第 0 条的 10 条回归

**给新会话的开场白可以直接复制这段**：

```
接手 D:/phone agent 这个项目。先读 HANDOFF.md 和 ARCHITECTURE.md，
然后按 HANDOFF 里「明天（或下次）的开工清单」第 0 条跑一遍回归测试，
把结果告诉我。手机已经连着（MDX0220A21035172），服务器 rl@192.168.2.102 已配免密。
```

**不要做的事**：
- 不要重新设计方案（`ARCHITECTURE.md` 里已经定了五域架构和四阶段路线）
- 不要重写已跑通的模块（语音、播报、直调、查询、音乐都已真机验证）
- 不要跳过回归直接改代码（`HeuristicPlanner` 和 `IntentGate` 是中枢，改一处可能坏一片）

---

## 一句话现状

Pilot 已经是一个**能在真机上跑通语音 agent** 的 App：喊「小派」唤醒 → 说指令 → 端侧识别 →
判断是操作/查询/闲聊 → 执行或回答 → 播报。端侧模型、语音识别、语音合成都跑在本机。

**已具备**：沙箱终端（proot + Alpine，11-10 移植完成，语音可直接跑 shell 命令）。
**还没做的**：导航 SDK、多模块拆分、沙箱独立进程。
**卡住的**：在线音乐（网易云频率风控）。

---

## 环境

| 项 | 值 |
|---|---|
| 工程目录 | `D:\phone agent` |
| 真机 | 华为 P40 Pro，序列号 `MDX0220A21035172`（USB） |
| 系统 | HarmonyOS 4.0 / API 31 / arm64-v8a |
| 包名 | `com.pilot.agent.debug`（debug 包，可 run-as） |
| 音乐服务器 | `rl@192.168.2.102`（Ubuntu 24.04，已配免密公钥） |
| 端侧模型 | `files/models/`（详见下文「模型清单」） |

### 常用命令

```bash
# 编译 + 部署（一条搞定）
cd "D:/phone agent"
bash scripts/deploy-device.sh MDX0220A21035172

# 手动装（华为必须这样才能免审核）
export MSYS_NO_PATHCONV=1
adb -s MDX0220A21035172 install -r -t -g app/build/outputs/apk/debug/app-debug.apk

# 看日志（EMUI 会丢弃 logcat，只能读文件日志）
adb shell "run-as com.pilot.agent.debug cat files/pilot.log"

# 跑一条指令
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --es goal '打开设置'"
```

---

## 今天（11-10）做了什么

### 0. 回归 + 两个护栏 bug 修复（17:25~17:40 这一轮）

10 条回归全部通过，但当场暴露两个护栏误判（都在无头 adb 广播路径上炸出来的）：

**Bug A：Guard 把「声音大一点/打开蓝牙/点击电池/给10086打电话」全判成 MONEY。**
- 根因：`Guard.riskOf` 扫全屏文本的条件写的是 `action.id == null`，把 `systemop`/`shortcut`
  这类**意图完全明确**的直达动作也卷了进去。桌面「支付宝」图标 content-desc 含「支付」
  → 所有直达动作都要确认 → 无头路径 auto-deny → 四条回归连环保留失败。
- 修法：只有 `action.action == "click" && id == null`（真正的盲点击）才扫全屏。
  **直达/系统直调永远不该扫全屏。**

**Bug B：直达华为系统组件抛 SecurityException，规划器连烧 5 步瞎点。**
- 「手机管家 电池」(HwPowerManagerActivity) 声明了 `ACCESS_INTERFACE` 权限，普通 App 起不来。
  `AppLaunchIndex` 建索引时没看 `ActivityInfo.permission`，收录了起不来的组件。
- 修法：① 索引跳过 `permission != null` 的 Activity（2532 → 1897 条，全是起得来的）；
  ② `launchComponent` 失败时降级打开该 App 主入口。
  修完「点击电池」直达成功。

**遗留（已知、未修）：后台 Activity 拦截。**
无头广播时 launcher 在前台，`Application context.startActivity` 被 HarmonyOS 拦
（logcat 里 `activity start fail: Background activity start`，但 **startActivity 不抛异常**，
`runCatching{}.isSuccess` 误判成功）。实测「给10086打电话」App 前台时真能拨出（incallui 起来），
后台时静默失败。语音主路径 App 都在前台，暂不影响验收；后续要么查无障碍 context 起 Activity，
要么起完后回查 focus 校验。

### 1. 端侧语音链路（模拟器 → 真机）

- `voice/AudioCapture.kt` AudioRecord 16k/mono/20ms 帧
- `voice/EnergyVad.kt` 能量 VAD，800ms 断句，300ms 预卷
- `voice/LocalAsr.kt` sherpa + SenseVoice，真机加载 2.1s、识别 166~493ms

### 2. 唤醒词 + 声纹

- `voice/WakeWordDetector.kt` sherpa KWS，模型 4.6MB 打进 assets，加载 217ms
- `voice/KwsKeywords.kt` ICU 转带声调拼音，「小派」→ `x iǎo p ài @小派`
- `voice/VoiceprintGate.kt` WeSpeaker 25MB，余弦相似度门禁
- **策略**：乐观放行 + 事后否决（唤醒即听，后台验声纹，验不过丢弃结果）

### 3. 端侧模型换成 0.3B

- 从 `taobao-mnn/MNN-Pocket-0.3B-MNN` 下载（HF 直连不通，用 `hf-mirror.com`）
- 权重 164MB（0.6B 是 335MB），加载 610ms、**首字 200ms**（0.6B 是 477~619ms）
- `modelDir()` 优先用 `mnn-llm-0.3b`，0.6B 留作备选
- **0.3B 没有思考模式**，不需要 `/no_think`；Qwen3 必须加

### 4. 播报（TTS）

- `voice/SystemTts.kt` 系统 TTS（零延迟）、`voice/MiniMaxTts.kt` 云端（音色好，1s）
- `voice/Speaker.kt` 路由 + **云端失败自动降级系统**
- 默认走 MiniMax

### 5. 执行力（系统直调）

- `action/SystemOps.kt` 音量/亮度/电话/闹钟，不碰界面
- `action/Contacts.kt` 姓名 → 号码
- `parseDelta()` 自然语言 → 百分比（「大一点」= +15%）

### 6. 直达（能启动的都不点）

- `action/ActionCatalog.kt` 系统设置 18 条 + 系统动作 11 条 + App deep link 9 条
- `action/AppLaunchIndex.kt` **运行时枚举 2532 条 exported Activity**，建索引 2.7s
- 三层优先级：静态表 → App 索引 → 点击兜底

### 7. 查询类（QueryEngine）

- `query/QueryTool.kt` + `QueryEngine.kt` + `StockTool.kt`（A 股行情，233ms）
- `IntentGate` 改成**三分类** `TASK / QUERY / CHAT`

### 8. 在线音乐（已打通）

- 服务器部署 `NeteaseCloudMusicApi`（`buntu:3001`，systemd）
- 登录成功（验证码方式，VIP 11），cookie 已写入 App
- 实测「播放晴天」→ 506ms 拿到播放地址 → MediaPlayer 拉流 → **出声**

**★ 排查记录：一开始误判成「IP 频率风控」，实际是三个代码 bug 叠加**。
写在这里是因为这个坑很容易再踩一次：

| Bug | 表现 | 修法 |
|---|---|---|
| 用 `/song/url/v1` | 恒定 `-460 网络环境存在风险` | 换旧接口 `/song/url` |
| cookie 塞 URL 参数（2022 字符） | 请求行过长被截断，服务端当未登录 | 改 `Cookie` header |
| 只试搜索第一个结果 | 首选常是翻唱版，`url: null` | 遍历前 5 个候选 |
| 播放 CDN 是 http 明文 | `MediaPlayer what=1 extra=-2147483648`（MEDIA_ERROR_UNKNOWN） | `network_security_config` 放行 `*.music.126.net` |

**第四条最难查**：播放地址是 `http://m701.music.126.net/...`，MediaPlayer 拉流同样受
network security policy 约束。被拦时报的是 `MEDIA_ERROR_UNKNOWN`，日志里**完全看不出是策略拦截**，
只有对照 `network_security_config.xml` 才能发现。

**教训**：不能只测一条路径就下结论。要同时对比「新接口 vs 旧接口」「header vs query」
「第一首 vs 遍历」。三个变量同时出问题时，现象看起来就像整条链路被风控。

### 9. 设置界面重构

- 五个分组卡片：语音唤醒 / 播报 / 云端模型 / 音乐 / 维护
- 三个可复用组件：`SettingsSection(title, hint)` / `OptionChips` / `SmallField`
- 输入框改「标题在上」省掉 Material3 浮动 label 的固定高度（56 → 46dp）
- `CompactSwitch` 缩放 0.7，解决开关和 12sp 文字比例失衡
- 说明文字收进 `ⓘ`，点开弹窗
- **API Key 默认掩码**（今天截图里它明文暴露过一次）

加一个新分组的成本 = 四行代码：
```kotlin
SettingsSection("导航", hint = "路线偏好会影响算路结果") {
    OptionChips(listOf("fastest" to "最快", "nofast" to "避开收费"), mode) { mode = it }
    SmallField(city, { city = it }, "常驻城市")
}
```

### 10. 沙箱移植 ✅（18:00~18:10 这一轮完成）

minis 的 `sandbox/` 24 个内核文件（7338 行）全部搬进 `com.pilot.agent.sandbox`，
**`offload/` 30+ handler 一个没搬**——注册点都在 MinisApp，内核不直接 import 它们，
桩掉 `NativeOffloadServer` 依赖面即可（未注册的 handler 走 HandlerStub 报 69）。

- 桩层在 `sandbox/shim/`：AppLogger→Dbg、MountedFoldersStore（SharedPreferences 简化版，
  Entry 字段与原版一致所以内核零改动）、FileMentionIndex 只留 MountEntry、
  EnvVarRepository 空实现、network 代理返回空（guest 直接走 Android 网络栈）
- **AGP 会在 mergeDebugAssets 自动把 `.tar.gz` 解成 `.tar`** 打进 APK（源码里的 .gz 反而进不去）；
  RootfsManager 本身双名兜底，不用管。`androidResources.noCompress += ["tar","tar.gz","proot-aarch64"]`
- **`jniLibs.useLegacyPackaging` 必须改 true**：false 时 AGP 无视 manifest 的
  `extractNativeLibs=true`，so 留在 APK 内不解包 → nativeLibraryDir 里没有可执行的 libproot.so（W^X）
- 接入：`SandboxTerminal`（门面）→ `systemop target=terminal`（HeuristicPlanner.ruleTerminal：
  「在终端里跑X」或裸命令白名单）→ AgentLoop.runSystemOp。裸命令（`df -h`）没有中文动词，
  IntentGate 单独加了 `looksLikeRawShellCommand` 判 TASK（坑 #13 变体）
- RunReceiver 新增 `--es sandbox_test '<命令>'` 自检通道
- 实测：uname/echo 冷启动 146ms（rootfs 早已解包好，仅 boot）；`cat /etc/alpine-release`→3.21.3；
  `df -h` exit=1 但有输出（华为挂载点 Permission denied）→ 语义改成“有输出即可用”；
  `ping 223.5.5.5` 193ms 通（guest 直接出网）
- 原 10 条回归合入后重跑：**全部通过**
- APK 现在 ~89MB（比之前 151MB 的口径小，模型本来就不在包里）

### 11. 沙箱 v2 + 后台启动修复（19:00~19:25）

**沙箱 v2**：`:sandbox` 独立进程（AIDL `ISandboxService`/`ResultCallback`/
`IHostBridge`，结果异步回推，不在 binder 同步事务里等长命令）；
offload 最小集接回（`android-device`/`android-open`，容器 CLI 调宿主，
实测 `android-device` 返回宿主机型/电量）；SAF 挂载目录 UI；
`apk add jq` 实测 13.5s 装成功（容器联网+写盘全通）。

**后台启动（坑 #18 彻底修）**：startActivity 后回查前台窗口，不再假成功。
四条路逐一验证：直接起✗ / 透明代理 Activity✗（代理自己也起不来）/
前台服务✗（状态对了但 HarmonyOS 要 privileged 权限）/
AlarmManager✗（排到 +5s 且代理仍被 Abort）。
**唯一在用户机上真能用的路：桌面图标无障碍点击**（翻屏找图标点，0.6s 生效）。
自研 ROM 有 `START_ACTIVITIES_FROM_BACKGROUND` 后可走前台服务，更快。

**踩的坑**：BroadcastReceiver 的 context 调 bindService 抛
`ReceiverCallNotAllowedException`，必须用 Application context。

### 12. 音乐三连坑（19:25~19:45 老板真机反馈，已修）

1. **「播放一会自动停了」**——MiniMax 播报和音乐播放器抢同一个流，
   华为音频策略把音乐停掉。修法：`AudioFocusManager`（音乐 GAIN 焦点，
   播报 TRANSIENT_MAY_DUCK）。实测持续播放 25s+ 不再断。
2. **「播放中冒出一句『你说完了』」**——外放歌词被麦克风录进去，
   ASR 误听成用户指令还播报出来。修法：`AudioGate`（放音期间不送 ASR，
   音乐/TTS 各持一个令牌，引用计数）。
3. **「播报期间要停音乐或降音量」**——两种策略可切换（设置→播报）：
   降低音量（默认，降到 15%，播完恢复）/ 停止音乐。

**关键坑**：降音量只能动 MediaPlayer 自己的 `setVolume`，**不能动系统
STREAM_MUSIC**——动系统流会覆盖用户媒体音量，恢复时还容易永久静音
（先静音再读音量，读到的是已经静音后的 0，恢复成 0，歌彻底哑了）。

**顺序坑**：指令播报（「正在播放晴天」）发生在音乐开始**之前**，
STOP 策略要记 `justSpoke` 窗口兜住，否则停了个寂寞。

### 13. 架构规划

- `ARCHITECTURE.md` 三个 App 合体的完整规划（五域架构、包体积、内存、进程模型、四阶段路线）

---

## 模型清单（都在手机 `files/models/`）

```
asr-sensevoice/     model.int8.onnx 239MB + tokens.txt      端侧识别
mnn-llm-0.3b/       llm.mnn.weight 164MB + 其余 4 个          端侧对话（首选）
mnn-llm/            llm.mnn.weight 335MB                     Qwen3-0.6B（备选）
voiceprint/         cnceleb_resnet34_LM.onnx 25MB            声纹
```

**模型不进 APK**，走 `ModelStore`：外部私有目录 → App 启动时自己搬进私有目录。
推模型：

```bash
adb push <本地文件> /sdcard/Android/data/com.pilot.agent.debug/files/models/<子目录>/
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --ez import_models true"
```

本地有备份：`D:\phone agent\models\mnn-llm-0.3b\`（0.3B）、`models\voiceprint\`（声纹）。

---

## 踩过的坑（别重复踩）

### 华为 / 真机

1. **EMUI 丢弃第三方 logcat**：`Log.i` 按 pid 抓也拿不到。必须用 `Dbg`（写 `files/pilot.log`）。
2. **`run-as` 通配符拷贝静默失败**：HarmonyOS 上目录建了但里面是空的。改让 App 自己搬（`ModelStore`）。
3. **安装要关「监控 ADB 安装应用」**：开发者选项里关掉，否则装新包要输密码。
4. **`force-stop` 会把无障碍踢掉**：测试脚本里别用它，用 `am start` 重启 Activity。

### sherpa / MNN

5. **`OfflineRecognizer(assetManager = null)`**：传 `context.assets` 会 **native abort**，Kotlin catch 不到，进程静默消失。
6. **ITN 必须关**：否则「三里屯」→「3里屯」。
7. **`LlmSession.init()` 首参是 `config.json` 文件路径**，不是目录。
8. **`onProgress` 返回 `true` 表示中止**，写反只出 1 个 token。
9. **KWS 三个 loader 缺一不可**（做沙箱时同理）。

### 网络

10. **Git Bash 调 adb 必须 `export MSYS_NO_PATHCONV=1`**，否则 `/data/local/tmp` 被改写成 Windows 路径。
11. **明文 HTTP 要精确放行**：`res/xml/network_security_config.xml` 只放行内网 IP，别全局 `usesCleartextTraffic`。
12. **HF 直连不通**，用 `hf-mirror.com`。

### 架构

13. **每加一类执行能力，必须同步扩 `IntentGate.TASK_VERBS`**。已经被这个坑过两次：
    「声音大一点」和「播放晴天」都被判成闲聊。**这是最容易忘的一步。**
14. **规则层要认边界**：`ruleOpen` 的正则会从长句里抠动词，「打开设置然后点电池」被整句当应用名。
    现在靠 `isMultiStep()` 先拦（有连接词或 ≥2 动词就返回 unable）。
15. **直达失败不能重试**：会连发同一个 Intent 到死循环。
16. **Guard 扫全屏文本只限盲点击**：判断条件必须是 `action=="click" && id==null`。
    只写 `id==null` 会把 systemop/shortcut 卷进来，桌面一个「支付宝」图标就能把
    「声音大一点」判成资金变动（11-10 回归实测踩到，四条连环保住）。
17. **AppLaunchIndex 不能收录带 android:permission 的组件**：普通 App 起不来，
    直达抛 SecurityException 后规划器连烧 5 步瞎点。收录前查 `ActivityInfo.permission`，
    起不来再降级开主入口。
18. **HarmonyOS 后台起 Activity 是静默失败**：`startActivity` 不抛异常（logcat 里才有
    `activity start fail: Background activity start`），`runCatching{}.isSuccess` 靠不住。
    无头广播路径拨号/闹钟这类要拉界面的操作会假成功，App 前台时正常。
19. **沙箱 W^X 两件套**：`manifest extractNativeLibs="true"` 必须配
    `jniLibs.useLegacyPackaging = true`，否则 AGP 无视 manifest，so 不解包，
    nativeLibraryDir 里没有可执行的 libproot.so。Android 10+ 唯一可 exec 的位置
    就是 nativeLibraryDir（proot 伪装成 .so 放 jniLibs 的原因）。
20. **AGP mergeDebugAssets 自动把 `.tar.gz` 解成 `.tar`**：源码里放 .gz，APK 里只有 .tar
    （RootfsManager 双名兜底所以没事，但别在 APK 里找 .gz 找不到就慌）。
    rootfs/proot 类资源记得加 `androidResources.noCompress`，`assets.openFd` 依赖不解压。

---

## 明天的开工清单

### 0. 先跑回归（10 分钟，别省）—— ✅ 11-10 17:40 已全部跑通（含两处护栏修复，见「今天做了什么」§0）

今天的改动动了 `HeuristicPlanner` 和 `IntentGate`，可能弄坏老功能。先跑这 10 条：

```
打开设置 / 回桌面 / 返回 / 点击电池 / 打开蓝牙
声音大一点 / 给10086打电话 / 查上证指数 / 你是谁 / 播放晴天
```

**验收**：10 条全部要有正确结果。第 10 条应该真的出声（网易云已登录、API 已部署）。

### 1. 音乐：已打通，只需确认

「播放晴天」已经能出声。下次只需验证这三条没被改坏：

- 搜索返回 5 首（不是 3 首）
- 取地址走 `/song/url`（旧接口），不是 `/v1`
- 播放地址是 http 时 MediaPlayer 不报 `what=1`

如果又听不到，按这个顺序查：**接口 → 播放地址是否为 null → 明文策略**。
（详见上面「排查记录」的四条 bug）

### 2. 沙箱移植 ✅ 已完成（11-10，详见「今天做了什么」§10）

遗留到 v2 的：`:sandbox` 独立进程（先观察主进程内存水位）、SAF 挂载 UI、
offload handler 按需接回（Pilot 已有等价能力，只缺「容器内 CLI 调宿主」这条通路）、
容器内软件包管理（apk）。

### 3. 高德导航 SDK（2 周）

从 nav-agent 搬（`D:\workbuddy\2026-10-31-21-31-07\nav-agent\android`），已有算路/播报接管/HUD。
重点是把它塞进 `:navigation` 独立进程，播报并入统一的 TTS 队列（按优先级抢占）。

### 4. 补两个小口子

- 唤醒提示音（`ToneGenerator`，一行）
- 声纹注册引导（现在要手动点，且需要用户先说一句才有音频）

---

## 关键文件索引

```
agent/AgentLoop.kt          主循环（意图分流、planner 链、执行 dispatch）
agent/HeuristicPlanner.kt   规则层（顺序：系统直调 → 直达 → App 入口 → 应用启动 → 按键 → 点击）
agent/IntentGate.kt         意图三分类（改这个记得同步 TASK_VERBS）
agent/PlannerRouter.kt      planner 链（规则 → 端侧 → 云端）
action/SystemOps.kt         音量/亮度/电话
action/ActionCatalog.kt     系统 action 静态表
action/AppLaunchIndex.kt    2532 条 App 入口索引
query/QueryEngine.kt        查询调度
voice/*                     语音全链路
music/*                     在线音乐
sandbox/SandboxTerminal.kt  沙箱门面（agent 唯一入口）
sandbox/*                   proot 内核（minis 移植，7338 行）
sandbox/shim/*              minis 依赖桩（AppLogger/MountedFoldersStore/network）
```

---

## 已知风险

1. **没有自动化测试**。所有验证都是手跑 adb 广播，改一处可能坏另一处。
2. **`HeuristicPlanner` 规则顺序敏感**。系统直调必须在直达之前，直达必须在应用启动之前，否则互相抢。
3. **国产 ROM 适配是无穷的**。P40 上能跑不代表其他机器能跑，`-460` 这类问题没法一次解决。
4. **包体积已经 151MB**（含 sherpa 48MB）。加高德 187MB 后必须拆按需模块或走 ROM 预置。
