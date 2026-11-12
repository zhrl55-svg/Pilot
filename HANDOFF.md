# 交接文档

> 最后更新：2026-11-11 21:45
> 写给明天的自己（或者接手的人）。看完这一份就能接着干。
>
> **今天改动很大**：语音交互整块重做了一遍（声纹/输入栏/长按选字/音乐条/音色/免打扰）。
> 老章节里描述的交互**部分已过时**，以「11-11 傍晚」那章为准。

---

## 怎么接手（新会话先看这段）

**这个工程不需要读代码就能上手。** 按顺序做三件事：

1. 读本文件的「一句话现状」和「明天的开工清单」
2. 跑 `bash scripts/deploy-device.sh 192.168.2.32:5555`（编译+装+推模型+自检一条龙）
3. 跑「回归清单（11-11 重做后的版本）」的 12 条

**给新会话的开场白可以直接复制这段**：

```
接手 D:/phone agent 这个项目。先读 HANDOFF.md 和 ARCHITECTURE.md，
然后按 HANDOFF 里「明天（或下次）的开工清单」第 0 条跑一遍回归测试，
把结果告诉我。手机已经连着（192.168.2.32:5555），服务器 rl@192.168.2.102 已配免密。
```

**不要做的事**：
- 不要重新设计方案（`ARCHITECTURE.md` 里已经定了五域架构和四阶段路线）
- 不要重写已跑通的模块（语音、播报、直调、查询、音乐都已真机验证）
- 不要跳过回归直接改代码（`HeuristicPlanner` 和 `IntentGate` 是中枢，改一处可能坏一片）

---

## 一句话现状

Pilot 已经是一个**能在真机上跑通语音 agent** 的 App：喊「小派」唤醒 → 说指令 → 端侧识别 →
判断是操作/查询/闲聊 → 执行或回答 → 播报。端侧模型、语音识别、语音合成都跑在本机。

**已具备**：
- 沙箱终端（proot + Alpine，语音直接跑 shell 命令；SAF 热挂载 + 空闲回收都通了）
- **导航全链路**（高德 SDK 官方导航界面 + 语音选候选 + 播报接管，11-10 深夜打通）
- **完整的语音交互**（11-11 重做，见「11-11 傍晚」章）：
  声纹首句自动注册 / 罩层式输入栏 / 长按选文字 / 音乐播放条 / 双引擎音色+预听 / 免打扰

**还没做的**：多模块拆分落地（方案已定，见 NAV_MODULE_PLAN.md）、ROM 预置。
**卡住的**：
- 在线音乐（网易云频率风控 + 试听 URL 限时，约 1 分钟就断流；登录后能好）
- 沙箱挂载目录的 proot bind 参数联动（设置里能挂上，热壳重启那步还没通）

---

## 环境

| 项 | 值 |
|---|---|
| 工程目录 | `D:\phone agent` |
| 真机 | 华为 P40 Pro，序列号 `192.168.2.32:5555`（USB） |
| 系统 | HarmonyOS 4.0 / API 31 / arm64-v8a |
| 包名 | `com.pilot.agent.debug`（debug 包，可 run-as） |
| 音乐服务器 | `rl@192.168.2.102`（Ubuntu 24.04，已配免密公钥） |
| 端侧模型 | `files/models/`（详见下文「模型清单」） |

### 常用命令

```bash
# 编译 + 部署（一条搞定）
cd "D:/phone agent"
bash scripts/deploy-device.sh 192.168.2.32:5555

# 手动装（华为必须这样才能免审核）
export MSYS_NO_PATHCONV=1
adb -s 192.168.2.32:5555 install -r -t -g app/build/outputs/apk/debug/app-debug.apk

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
- `parseLevelChange()` 自然语言 → 相对增减/设定值（「增大音量」= +20%，「调到60%」= 设成 60%）

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

## 11-10 傍晚到深夜做了什么（沙箱收尾 + 导航全链路）

> 这一段是 18:15 之后的工作，按时间顺序。每个「坑」都写了现象和解法，
> 遇到类似问题时先在这里搜关键词。

### 一、沙箱收尾件

**SAF 热挂载**
- 现象：加了挂载目录，`ls /var/minis/mounts/` 还是空的
- 根因：proot 的 `-b` 参数在**进程 spawn 时**就冻结了，改快照对活着的热壳无效
- 解法：挂载变化时杀热壳，下一条命令重建（照搬 minis 原版 T219-5）
- 附带坑：挂载表原来存 SharedPreferences，但 SP 的跨进程缓存靠
  `MODE_MULTI_PROCESS`，那个 flag API23 起被废弃 → `:sandbox` 永远读旧副本。
  改存文件（`files/sandbox/mounts.json`，原子写 + mtime 判重）
- 实测：热挂载 287ms 生效，guest 写 `/var/minis/mounts/Download/x.txt`
  宿主 `/sdcard/Download/x.txt` 真出现

**`:sandbox` 空闲回收**
- 现象：回收后进程又活了
- 根因：**沙箱自杀后主进程还持着 binder**，系统按 `BIND_AUTO_CREATE`
  立刻把服务拉回来，回收白做
- 解法：回收前先通知主进程 `unbindService`（用已有的 IHostBridge 反向通道）
- 实测：回收后进程真消失，下条命令自动重启（145ms），挂载表还在

### 二、导航全链路（重点）

**架构**（详见 `navigation/ARCHITECTURE.md`）
```
主进程                              :navigation 进程
IntentGate → HeuristicPlanner       NavigationService
   ↓                                  ├─ LocationProvider（双通道定位）
NavigationClient ──AIDL──→           ├─ WebPoiSearch / SdkPoiSearch（POI）
   ↑                                  ├─ WebRoutePlanner / AmapNaviEngine（算路）
   └── NavCallback ────────────────── ├─ RouteGuide（自研引导）
        ↓                             └─ NavActivity（高德官方界面 + 我们的按钮）
Speaker.speakNav（不可打断）
```

**高德接入踩的 3 个错误码**（反射 `PathPlanningErrCode` 打出来的对照表）

| 码 | 含义 | 真因 | 解法 |
|---|---|---|---|
| 3 | ERROR_STARTPOINT | `List<NaviLatLng>` 重载不纠偏，起点落在不可通行区域 | 改 **String 重载**（"经度,纬度"），服务端吸附到道路 |
| 22 | INVALID_USER_SCODE | **安全码格式**：高德控制台要求**带冒号**的 SHA1 | 填 `26:F6:15:CD:A0:6C:...` |
| 6 | ERROR_ENDPOINT | POI 坐标是**停车场中心/建筑内部**，不在道路上 | 用 `NaviPoi(poiId, LatLng, name)` 传 **POI ID** |

**Key 管理**
- `local.properties`（不入库）：`AMAP_KEY_PILOT`（SDK Key，绑 com.pilot.agent）、
  `AMAP_WEB_KEY`（Web Key，地点搜索/算路/逆地理编码）
- ★ 坑：`local.properties` 的键**不会**自动变成 gradle project property
  （那是 Android Studio 的行为），命令行构建拿到空串。
  必须自己读 `Properties` 文件——`aapt2 dump` 看到 `value=""` 才发现

**地图相关**
- 地图停在北京市：两个原因叠加 —— ① `startService` 是**异步**的；
  ② 定位器是**懒创建**的，没人调 `startNavi` 就永远不创建。
  修：`peekLocationProvider()` 改成「拿不到就创建并 start」+ 轮询等 10 秒
- 日夜自适应：**不要用 SDK 的 auto**（定位没就绪时判成白天后不纠正）。
  自己按定位经纬度算日出日落（`NavDayNight`），再
  `setMapStyle(MapStyle.NIGHT/DAY, "")` 切**底图** + `isNaviNight` 切引导层；
  每分钟检查一次，开始导航/回到前台时再强制套一次。
- 缩放：初始页 12f（14f 只能看到当前一条路，老板要求能看见周边路网；
  导航开始后仍由 SDK 自动跟车缩放）

**高德界面按钮的真面目**
- 「退出」「设置」不是自己弹菜单，而是**回调给宿主**：
  `onNaviCancel()` / `onNaviSetting()`。不实现就点了没反应
- 点「退出」后高德会**自己 finish 掉 Activity**（日志：onNaviCancel → 1ms 后
  onAMapNaviViewExit → onDestroy），拦不住。想保留地图只能 onDestroy 里重建
- 点「设置」后高德会先切到浏览模式（底部变「退出/继续导航/设置」），
  我们立刻弹的 Dialog 会被顶掉 → **延迟 350ms 弹**

### 三、导航界面 UI（老板多轮迭代后的最终形态）

```
┌────────────────────────────────┐
│ [高德转向提示]         卫星弱   │
│ ┌────────────────────────┐     │
│ │ 搜地点                  │     │ ← 灰色搜索框（右侧留 96dp）
│ └────────────────────────┘     │
│  ① 张掖西站停车场 · 320米       │ ← 候选列表
│  ② 张掖汽车站 · 1.2公里         │
│                          🎤    │ ← 语音（状态图标：静态/呼吸/跳动）
│                          🕘    │ ← 历史
│                          🏠    │ ← 主页
│                          ✕     │ ← 收起 App（退后台，导航继续）
│ 退出  3.2公里 11分钟    设置    │ ← 高德底栏
└────────────────────────────────┘
```

- 按钮统一圆形（`#B39E9E9E` 底 + 24dp 圆角 + 48dp）：
  左侧竖排 = 出行方式（🚗/🚶 点击切换）+ 语音；右侧竖排 = 路线 / 历史 / 回桌面；
  圆形 ✕ 退出钮已删除（退出交给高德底栏「退出」）
- 搜索框只在没有路线时显示；开始导航后自动收起，点语音按钮可临时叫出
- 导航中搜索框收起后，转向提示下方常驻「目的地 · XX」（读 currentDestination）
- 搜索框（和收起后的「目的地」标签）底色与按钮统一：`#B39E9E9E`
- 出行方式：`TravelMode`（0=驾车 1=步行）存 `nav_settings.json`；
  AIDL 新增 `startNaviWithMode(dest, strategy, mode)`；
  步行走 SDK `calculateWalkRoute`（NaviPoi + TravelStrategy.SINGLE），
  Web 降级改用 `/v3/direction/walking`（步行无 strategy 参数）；
  导航中切换模式会按新方式重新算路
- 语音卡在「正在聆听」：`VoiceInput` 加 20 秒聆听看门狗（无断句就回待命），
  所有收尾路径都写回 `VoiceState.IDLE`；点导航麦克风会先停 TTS，避免放音闸门吃掉人声
- 导航面板只吃「点导航麦克风」发起的识别（`nav_voice_arm.txt`，30 秒有效）；
  唤醒词说的「讲个故事」不会再被当成地名搜索。导航播报中的普通回答改为排队补播，不丢弃
- 语音按钮动态改为外框彩色转圈（`VoiceRingDrawable`）：录音蓝紫、播报橙粉
- 左下角「退出」分两种：**导航中**=结束导航、保留地图（确认框）；
  **没在导航时**=关掉高德界面 + `AMapNavi.destroy()` 释放 SDK（下次导航自动重建）
- 候选列表只念最新一批：搜索请求带版本号，旧回包丢弃；播报延迟 1.2 秒，
  输入变化会取消待播，避免同一批地点被念两遍
- 语音说「导航去张掖西站」只把「张掖西站」填进搜索框（剥掉开头动词短语）
- 候选列表/输入文字 20 秒没人选自动清空，并停掉还在念的候选播报
- 播报候选时不念「第 N 个」（否则常驻 KWS 会把播报自己当成选序号）。
  说「第二个」由 KWS 打断播报，导航面板轮询到序号直接选第 2 条
- 设置面板用 **Compose**（Material3 Switch，和主界面同款），
  配色抄主界面 PilotTheme（`NavColors` / `NavTheme`）
- 播报三档：详细 / 标准 / 简洁
- 常去地点：输入地址搜 / 用当前位置（**反查真实地址，不显示经纬度**）
- 导航历史：算路**成功后**才记，可删可改别名

### 四、这一轮新增的坑（都验证过）

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | Gradle 构建失败被 grep 吞了 | 我 `grep ^e:` 过滤，而 `FAILURE: Build failed` 不带 `e:` 前缀 → 连装几次旧 APK，以为代码没生效 | **看完整构建输出**，别只 grep 编译错误行 |
| 2 | Compose Compiler 插件 | `Starting in Kotlin 2.0, the Compose Compiler Gradle plugin is required` | 模块的 plugins 里要用 `alias(libs.plugins.kotlin.compose)`，`id(...)` 不带版本不生效 |
| 3 | ComposeView 挂 Dialog 崩 | `ViewTreeLifecycleOwner not found from ComposeView` | Dialog 的 window 不在 Activity 的 View 树上 → 改成 `addView` 到根容器 |
| 4 | lambda 里的 this | 在 `ComposeView{ setContent { ... } }` 里 `this` 是 ComposeView | 要写 `this@NavActivity` |
| 5 | 华为 ROM 广播队列卡死 | `am broadcast` 的 `dispatchClockTime=1970-01-01`，广播永远不派发 | 用 `am start -n ...TriggerActivity` 绕开 |
| 6 | 国产 ROM 丢 logcat | 日志完全看不到 | 写文件（`Dbg` / `NLog`），用 `run-as` 读 |
| 7 | 改包名丢模型 | 包名从 `.debug` 改掉后卸载旧包，554MB 模型全删 | `deploy-device.sh` 的 `PKG` 要同步改；改包名前先备份 |
| 8 | `run-as` 访问不了外部目录 | `cat /sdcard/Android/data/...` Permission denied | Android 11+ 限制，只能让 App 自己读 |
| 9 | 截图坐标 ≠ 点击坐标 | 按截图比例点，落到别处（误触 SAF 文件夹选择） | 截图是缩放显示的，实际屏幕 `wm size` 看 |

## 模型清单（都在手机 `files/models/`）

```
asr-sensevoice/     model.int8.onnx 239MB + tokens.txt      端侧识别
mnn-llm-0.3b/       llm.mnn.weight 164MB + 其余 4 个          端侧对话（首选）
mnn-llm/            llm.mnn.weight 335MB                     Qwen3-0.6B（备选）
voiceprint/         campplus_sv_zh_16k.onnx 27MB             声纹（优先，带 metadata）
                    cnceleb_resnet34_LM.onnx 26MB            备用（缺 metadata，sherpa 下会崩）
```

**模型不进 APK**，走 `ModelStore`：外部私有目录 → App 启动时自己搬进私有目录。
推模型：

```bash
adb push <本地文件> /sdcard/Android/data/com.pilot.agent.debug/files/models/<子目录>/
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --ez import_models true"
```

本地有备份：`D:\phone agent\models\mnn-llm-0.3b\`（0.3B）、`models\voiceprint\`（声纹）。

---

### ★ 声纹模型必须带 metadata（踩了 5 次崩溃）

`cnceleb_resnet34_LM.onnx` 是原始 WeSpeaker 模型，**没有 sherpa 需要的 metadata**。
`sherpa-onnx` 构造 `SpeakerEmbeddingExtractor` 时会**静默失败返回空指针**，
下一次 `createStream()` 直接 `SIGSEGV`（进程消失，Kotlin 完全接不住）。

sherpa 的报错（`logcat` 能抓到，EMUI 只丢第三方 `Log.i`，native stderr 还在）：

```
sherpa-onnx: No model_type in the metadata!
sherpa-onnx: Unsupported model_type: pytorch      ← 它读的其实是 framework 字段
```

**解法**：换成 sherpa 官方打包的 3D-Speaker 模型
`3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`（27MB，192 维，
metadata 里带 `framework=3d-speaker`）。放到 `files/models/voiceprint/campplus_sv_zh_16k.onnx`，
代码按 `MODEL_CANDIDATES` 优先找它。

自测（不用真说话）：`--es voice_test enroll` + 三次 `--es speak_text '...'`，
日志出现 `声纹模板已保存 dim=192` 即成功。

## 输入栏结构（11-11 重写，别改回去）

```
Box
 ├─ OutlinedTextField        永远不自己处理触摸
 ├─ 流光边框 / "正在听…" 层   仅录音时
 └─ 透明手势罩层             仅「未进入文本输入态」时存在，所有点击先到这
```

**为什么必须罩层**：之前把手势放在父 Box 上，和 `OutlinedTextField` 自己的
触摸处理抢同一个 down/up。结果一连串互相打架的 bug：单击被判成长按、
长按弹键盘、输入框一直抢焦点。前后加了 6 层补丁
（`clearFocus` / `keyboard.hide` / `readOnly` / `focusProperties` /
`showKeyboardOnFocus=false` / `handler.postDelayed`）都没根治，全删了。

3. **录音时罩层不能撤**：撤掉后 `pointerInput` 协程会被取消，
   松手事件和 `onFinishVoice()` 都执行不到 → UI 收了边框，
   但语音层永远卡在 `LISTENING`（"松开后一直在转"）。
   `onFinishVoice()` 必须放在 `finally` 里。
4. **`vad.flush()` 在没说话时什么都不做**（`if (!inSpeech) return`）。
   所以 `finishListening()` 要自己加 250ms 兜底，主动 `backToIdleOrWake()`；
   有语音时由 `recognize()` 的 finally 回收，两者用 `finishGuard` 防重复。

**两条硬规则**：

1. `readOnly` / `disabled` 会让 TextField **停止消费指针事件**，
   长按手势就断了 —— 要禁焦点只能用罩层或 `focusProperties(canFocus=false)`。
2. **按住不动时不会有新指针事件**。长按判定必须用
   `withTimeoutOrNull(450ms) { 等 up }` 靠超时主动唤醒；
   `while(awaitPointerEvent) { 比较时间戳 }` 永远不会触发（实测踩过）。

状态：`textMode=true`（已聚焦）→ 罩层撤掉，TextField 正常输入；
失焦 → 罩层回来。键盘只在单击后由 `LaunchedEffect(textMode)` 主动
`requestFocus + keyboard.show()`，长按从头到尾不聚焦 → 不会弹键盘。

配套：`AndroidManifest` 的 MainActivity 加 `windowSoftInputMode="adjustResize"`，
否则键盘弹出会 `adjustPan` 把标题顶出屏幕。

## 语音交互（11-11 重做）

### 状态机
```
启动 App → 自动开麦（KWS 常驻）
  ├ 唤醒词命中 → LISTENING（说指令）
  │     └ 说完 → ACTIVE（可设置秒数，默认 60s：不用再喊唤醒词，直接说话）
  │            └ 超时 → ASLEEP（只跑 KWS，仍可唤醒）
  └ 播报中命中「唤醒词 / 打断词」+ 声纹通过 → 立即停播 → 双短音 bi-bi → LISTENING
```

- 开关在「设置 → 语音唤醒」：**对话结束后多久休眠**（0=不休眠）、**语音打断**、**打断词**。
- 打断词和唤醒词**一起喂给同一个 KWS**（一次加载，词表见日志 `KWS 就绪 words=[...]`），
  不额外开识别链路。
- 播报中声纹阈值**自动放宽一半**：麦克风录到的是"喇叭声+人声"混合，
  按原阈值会永远打断不了；没注册模板则直接放行。
- 打断提示音是**双短音**（bi-bi），唤醒是单短音，听感上能区分。

### 自检（不用说话就能验）
```bash
# 模拟唤醒 / 对话结束 / 看状态 / 发打断词（播报中用）
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test wake"
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test end"
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test status"
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es speak_text '一段长播报…'"
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test 停一下"
```


---

## 回归清单（11-11 重做后的版本，照这个跑）

> 旧的 10 条回归是 11-10 的，交互变了。**先跑这 12 条**。

```bash
D=192.168.2.32:5555
A="adb -s $D"
L() { $A shell "run-as com.pilot.agent cat files/pilot.log" | tr -d '\r' | grep -E "$1" | tail -${2:-3}; }

# 0. 编译 + 部署 + 推模型
bash scripts/deploy-device.sh $D

# 1. 唤醒：应有「我在」+ 进入聆听
$A shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test wake"
L "唤醒|语音状态" 2

# 2. 声纹：清除后第一句话应自动注册
#    （设置 → 语音唤醒 → 清除；然后说一句话）
L "首句注册|模板已保存" 2

# 3. 免打扰：说休息类话术应回应并休眠
$A shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test say --es say_text '你休息一会'"
L "免打扰|ASLEEP" 2

# 4. 免打扰能被唤醒词叫醒
$A shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test wake"
L "唤醒" 1

# 5. 打断词在非播报状态不唤醒（不该有"进入活跃期"）
$A shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test 停一下"
L "打断词" 1

# 6. 播报中停止按钮：应出现红底白方块，点击后焦点释放
$A shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es speak_text '一段长播报内容用来测试停止按钮'"
sleep 2   # 截图确认按钮

# 7. 音乐：应有播放条（♪ 动态音柱 + 歌名 + 暂停/停止）
#    「播放一首王菲的歌曲」
L "开始播放" 1

# 8. 音色预听：设置 → 播报 → 音色下拉 → 点「试听」
#    MiniMax 需要 API Key；系统 TTS 无需网络

# 9. 长按选文字：长按消息应有蓝色高亮 + 手柄 + 浮层菜单

# 10. 输入栏：单击弹键盘（标题不被顶出屏幕）、长按录音（不弹键盘）

# 11. 沙箱挂载：设置 → 维护 → 手动填路径 /storage/emulated/0/Download
L "已挂载|挂载失败" 1

# 12. 无障碍权限开着时，任务能跑完
$A shell "am start -n com.pilot.agent/com.pilot.agent.ui.MainActivity --es goal '声音大一点'"
L "识别|音量" 3
```

**看日志的正确姿势**（EMUI 会丢弃 logcat 的第三方 `Log.i`，只能读文件日志）：
```bash
$A shell "run-as com.pilot.agent cat files/pilot.log" | tr -d '\r' | tail -50
```

## 11-11 傍晚（16:00~21:40）做了什么

> 这一段全是**交互层面**的返工，代码不难但坑极多。
> 每一条都写了「为什么这么写」，照着改回去会重踩。

### 声纹：注册流程推倒重做

**最终交互**：清除声纹后，**说第一句话就自动注册**（不用念固定口令）。

```
清除声纹 → 设置里显示"未注册 —— 说第一句话时自动注册"
   ↓ 说任意一句（≥1.5s、非问句）
声纹已记住（用你说的第一句话注册的）
   ↓
之后只认这个声音
```

**为什么推翻原来的"念 3 遍口令"**：
口令必须**包含唤醒词**（声纹校验拿「注册音频」和「唤醒时那段音频」比余弦，
两句得是同一段话），用户记不住；而且 3 遍太重。

**排除项**：问句（「注册了吗」）和太短的应答（「嗯」）不算，
否则状态询问会变成模板。

### ★★ 崩了 5 次才定位的坑：声纹模型缺 metadata

`sherpa-onnx` 构造 `SpeakerEmbeddingExtractor` 时**静默失败返回空指针**（不抛异常），
下一次 `createStream()` 直接 `SIGSEGV`——进程消失，Kotlin 完全接不住。

sherpa 自己在 logcat 里说了：
```
sherpa-onnx: No model_type in the metadata!
sherpa-onnx: Please make sure you have added metadata to the model.
```

原始 WeSpeaker 的 `cnceleb_resnet34_LM.onnx` **没有 sherpa 认的 metadata**。

**解法**：换 sherpa 官方打包的
`3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`
（27MB、192 维、metadata 带 `framework=3d-speaker`），
放 `files/models/voiceprint/campplus_sv_zh_16k.onnx`。
代码按 `MODEL_CANDIDATES` 优先找它。

**排查手法（以后 native 崩溃照这个来）**：
```bash
adb logcat -d -b crash          # native 崩溃栈（EMUI 只丢第三方 Log.i，native stderr 还在）
adb logcat -d | grep sherpa-onnx   # sherpa 自己的原生报错
```
Kotlin 的 try/catch 抓不到 native abort，这两条命令才是关键。

### 输入栏：结构重写（6 层补丁换来的教训）

**最终结构**：
```
Box
 ├─ OutlinedTextField        永远不自己处理触摸
 ├─ 流光边框 / "正在听…" 层   仅录音时
 └─ 透明手势罩层             仅「未进入文本输入态」时存在
```

**四条硬规则**（违反任一条都会重踩）：

1. `readOnly` / `disabled` 会让 TextField **停止消费指针事件**，
   长按手势就断了。要禁焦点只能用**罩层**或 `focusProperties(canFocus=false)`。
2. **按住不动时不会有新指针事件**。长按判定必须用
   `withTimeoutOrNull(450ms) { 等 up }` 靠超时主动唤醒；
   `while(awaitPointerEvent) { 比较时间戳 }` **永远不会触发**。
3. **父级 `detectTapGestures(onTap)` 和长按的抬起是同一个事件**——
   无论加多少延迟都会在"长按完松手"时误清选择。
   父级只管收焦点，"清选择"要用 `lastLongPressAt` 时间戳区分。
4. 浮层菜单不能套 `size(0)` 的 Box（**会被裁掉**），
   也不能只靠 `offset`（父 Box 仍会裁）。
   正确做法：`graphicsLayer { translationY }` 做纯视觉位移。

**配套**：manifest 加 `android:windowSoftInputMode="adjustResize"`，
否则键盘弹出会 `adjustPan` 把标题顶出屏幕。

### 长按选文字：浮在原文上的选择条

```
长按消息 → 蓝色高亮直接画在原文字上 + 两端竖线和圆点手柄
        → 半透明横向菜单浮在文字上方：复制 / 添加到输入框 / 全选
        → 点别处放弃选择
```

- 文字按分隔符切块（中文标点/问叹分冒号/空格），**保留末尾标点**。
  不要用英文句点/逗号分隔，否则 `3680.64` 会被切成 `3680.` / `64`。
- 手柄**要能反复拖**：`pointerInput` 协程里捕获的 `selRange` 是旧快照，
  必须用 `rememberUpdatedState` 读最新值。
- 「添加到输入框」后**光标自动到句末**：输入框得用 `TextFieldValue` 而不是 `String`。
- 菜单浮层用 `Dialog` 承载（普通 Box 叠层会被后面的内容盖住）。

### 音乐：播放条 + 语音输入时暂停

**聊天区上部**：♪ 动态音柱（4 根不同相位跳动，播放中动/暂停时静）+ 歌名 + 暂停/继续 + 停止。

**语音输入时自动暂停音乐**（不是停止，保留播放位置），说完自动恢复。

> ★★ 踩坑：**暂停必须释放麦克风闸门**（`AudioGate.hold("music")`）。
> 不释放的话，麦克风仍被判为"放音中不可信"→ **录不到人声**（用户反馈"暂停了但无法录入"）。
> `resume()` 时要重新占闸。

### 音色：双引擎可选 + 预听

设置 → 播报，按当前引擎只显示对应选择器：

- **MiniMax**：内置 12 个官方系统音色（青涩青年男/御姐女声/主持人…）
- **系统 TTS**：**运行时向引擎枚举**，列出所有中文相关音色
  （`zh` / `zh-CN` / `zh-TW` / `cmn` / `yue`，只按 `startsWith("zh")` 会漏）

> ★ 枚举音色**必须复用 Speaker 里已经在播报的那个 SystemTts 实例**，
> 新建一个的话引擎初始化还没完成，枚举出来是空的（实测一个都没有）。
> 还要等 `initLatch`（引擎初始化是异步的）。
> 华为引擎所有中文音色的 `name` 都叫 `"zh"`，标签得靠 序号+locale+features 区分。

**预听**：`MiniMax` 走 `voiceOverride` 合成；系统 TTS 是 `setVoice` 播完 3.5 秒切回原音色。

> ★ 试听按钮用 `pointerInput` 消费点击事件时会踩坑：
> `awaitFirstDown(requireUnconsumed=false)` 之后又写了
> `awaitFirstDown(requireUnconsumed=true)`——**第二个等的是下一个 down**，
> 一次点击只有一个 down，所以永远挂住、试听从不执行。
> 正确做法：**结构上分离**——整行不挂 clickable，左半边（文字）负责选中，
> 右半边（试听）独立 TextButton。

### 免打扰（老板定的：和自动休眠是同一个逻辑）

用户说「你休息一会 / 我先忙一会 / 别打扰我」→ **复用休眠**（`Mode.WAKE` + `State.ASLEEP`），
只跑唤醒词，不聆听、不识别指令。唤醒词或长按输入框才醒。

- **不要另造 DND 模式**——这就是"超时自动休眠"的同一条逻辑，只是触发方式不同。
- 回应一句「我先休息了，有事叫我啊」，顺序是**先休眠再播报**
  （反过来这句会被自己当成新指令听进去）。
- 按老板要求**不挂任何提示条**。
- 话术含操作动词的不算（"我休息好了放音乐"仍是任务）。

### 唤醒时说「我在」

用**新的 `speakQuiet`**（不占麦克风闸门、不抢音频焦点）：
正常播报会 `AudioGate.hold("tts")`，那样用户"喊完唤醒词马上接指令"的那半句会被丢掉。

`isWakeEcho` 要排除「我在/我在在/我在了」——不占闸的话麦克风会把它听进去。

### 其它零碎

- **打断词在非播报状态一律忽略**。原来会调 `backToIdleOrWake()` 把 `WAKE` 切成 `ACTIVE`，
  活跃期免唤醒监听 → 环境音被当对话 → **"莫名被唤醒"**（用户反馈）。
- **放音期间连 VAD 都不喂**并 `reset()`。只在 `recognize()` 入口拦不够——
  VAD 一直在攒播报声，播完整段被切出来 → ASR 误识别 → **自激循环**（日志里连着 6 轮）。
- **同内容 3 秒内去重**：长按松手时 VAD flush 一句、麦克风还在录，紧接着又切一段 → 显示两遍。
- **AI 回答删末尾括号补充**：「音量 86% 调到 100%（+14%，已到最大）」→「音量 86% 调到 100%」。
  只删含中文/加号/百分号的那组，「型号 (X100)」要留着。
- **删除「无障碍服务还没开…」提示**：顶部「操作权限 未开启」卡片已有「去开启」按钮，重复。
- **删除「声纹不匹配，已忽略这句」**：那是防误触发的正常路径，不是错误，写进对话区像出故障。
- **沙箱挂载目录**：manifest 补 `MANAGE_EXTERNAL_STORAGE` 等权限；
  SAF 的 `OpenDocumentTree` 在部分 ROM 上不弹/不返回 → 加「手动填路径」入口
  （proot bind mount 真正需要的只是宿主 POSIX 路径）。

### 新增自检入口

```bash
# 模拟识别到一句话（测意图分流用，不用真说话）
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity \
  --es voice_test say --es say_text '你休息一会'"

# 直接进免打扰
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity --es voice_test dnd"
```

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
18. **★ 更正（11-11）：「后台起 Activity 被拦」多半是误判。**
    11-10 的结论是「HarmonyOS 静默拦截后台 startActivity，唯一出路是桌面点图标」。
    11-11 用 `pidof` 直接测：`force-stop` 目标 App 后调用 `startActivity`，
    **目标进程 0.6 秒就起来了**，只是**窗口焦点要约 5 秒**才切过去。
    当时判断用的是 `rootInActiveWindow` + 1.2 秒超时，而窗口切换期它返回 **null**，
    null 被当成「前台没动」→ 把「冷启动慢」判成「被系统拦截」→ 白走一遍翻屏（10 秒还可能失败）。
    正确做法：判据用**无障碍事件流**（`AgentRuntime.foreground`，TYPE_WINDOW_STATE_CHANGED 驱动，
    已过滤输入法和自己的包）+ 6 秒预算，别用 rootInActiveWindow。
    教训：**遇到问题先怀疑测量方法，别急着归因于环境限制。**
19. **输入法窗口会抢走 `rootInActiveWindow`**：点开带输入框的页面后它返回
    `com.huawei.ohos.inputmethod`，于是「页面上没有输入框」这种假结论就出来了。
    找节点要遍历 `service.windows`，把 inputmethod 包压到最后。
20. **设置项的搜索框不是 EditText**：华为设置首屏那条是 `AutoCompleteTextView`
    （`android:id/search_src_text`），按类名找 `EditText` 永远匹配不到，要按 `isEditable` 判。
21. **搜索结果第一条就是答案**：别自己算字符串相似度去挑（会挑到搜索框自己的文字、
    或列表深处不相干的条目）。取「含关键词 + y 最小」那一条，点不动就按坐标点中心。
22. **桌面搜索的 Intent 被华为签名权限挡死**：`android.intent.action.SEARCH` 打
    `com.huawei.launcher.globalsearch.SearchLocalAppsActivity` 要
    `com.huawei.search.permission.ACCESS_REMOTE_CALL`，连 shell 都起不来。
    走 UI（下滑 → 输入）才行；自研 ROM 里把这条权限加进白名单可直接用 Intent。
23. **沙箱 W^X 两件套**：`manifest extractNativeLibs="true"` 必须配
    `jniLibs.useLegacyPackaging = true`，否则 AGP 无视 manifest，so 不解包，
    nativeLibraryDir 里没有可执行的 libproot.so。Android 10+ 唯一可 exec 的位置
    就是 nativeLibraryDir（proot 伪装成 .so 放 jniLibs 的原因）。
24. **设置子页大多「未导出」，第三方跳不进去**：`com.android.settings.EyeComfortSettings`
    （护眼）看着在 resolver 表里，实际 `startActivity` 报
    `SecurityException: not exported from uid 1000`——只有设置自己（uid 1000）能进。
    所以「打开护眼模式」只能走**设置内搜索**。这类 action 试一次失败就拉黑，
    别再撞墙（`SettingsShortcuts.markDead`）。
25. **桌面搜索结果是分区结构**：最佳匹配 / 设置 / 应用市场 / 搜索网络结果 / 资讯 /
    玩机技巧 / 小视频 / 已安装的应用。**必须按标题切区取候选**——搜 App 只点
    「已安装的应用」，搜设置项只点「设置」。不分区的结果就是点到应用市场、点到
    内嵌百度页（都实测踩过）。
26. **AGP mergeDebugAssets 自动把 `.tar.gz` 解成 `.tar`**：源码里放 .gz，APK 里只有 .tar
    （RootfsManager 双名兜底所以没事，但别在 APK 里找 .gz 找不到就慌）。
    rootfs/proot 类资源记得加 `androidResources.noCompress`，`assets.openFd` 依赖不解压。

---

## 下次的开工清单

> ⚠️ 下面的 `regress.sh` 是 11-10 的版本（10 条，验的是旧交互）。
> **先跑上面「回归清单（11-11 重做后的版本）」的 12 条**，
> 再跑 `regress.sh` 补验设备操作那部分。

**第 0 条：回归（必跑）**
```bash
bash scripts/regress.sh            # 10 条，逐条打结论（设备默认 192.168.2.32:5555）
bash scripts/regress.sh 蓝牙 护眼   # 只跑匹配的
```
已固化成脚本：每条从桌面起、清日志、触发、抽结论，`✓ 结论=完成` 才算过。
手工补测：`打开护眼模式` / `关闭蓝牙`（这两条要验**开关真的变了**，
可用 `adb shell settings get global bluetooth_on` 反查）。

**第 0 条（旧）：手工 10 条**
```bash
bash scripts/deploy-device.sh 192.168.2.32:5555   # 编译+装+推模型
adb shell "am start -n com.pilot.agent/com.pilot.agent.ui.TriggerActivity \
    --es goal '<指令>'"                          # 触发（广播在这台 ROM 上不派发）
```
依次跑：打开设置 / 回桌面 / 返回 / 点击电池 / 打开蓝牙 /
声音大一点 / 给10086打电话（预期失败，后台拦截）/ 查上证指数 / 你是谁 / 播放晴天

**第 1 条：导航端到端**
- 「导航去张掖西站」→ 看是否走 SDK 算路（日志 `SDK 算路结果=true`）
- 地图是否停在张掖（日志 `地图挪到当前位置 ... zoom=14.0`）
- 转弯提示、播报、红绿灯倒计时是否出来

**第 2 条：待确认项**
- 云端 TTS：设置页填 MiniMax Key（包名换过，配置被重置了）
- 语音选候选链路（说地点 → 列 5 条 → 说「第2个」）还没端到端验过

### 旧的开工清单（保留）

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
agent/IntentGate.kt         意图三分类 + 免打扰话术（改这个记得同步 TASK_VERBS）
agent/PlannerRouter.kt      planner 链（规则 → 端侧 → 云端）
action/SystemOps.kt         音量/亮度/电话
action/ActionCatalog.kt     系统 action 静态表
action/AppLaunchIndex.kt    2532 条 App 入口索引
query/QueryEngine.kt        查询调度
voice/*                     语音全链路
  voice/VoiceInput.kt       状态机（WAKE/ACTIVE/LISTEN/ASLEEP）+ 首句声纹注册
  voice/VoiceprintGate.kt   声纹门禁（余弦比对，失败一律放行）
  voice/SystemTts.kt        系统 TTS（音色枚举/预听）
  voice/MiniMaxTts.kt       云端 TTS（voice_id/预听）
  voice/Speaker.kt          播报门面（speak 正常 / speakQuiet 不占闸）
  voice/AudioGate.kt        麦克风闸门（放音时不可信）
music/*                     在线音乐
  music/MusicPlayer.kt      播放器（pause 必须释放麦克风闸门！）
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
