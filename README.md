# Pilot — 系统级手机 Agent

自研 ROM 路线下的手机助理内核。核心不是"聊天框里接个大模型"，而是三件事：
**能看见屏幕、能操作应用、不闯祸**。

---

## 现在能跑通的闭环

```
你说一句话
  → 技能库先查有没有可回放的旧路径（命中直接跑，不惊动模型）
  → 没有就走双速路由：简单指令端侧规则，复杂任务云端规划
  → 每轮重抓屏幕（节点树优先，截图兜底）
  → 模型输出一个原子动作（tap_node / input / open / key / scroll …）
  → 护栏检查风险（支付、删除、对外发送 → 强制二次确认 + 审计留痕）
  → 执行，记录轨迹
  → 任务成功后把整条轨迹沉淀成技能，下次直接回放
```

跑完一次完整任务，第二次同类任务的成功率和延迟都会明显好于第一次——这是技能库的作用，
也是把通用 GUI agent 的 ~66% 成功率往 90%+ 拽的唯一工程手段。

---

## 工程结构

```
app/src/main/java/com/pilot/agent/
├── perception/          感知层
│   ├── UiNode.kt          节点模型 + 压缩成 prompt 的格式
│   └── ScreenReader.kt    无障碍树抓取、截图兜底、节点缓存
├── action/
│   └── DeviceActions.kt   执行层：performAction 优先，手势注入兜底
├── agent/               编排层
│   ├── AgentAction.kt     动作协议（单一 data class，容错好）
│   ├── Planner.kt         Planner 接口 + 事件类型
│   ├── HeuristicPlanner.kt  端侧快思考（规则，离线可用）
│   ├── CloudPlanner.kt      云端慢思考（OpenAI 兼容）
│   ├── PlannerRouter.kt     双速路由
│   ├── AgentLoop.kt         主循环 + 死循环检测 + 确认挂起
│   ├── SkillStore.kt        技能库（bigram 匹配 + 语义回放）
│   └── Guard.kt             风险判定 + 审计日志
├── appfunction/
│   └── AppFunctionBridge.kt AppFunctions 调用方桥（待 ROM 权限到位）
├── service/
│   └── AgentAccessibilityService.kt  常驻入口
├── runtime/
│   └── AgentRuntime.kt    依赖容器 + 事件总线
└── ui/                    Compose 界面
rom/                      ROM 集成资产（见 rom/README.md）
```

### 实时资讯搜索

查询通道支持 A 股主要指数行情，以及通过 Google News RSS 搜索实时新闻。可以直接问：

- 「搜一下今天的财经新闻」
- 「AI 最近有什么动态」
- 「娱乐圈最新消息」
- 「搜索英伟达最新消息」

新闻查询优先限制为最近 24 小时；如果不足 5 条，再扩展到最近 7 天。结果按发布时间倒序，过滤未来和没有有效发布时间的条目。页面显示 5 条标题、媒体来源和发布时间，并自动打开原文提取正文摘要（每篇最多约 900 字），不展示链接。语音会逐条播报 5 个标题。如果媒体站点拦截抓取、要求登录或使用脚本动态加载，会回退到 RSS 摘要或显示正文抓取失败原因。不需要单独配置 API Key。

通用网页搜索使用 Brave Search API。在 Pilot 设置中填写 Brave Search API Key 后，可以问「网页搜索……」；搜索后会自动打开前 3 条结果，提取正文摘要（每篇约 1,200 字），回答中不展示链接。Key 使用 Android Keystore 加密保存在本机。说「打开这个网页 https://…」会在浏览器打开，要求「总结网页/提取网页内容」时则抓取并提取正文。搜索词和网页请求会发送给相应网络服务。

### 本地助理工具

- 提醒：如「30 分钟后提醒我喝水」「明天下午 3 点提醒我开会」「我的提醒」「取消提醒」。提醒文本本地加密保存，通过系统闹钟触发通知；需要允许 Pilot 通知。
- 待办：如「添加待办 买牛奶」「我的待办」「完成待办 买牛奶」「删除待办 买牛奶」。待办本地加密保存。
- 日历：如「添加日程 明天下午 3 点项目讨论」。Pilot 会打开系统日历的新建界面，确认内容后由用户保存。
- 通知：在 Pilot 设置中打开「通知读取」并授予系统通知访问权限后，可说「读一下通知」或「最近的通知」。只缓存开启服务后的最近通知，验证码等敏感内容会遮蔽；缓存仅在内存中，重启后清空。
- AppFunctions：桥接按 Android AppFunctions 接口反射调用，支持它的系统/应用与 ROM 才可用。当前测试机 HarmonyOS 4（API 31）不提供 Android 16（API 36）的执行接口，因此此项在该机上不可用。

---

## 跑起来

```bash
# 编译
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug

# 装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 开无障碍：设置 → 无障碍 → Pilot 系统操作 → 开启
```

App 里点"设置"填云端模型（任意 OpenAI 兼容接口：DeepSeek / 通义 / 智谱 / 自建 vLLM）。
**不填也能用**——端侧规则覆盖"打开X / 返回 / 回桌面 / 点击X / 输入X"这类单步指令。

---

## 三个刻意的设计选择

**1. 节点树优先，截图兜底。**
一张截图进 VLM 就是几千 token、一秒以上的延迟；节点树压平后通常 800~1500 token、几十毫秒。
只有节点树拿不到信息时（游戏、Canvas、部分 Flutter 自绘）才截图。这个顺序反了，成本和延迟会直接失控。

**2. 动作协议用单一 data class，不用 sealed class。**
模型输出经常多吐或少吐字段。sealed class 反序列化遇到意外字段就整体失败，
宽松的 data class 能容错并让护栏继续工作。宁可动作降级，不要流程中断。

**3. 护栏在模型之外。**
`Guard` 独立于 Planner，按目标节点文本 + 屏幕文本双向匹配风险词。
不指望模型"记得"不要付款——模型会忘，代码不会。

---

## 端侧语音与端侧模型

三档能力全部在本机跑，模型文件不进 APK：

| 环节 | 方案 | 体积 | 状态 |
|---|---|---|---|
| 语音识别 | sherpa-onnx `OfflineRecognizer` + SenseVoice-Small int8 | 239MB | ✅ 模拟器实测通过 |
| 语言模型 | MNN-LLM + **Pocket-0.3B**（首选）/ Qwen3-0.6B（备选） | **164MB** / 335MB | ✅ 真机实测通过 |

### 端侧模型选型：0.3B 比 0.6B 全面更好

| 指标 | Qwen3-0.6B | **Pocket-0.3B** |
|---|---|---|
| 权重 | 335MB | **164MB** |
| 加载 | 905ms | **610ms** |
| **首字** | 477~619ms | **200ms** |
| 生成 | 957ms / 20字 | 1119ms / 100字 |

首字快 2.5 倍是交互体感的分水岭。`modelDir()` 检测到 `mnn-llm-0.3b` 目录存在就优先用它，0.6B 留作备选
（改个目录名就切换）。

**Pocket-0.3B 没有思考模式**，`llm_config.json` 里没有 `enable_thinking`，所以不用加 `/no_think`。
Qwen3 系列则必须加，否则会先写 100 多 token 的推理过程。

来源：`taobao-mnn/MNN-Pocket-0.3B-MNN`（HF 镜像 `hf-mirror.com` 下载，直连不通）。
五件套：`config.json` / `llm_config.json` / `llm.mnn` / `llm.mnn.weight` / `tokenizer.mtok`。

模型放 `filesDir/models/`：

```
files/models/
├── asr-sensevoice/
│   ├── model.int8.onnx      239MB
│   └── tokens.txt           309KB
└── mnn-llm/
    ├── config.json          502B
    ├── llm_config.json      4.8KB
    ├── llm.mnn              1.3MB
    ├── llm.mnn.weight       335MB
    └── tokenizer.mtok       4MB
```

推模型（`MSYS_NO_PATHCONV=1` 不能少，否则 Git Bash 会把 `/data/local/tmp` 改成 Windows 路径）：

```bash
export MSYS_NO_PATHCONV=1
adb push model.int8.onnx /data/local/tmp/
adb shell "run-as com.pilot.agent.debug mkdir -p files/models/asr-sensevoice"
adb shell "run-as com.pilot.agent.debug cp /data/local/tmp/model.int8.onnx files/models/asr-sensevoice/"
```

自检（不需要麦克风，用 wav 喂）：

```bash
# 识别：先把 wav 放进 files/ 目录
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --es asr_test test.wav"
# 生成
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --es llm_test start"
```

### 实测数据

```
模拟器（x86_64, Android 15）
  本地ASR 就绪，加载 2516ms
  本地ASR 850ms / 5.61s 音频 → 「对我做了介绍啊那么如果对我的研究感兴趣呢」

华为 P40 Pro（麒麟 990 / HarmonyOS 4.0 / API 31）★ 真机
  本地ASR 就绪，加载 2165ms
  本地ASR 411ms / 5.61s → 「对我做了介绍啊那么如果对我的研究感兴趣呢嗯」
  本地ASR 166ms / 1.78s → 「你是谁呀」
  本地LLM 生成 1824ms（首字 1763ms）
```

真机比模拟器快一倍——模拟器虽然能跑（sherpa 带 x86_64 库），但没有 NPU 加速。

### 真机上踩到的四个额外坑

**0. 装包用 `pm install -r -t -g`，不要用 `adb install`。** EMUI 上 `adb install` 会弹「是否允许安装」的审批框（还会被华为应用安全检查拦一次），`pm install` 走 shell 直装不弹；`-g` 直接授予运行时权限，`RECORD_AUDIO` 装完就是 `granted=true`，省掉一步 `pm grant`。

```bash
adb push app-debug.apk /data/local/tmp/pilot.apk
adb shell "pm install -r -t -g /data/local/tmp/pilot.apk"   # 18 秒，直接 Success
```

**1. EMUI 丢弃第三方应用的 logcat 输出。** `Log.i` / `Log.w` 在 `adb logcat` 里完全看不到，按 pid 抓也抓不到。所以加了 `Dbg`（文件日志）：

```bash
adb shell "run-as com.pilot.agent.debug cat files/pilot.log"
```

**2. `run-as` 的通配符拷贝在 HarmonyOS 上静默失败。** `run-as pkg sh -c 'cp /sdcard/.../* files/...'` 目录建出来了但里面是空的。改成让 App 自己从**外部私有目录**搬到内部（`ModelStore`），不依赖 `run-as`：

```
adb push 模型 → /sdcard/Android/data/<pkg>/files/models/
App 启动时自己搬到 files/models/
```

**3. 模型清单漏一个文件就是一句含糊报错。** `llm_config.json` 不在 `EXPECTED` 里，MNN 报的是 `Module load failed`，看不出缺哪个文件。`ModelStore.diagnose()` 现在会逐文件报状态和体积。

### 意图门：语音输入不能直接当任务

真机上第一版有个危险行为——用户说「你是谁呀」，agent 当成任务去点屏幕上第一个可点元素，一句话触发 3 次乱点。

`IntentGate` 的做法是先认**操作动词**（打开/点击/输入/返回/桌面…），再排除纯交流开头（你是谁/为什么/今天…）。没过门的一律不执行设备操作，交给端侧模型回答。

判据刻意不看问号：「今天几号」是提问但不该乱点，「回桌面」没有"打开"这种典型动词但确实是操作。

### 端侧 LLM 的真实水平

0.6B 能加载、能生成、延迟可接受（1.8s），但**指令遵循很弱**。实测它会把提示词示例里的占位符原样吐出来：

```
输入: {"action":"tap_node","id":N}
输出: {"action": "tap_node", "id": N}   ← N 是字面量，不是数字
```

两处修正：
1. 提示词里的示例全换成真实数字（`"id":3`），不用 `N` 占位
2. 解析层校验：`tap_node` / `input` 拿不到合法 id 就当解析失败，**不能拿 null 去点屏幕**

所以它现在的定位是「单步决策兜底」，不是云端模型的替代品。

---

### 四个必须记住的坑

1. **`OfflineRecognizer(assetManager = null, ...)`** —— 传 `context.assets` 会让 sherpa 走 AssetManager 的相对路径，和 filesDir 的绝对路径对不上，然后 native abort。Kotlin 的 `try/catch` **抓不到**，进程静默消失，logcat 里连 FATAL EXCEPTION 都没有。
2. **ITN 必须关**。SenseVoice 的 inverse text normalization 会把「三里屯」变成「3里屯」——地名里的数字是专名。
3. **`LlmSession.init()` 第一个参数是 `config.json` 的文件路径**，不是模型目录。传目录会报 `Module load failed for config: <dir>`。
4. **`onProgress` 返回 `true` 表示中止**，不是继续。写反了每轮只生成 1 个 token，日志还一切正常。

### 播报（TTS）

两套引擎，设置里可切，默认 MiniMax：

| 引擎 | 延迟 | 音色 | 成本 |
|---|---|---|---|
| MiniMax `speech-02-hd` | 合成 1.0~1.1s + 播放 | 自然 | 按字符计费 |
| 系统 TTS（华为 hiai） | 几乎 0 | 一般 | 免费 |

**云端失败自动降级系统 TTS**——联网失败、额度用尽、服务异常都不能让 agent 一声不吭。

实测（P40）：

```
MiniMax TTS 33972B 1038ms   ← 「你好，我是 Pilot」
MiniMax TTS 45492B 1073ms   ← 任务完成播报「已打开 设置」
系统 TTS 就绪 lang=zh
```

★ **`data.audio` 是十六进制字符串，不是 base64**。`494433` 解码就是 `ID3`（MP3 文件头）。按 base64 解会得到一堆乱码字节，MediaPlayer 直接报错，而且错误信息看不出是这个原因。

自检：

```bash
# 测系统 TTS
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --es tts_test '你好'"
# 测 MiniMax（临时覆盖设置，不改用户配置）
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --es tts_test '你好' --es tts_engine minimax"
```

### 语音唤醒与声纹

**唤醒词**用 sherpa-onnx zipformer KWS（4.6MB，打进 assets，不用推模型）：

```
KWS 拼音行「小派」→ x iǎo p ài @小派
KWS 就绪 words=[小派, 小派小派, 你好小派] 217ms
KWS 命中「小派」→ 唤醒，进入聆听
```

唤醒词**不是写死的**——`KwsKeywords` 用 Android 自带的 ICU (`Transliterator("Han-Latin")`)
现场把汉字转成带声调的音素行，改词只改设置。两个坑：

1. 不能用 `"Han-Latin; Latin-ASCII"`，那会把声调抹掉，而 KWS 的 token 是带调的（`iǎo` 不是 `iao`）。
2. ICU 转出来的音节不一定都在模型词表里，只要有**一行**含未知 token，`createStream` 就抛
   `Failed to create native OnlineStream`，整条链路哑掉。所以逐行校验，坏的丢掉并打日志。

**声纹**用 WeSpeaker `cnceleb_resnet34_LM`（25MB，放 filesDir）做主人确认：

```
策略：乐观放行 + 事后否决
唤醒了立刻让用户说指令（不等声纹），同时后台用唤醒词那 1.5 秒音频验声纹；
验不过就在识别结果出来时丢弃并提示。
```

阈值默认 0.30，可调（0.1~0.9）。**定位是降低误触的软门禁，不是身份认证**：
没注册模板、模型没就绪、音频不足 0.6 秒 → 一律放行。「宁可放过，不可错杀」。

nav-agent 在 P40 上实测的分数基线供参考：TTS 回声 0.02~0.11，主人 0.39~0.52。

**唤醒词残留必须挡掉。** 唤醒后 VAD 会把「小派」再切一遍送 ASR，实测识别成「拍」，
不挡的话 agent 会对这个单字走一轮对话。`isWakeEcho()` 判定：单字、包含完整唤醒词的、
纯唤醒词字符组成的两字词，一律丢弃；「返回」这种两字真指令放行。

### 云端多步规划

前置：设置里填了 OpenAI 兼容的 Key（当前用 MiniMax `minimax-m3.1-flash`）。

实测「打开设置然后点电池」，5 步完成，中间自己换了策略：

```
step=1  点击节点 [5]           ← 以为电池在首屏列表里
step=2  滚动 [0] forward       ← 没找到，往下翻
step=3  在 [16] 输入「电池」     ← 换策略：用搜索框
step=4  点击节点 [3]           ← 点第一个搜索结果
step=5  完成：已打开设置并进入电池页面
```

**规则层必须知道自己的边界。** 第一版 `ruleOpen` 的正则把「打开设置然后点电池」整句
当成了应用名，连续 4 步都在打开一个不存在的 App。现在 `isMultiStep()` 先拦一道：
有连接词（然后/接着/之后…）或出现两个以上动词，规则直接返回 `unable`，让位给下游。

云端单步 1~7 秒（取决于上下文大小），5 步约 30 秒。要提速可以砍节点数——
现在送 120 个节点，实际决策通常只用到前 20 个。

### 能启动的都不点屏幕

原则：**但凡能用 Intent 启动的，就不要去点。** 点击慢（5~30s）、脆（改版就失效）、还可能点错。

三层，从上往下找，找到就停：

| 层 | 来源 | 覆盖 | 耗时 |
|---|---|---|---|
| 1. 静态目录 | `ActionCatalog`（系统设置页 + 系统动作 + 常见 App deep link） | ~50 条高频 | 200~800ms |
| 2. App 内入口 | `AppLaunchIndex`（运行时枚举全部 exported Activity） | P40 实测 **2532 条** | 200ms（建索引 2.7s，后台） |
| 3. 模拟点击 | 兜底 | 无 action 的页面 | 5~30s |

**第 2 层是系统级 agent 的特权**：有 `QUERY_ALL_PACKAGES` 就能枚举所有 App 声明的 exported Activity
和 label。App 为了让桌面快捷方式、语音助手能唤起功能，必须把这些入口暴露出来——正好成了我们的把手。

```kotlin
val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
info.activities?.filter { it.exported }?.forEach { act ->
    val label = act.loadLabel(pm).toString()   // "扫一扫" 这类
    // 存 label → ComponentName，之后 startActivity 直启
}
```

### ★ 厂商会劫持系统 action

实测 P40（HarmonyOS 4.0）：

```
Intent.ACTION_POWER_USAGE_SUMMARY        → 节电助手（不是电池用量）
Settings.ACTION_BATTERY_SAVER_SETTINGS   → 节电助手
```

华为把电池相关的 action 全重定向到自家页面。**action 能启动成功，但落点不由你决定。**

这不是 bug，是定制 ROM 的常态。应对顺序：

1. 接受现状——落点大致对，用户能自己再点一步
2. 针对具体机型，在 `AppLaunchIndex` 里找厂商自己的 Activity（如 `com.huawei.systemmanager` 的电源管理页），用 `component` 动作直启
3. 落点验证：直达后对比 `pkg` 和预期，不符就回退点击

规则层已加保护：**同一条直达失败过就不再试**，避免重复发同一个 Intent 转到死循环
（实测「打开电池」连发 3 次直到死循环检测中止）。

### 系统能力直调（真执行）

「打开音量设置页」只是导航；「把音量调大 20%」才是执行。有系统 API 的一律直调，连 Intent 都不发。

| 能力 | 实现 | 权限 | 实测 |
|---|---|---|---|
| 调音量 | `AudioManager.setStreamVolume` | 无需 | ✅ `93% → 100%`，250ms |
| 调亮度 | `Settings.System.SCREEN_BRIGHTNESS` | `WRITE_SETTINGS`（特殊） | ⚠️ 缺权限会拉起授权页 |
| 打电话 | `Intent.ACTION_CALL` + 通讯录查号 | `CALL_PHONE` + `READ_CONTACTS` | ✅ `给10086打电话` 直接拨 |
| 闹钟/计时器 | `AlarmClock.ACTION_SET_ALARM` | 无需 | 待测 |
| 播放本地音乐 | `MediaStore` + `MediaPlayer` | `READ_MEDIA_AUDIO` | 待测 |

**自然语言 → 参数**是执行力的关键一环。用户不会说"调 20%"，他说"声音大一点"：

```
"增大音量 / 减小音量"  → ±20%（只说方向时的默认步长）
"大一点/稍微大"        → +10%
"大很多/大幅"          → +40%
"音量 60% / 调到60%"   → 设成 60%（绝对值，不是 +60%）
"最大/最响"            → +100%（钳到上限）
```

`SystemOps.parseLevelChange()` 把话分成两类：**相对增减**（±N%）和**设定值**（Absolute）。
旧版把两者混在一起，「音量调到 60%」会被当成「加 60%」——这是本次修掉的 bug。

播报必须**说清幅度**：`音量 40% 调到 60%（+20%）`。被上下限夹住时按实际变化说
（90% → 100% 是 `+10%，已到最大`），不能把用户要的 +20% 原样念出去。
系统音量档位有限（这台是 15 档），所以实际变化可能是 13% 而不是 10%，播报一律以实际为准。

**失败必须立即终止。** 第一版 systemop 失败后会重试，实测「亮度小一点」在缺权限的情况下把同一句话问了 5 遍。
直调是原子操作——缺权限就是缺权限，重试没有意义。

### 意图门要跟上执行能力

加了系统直调后，「声音大一点」被意图门挡住了——它没有"打开/点击"这类动词。
`IntentGate.TASK_VERBS` 补了音量、亮度、大声、小声、调大、调小这组词。

**规则：每新增一类执行能力，就要同步扩一次意图门**，否则能力做了但永远触发不到。

### 三类意图，三条链路

意图门把一句话分成三类，走完全不同的执行路径：

```
┌ 操作类    打开设置、点电池、发微信   → AgentLoop（点击 / 直达）
├ 系统直调  音量、亮度、打电话、播放   → SystemOps（API 直调，不碰界面）
└ 查询类    查行情、查天气、查快递     → QueryEngine（HTTP，2 秒内出答案）
```

**为什么必须分**：原来只有「任务 / 非任务」两分，「查一下上证指数」被判成闲聊扔给端侧 0.6B——
那个模型不知道今天几号，答不出来还占一次推理。

`IntentGate.classify()` 的判断顺序是**先任务后查询**：「打开设置看看有什么」有操作动词，是任务；
「查一下上证指数」没有动词，是查询。反过来判会把前者误归查询。

### QueryEngine

```
query/QueryTool.kt   接口：matches(goal) + suspend execute(goal): String?
query/QueryEngine.kt 调度：遍历工具，第一个匹配的执行
query/StockTool.kt   A 股指数行情
```

新增数据源 = 实现一个 `QueryTool` 扔进列表，调度逻辑不用动。

实测「查一下上证指数」→ **233ms** → 「上证指数 3663.72 点，涨 0.08%」→ 播报。

**新浪行情接口两个坑**：
1. **不带 `Referer: https://finance.sina.com.cn` 直接 403**（防盗链）
2. **返回是 GBK 编码**，不是 UTF-8，而且格式是 `var hq_str_s_sh000001="..."` 的 JS 赋值语句

### 在线音乐（待风控解除）

自建 `NeteaseCloudMusicApi` 在 `buntu:3001`（systemd 守护），客户端 `music/NeteaseApi.kt` + `MusicPlayer.kt`。

```
播放晴天 → 搜索 → 取播放地址 → MediaPlayer 拉流（全程不开网易云 App）
```

**登录用验证码，不要用扫码**。扫码这条路上踩了两个坑：
- 重复点「扫码登录」会取消上一次的轮询任务，用户扫的码被掐掉
- 网易云二维码只有 2~3 分钟，中间隔一次截图转发就容易过期

`/captcha/sent` + `/login/cellphone` 一次到底。

**cookie 必须清洗**：`NeteaseCloudMusicApi` 返回的 `cookie` 字段带 `Max-Age/Expires/Path` 这些
**响应头属性**，直接当请求头用会被网易云判异常。只保留 `key=value` 对（4299 → 2022 字符）。

当前卡点：`-460 检测到您的网络环境存在风险`。诊断结论——**不是 IP 类型问题**（出口是电信家宽
`124.152.86.65`），是短时间内十几次请求触发的**频率风控**，等 20 分钟自然过期。

### 规划器链

```
规则（0ms） → 端侧小模型（~1.5s） → 云端（~2s）
```

端侧小模型只在「单步短句」时插到云端前面——0.6B 干不了长链推理，让它规划跨应用任务纯属浪费 1.5 秒。某个规划器抛异常（没配 key / 模型没装）就跳到下一个，不会打断整个任务。

提示词也按 0.6B 的能力裁过：系统提示词压到 260 字以内、屏幕节点只给前 15 个、历史只留最近 3 步。实测 700 字的提示词会让首字延迟从 1.4s 涨到 3.5s。

---

## 下一步

- [ ] 端侧 LLM 在 arm64 真机上验证（Qwen3-0.6B 加载 + 生成）
- [ ] `AppFunctionBridge` 接通真实调用（需要 ROM 权限 + Android 16+ 设备）
- [ ] 截图 + VLM 分支补上（Canvas / 游戏界面）
- [ ] 用户画像与长期记忆（国标 L3 的"情境适应"项）
- [ ] 多模态内容生成（国标 L3 唯一完全没覆盖的能力项）
- [ ] 唤醒词（sherpa KWS，现在只有「点一下说一句」）

ROM 侧怎么落地，看 [`rom/README.md`](rom/README.md)。
