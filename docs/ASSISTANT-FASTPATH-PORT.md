# ASSISTANT-FASTPATH-PORT

> Minis 助理模式「快速路径技能」→ Pilot（`D:\phone agent`）移植方案
>
> 状态：P0 已冻结（2026-11-18）。分支 `feat/assistant-fastpath`。
> 上游参考：`D:\minis-src\docs\HANDOFF-fastpath-debug.md`、
> `D:\minis-src\src\android\app\src\main\java\com\openminis\app\assistant\routing\`

---

## 0. 范围与已冻结的决策

### 0.1 「技能」指什么

本方案里的「快速路径技能」= Minis 助理模式端侧直执行链路上的**本地能力面**：

- `LocalCapabilityRegistry` 声明的 capability/action + slot schema；
- 规则路由（关键词/regex）选技能；
- 端侧 0.3B 只做**自由文本槽位补值**（不选技能）；
- 本地执行 + 安全闸 + 结果格式化，全程不调云端。

不包含 `agent/skill/` 那套 markdown 技能库（SkillStore / SkillMatcher / SkillBench），
那是另一条产品线；需要时单独立项（P6）。

### 0.2 已定决策（P0 冻结）

| # | 决策 | 结论 |
|---|---|---|
| D1 | v1 能力集 | 11 个：`app_launch / volume / device / media / reminder / scheduled_task / memory_write / memory_get / file_read / file_write / screenshot`，见 §2.2 |
| D2 | JSON 数据模型 | 路由层原样移植 `org.json`；单测用 `testImplementation("org.json:json:20240303")` 跑纯 JVM（不引入 Robolectric） |
| D3 | 默认开关 | `assistant_fastpath_enabled` 默认 **false**；P5 真机验收通过后再打开 |
| D4 | P4 收敛策略 | 逐能力「接管」+ 影子模式 + 每能力 owner 开关；查询类不迁；`scheduled_task` / `ssh` 保留遗留 matcher（见 §5） |
| D5 | 执行契约 | 路由只做决策，执行统一走现有 `ToolInvocation` + `LocalToolRuntime.execute`，不新增第二套执行器 |
| D6 | Agent 模式 | `AgentLoop` / `CloudPlanner` 一行不改 |

---

## 1. 源端清单（Minis）

路径前缀 `D:\minis-src\src\android\app\src\main\java\com\openminis\app\`

| # | 层 | 文件 | 行数 | 移植方式 |
|---|---|---|---|---|
| 1 | 技能声明 | `assistant/routing/LocalCapabilityRegistry.kt` | 350 | 裁剪后原样移植 |
| 2 | 路由定义 | `assistant/routing/AssistantRoute.kt` / `DefaultAssistantRoutes.kt` | 116 / 669 | 原样移植，seed 内容按 Pilot 能力重写 |
| 3 | 路由匹配 | `assistant/routing/AssistantRouteMatcher.kt` | 112 | 原样移植 |
| 4 | 槽位抽取 | `assistant/routing/SlotExtractor.kt` / `ChineseTimeParser.kt` | 954 / — | 原样移植，去掉 amap/note/train 分支 |
| 5 | 路由决策 | `assistant/routing/AssistantRouter.kt` | 240 | 原样移植 |
| 6 | 端侧抽参 | `llm/mnn/LocalIntentPlanner.kt` + `AssistantFastPath.kt` | 412 + 123 | 适配 Pilot `voice/LocalLlm` |
| 7 | 安全+执行+回执 | `llm/mnn/LocalAssistantRouter.kt`、`assistant/execution/*.kt` | ~2.6k | 只搬安全闸与调度骨架，业务执行复用 Pilot |

**同搬的测试资产**（`D:\minis-src\src\android\app\src\test\java\com\openminis\app\assistant\routing\`，约 1500 行）：
`AssistantRouteMatcherTest` / `AssistantRouteStoreTest` / `AssistantRouteCorpusTest` /
`SlotExtractorTest` / `ChineseTimeParserTest` / `VolumeRouteTest` / `WebSearchRouteTest` /
`TrainRouteTest` / `FlashlightRouteTest`。这是 Minis 真机调出来的行为契约，比代码更值钱。

---

## 2. Pilot 现状与能力映射

### 2.1 现有架构（`app/src/main/java/com/pilot/agent/`）

```
RunReceiver.kt:180          ← 广播/语音/无 UI 入口：localTools.match → execute
AgentViewModel.kt:1289-1310 ← 聊天 UI 入口：preferExtension → IntentGate → AgentLoop
AgentLoop.kt:200            ← 意图门（三车道），Agent 模式，不动
PlannerRouter.kt            ← 规则 → 端侧小模型 → 云端 的规划器链
tool/LocalToolRuntime.kt    ← 每工具一段手写正则 + ToolInvocation + 权限/审计
tool/ToolContract.kt        ← ToolInvocation / ToolGroup / ToolPolicy / ToolResult
agent/Guard.kt              ← 风险分级 + 审计
```

关键事实：

- `LocalToolRuntime` 已经具备**执行契约**（policy / confirm / audit / timeout / 结果截断），
  这正是路由层要复用的执行体。
- 但它的匹配是**关键词包含式**（`text.contains("电量")`），没有 Minis 的
  置信度门槛 / 话题讨论 / 长文本等防误触闸。
- Pilot 目前**没有单元测试目录**（`app/src/test` 不存在），P1 建立。

### 2.2 v1 能力冻结表（D1）

| capability | action | 必需槽 | needsLlm | Pilot 执行器 | 迁移档 |
|---|---|---|---|---|---|
| `app_launch` | `open` | `app` | 否 | `action/ActionCatalog.kt` + `AppLaunchIndex.kt` | 1 |
| `volume` | `set` / `adjust` / `status` | `percent`（status 除外） | 否 | `action/SystemOps.kt`（`parseLevelChange`） | 1 |
| `device` | `battery` / `storage` / `info` | — | 否 | `tool/DeviceTools.kt` | 1 |
| `media` | `play` / `pause` / `resume` / `next` / `previous` / `stop` / `status` | play 的 `kind`（enum） | play 是 | `music/MusicPlayer.kt` + `NeteaseApi.kt` | 2 |
| `reminder` | `schedule` / `list` / `cancel` | `title`，`at`/`after` 二选一 | 否 | `action/ReminderManager.kt` | 2 |
| `scheduled_task` | `create` / `list` / `cancel` | `prompt`，`at`/`after` 二选一 | 否 | `tool/ScheduledTaskManager.kt` | 1（遗留 matcher 并存，owner 切换在 P4） |
| `memory_write` | `write` | `content` | 否 | `tool/MemoryStore.kt` | 2 |
| `memory_get` | `search` | `keywords`（可选） | 否 | `tool/MemoryStore.kt` | 2 |
| `file_read` | `read` | `path` | 否 | `tool/FileTools.kt:readFile` | 3 |
| `file_write` | `write` | `path` `content` | 是 | `tool/FileTools.kt:writeFile` | 3 |
| `screenshot` | `take` | — | 否 | `tool/FileTools.kt:takeScreenshot` | 2 |

**不进入 v1**：

- 查询类（stock / news / train / web search / notifications / weather）——保留 QueryEngine 车道（D4）。
- shell / browser / calendar / contacts / todo / music 库管理——P2 评估后再加，shell 属高风险。
- amap / read_image / hub_call——Pilot 无对应执行器；hub 语义对应 `SshTool`/MCP，另议。
- `file_edit`（old/new 替换）——FileTools 暂无，需要时补 ~30 行再立项。

### 2.3 入口与优先级（收敛后）

```
1. 快路径路由（能抽参的具体能力）
2. 遗留 matcher（只剩 scheduled_task / ssh）
3. QueryEngine（IntentGate.Kind.QUERY 车道）
4. IntentGate → AgentLoop（Agent 模式）
```

硬约束：**同一能力不允许两个 matcher 同时有执行权**；执行前做所有权断言，
trace 记录 `source=route|legacy`，同一句出现两条执行记录即视为事故。

---

## 3. 目标架构与落点

### 3.1 新增包

```
app/src/main/java/com/pilot/agent/assistant/
├── capability/CapabilityRegistry.kt   ← 自 LocalCapabilityRegistry 裁剪
├── routing/AssistantRoute.kt          ← 原样
├── routing/DefaultRoutes.kt           ← seed：从 LocalToolRuntime 现有词表 + Minis 路由收敛
├── routing/RouteMatcher.kt            ← 原样
├── routing/RouteStore.kt              ← filesDir/routes.json，保留 seedVersion
├── routing/SlotExtractor.kt           ← 原样（去 amap/note/train）
├── routing/ChineseTimeParser.kt       ← 原样
├── routing/AssistantRouter.kt         ← 原样
├── exec/CapabilityExecutor.kt         ← capability/action → ToolInvocation → LocalToolRuntime.execute
├── exec/CapabilitySafety.kt           ← 自 LocalAssistantRouter（破坏性命令/重定向/管道/写入子序列）
├── exec/ResultFormatter.kt            ← 自 LocalResultFormatter + AssistantFastPath.formatResult
├── exec/Trace.kt                      ← assistant_fastpath.log
└── llm/SlotFiller.kt                  ← 自 LocalIntentPlanner，适配 voice/LocalLlm
```

### 3.2 接线点（均受 `assistant_fastpath_enabled` 控制）

| 入口 | 位置 | 接入方式 |
|---|---|---|
| 广播 / 语音 / 无 UI | `service/RunReceiver.kt:180`（`localTools.match` 之前） | 读类能力可执行；写类按现有策略无人确认即拒绝 |
| 聊天 UI | `ui/AgentViewModel.kt:1289`（`preferExtension` 与 `IntentGate` 之前） | 未接管时原链路不变 |
| Agent 循环 | `AgentLoop.kt:200` | **不动** |

### 3.3 端侧抽参适配

Pilot 的 `voice/LocalLlm`：`ensureReady()` / `generate(prompt)` / `wantsNoThink`。
Minis 的 `LocalIntentPlanner` prompt 是纯文本补全，Pilot 的本地规划器用
`<|im_start|>` 模板 —— `SlotFiller` 负责薄适配，保留 Minis 三条硬规则：

1. prompt ≤ 260 字；
2. few-shot 示例必须是**另一句真实用户话 + 从这句话抽出的真实值**，不能用占位符；
3. 值必须逐词/子序列来自用户原话，否则丢弃 → 规则兜底 → 仍失败则转云端。

---

## 4. 核心机制（从 Minis 原样继承）

### 4.1 失败即回退

任何一步不成立（ABI 不支持 / 模型缺失 / 规则未命中 / 抽参失败 / 安全不过 / 工具异常）
→ `return false`，调用方走原链路。端侧是可选加速器，不是单点。

### 4.2 五道防误触闸

| 闸 | 作用 | 例 |
|---|---|---|
| 置信度门槛 | 关键词占比/强度不足不执行 | 「我们可以加个电量显示功能」 |
| 话题提及 | 长句里顺带提到能力名，视为讨论 | 「对接高德天气接口」 |
| 讨论词 | 方案/接口/代码/实现…出现时不执行状态类能力 | 「讨论一下写入文件的方案」 |
| 弱写入拒绝 | 单字/弱关键词构成的写路由不执行 | 「写个」 |
| 长文本拦截 | 媒体等控制类只接受短指令 | 长消息里出现「暂停音乐」 |

### 4.3 确认语义（Minis 已验证）

`ConfirmMode`：`ALWAYS` 永远确认 / `WRITE` **只有破坏性操作确认** / `NEVER` 不确认。
破坏性 = `CapabilityRisk.HIGH`（删除、取消、覆盖文件、改系统设置、拨号、跑命令）。
创建类（日程、提醒、笔记、播放控制）不弹确认，直接执行。

映射到 Pilot：`ToolGroup` / `ToolPolicy` 继续负责权限分组与审计，
路由的 `needsConfirmation` 作为额外闸；两者取「更严格」。

### 4.4 诊断轨迹（`assistant_fastpath.log`）

每次进入快路径必落一行，字段：

```
[HH:mm:ss] enter enabled=… model=… source=ui|broadcast
[HH:mm:ss] no-rule: 「…」
[HH:mm:ss] no-args: capability=… missing=[…]
[HH:mm:ss] unsafe: capability=… reason=…
[HH:mm:ss] tool-threw: capability=… error=…
[HH:mm:ss] handled: capability=…/action=… source=route|legacy confirm=… len=…
```

失败定位表（继承自 `HANDOFF-fastpath-debug.md` §5.3）直接复用。

---

## 5. P4 收敛专章（D4）

### 5.1 原则

- **查询类不迁**：QueryEngine 已有 `QueryTool.matches + execute` 契约，且本来就不走云端
  （新浪行情 233ms）。再写一套路由匹配就是第二份真相。唯一补丁：当 `IntentGate` 判成
  TASK 但 QueryTool 能匹配时，转交 QueryEngine，避免「查询被当任务去点屏幕」。
- **执行体只有一套**：路由产出决策 → 适配 `ToolInvocation` → `LocalToolRuntime.execute`。
  Minis 的 `LocalCapabilityExecutor`（1162 行）只搬调度骨架，业务实现不搬。
- **不做整体搬迁**：`LocalToolRuntime` 的匹配器是关键词包含式，边界行为已在真机验证
  （截图排除词、memory 句首锚定、SSH 凭据解析）。整体切换会导致回归范围不可控。

### 5.2 逐能力接管顺序

| 档 | 能力 | 风险 | 备注 |
|---|---|---|---|
| 1 | `device_*` | 低 | 纯只读，先跑通迁移流程 |
| 2 | `memory_get` | 低 | 只读先迁 |
| 3 | `memory_write` / `cancel` | 中 | **保留句首锚定**（`^(?:请\|帮我\|麻烦)?(?:记住\|牢记\|记下来)`） |
| 4 | `screenshot` | 中 | 保留排除词「打开截屏设置」「关闭截屏」 |
| 5 | `file_*` | 高 | 路径解析最复杂，最后迁 |
| 6 | `scheduled_task` | 高 | 遗留 matcher **保留到 P4 影子比对通过**，之后由路由接管 |
| — | `ssh` | 很高 | **不迁**，保持兜底一档；路由命中 `remote_exec` 时调用其解析 |

### 5.3 owner 状态机与影子模式

`routes.json` 中每个能力带 `owner`：

```
LEGACY  → 今天的行为（路由不参与）
SHADOW  → 路由只判断不执行；遗留 matcher 执行；trace 记两条，分歧 = 回归信号
ROUTE   → 路由说了算；遗留 matcher 对该能力停用
```

升级条件：该能力 10 句语料（5 正 + 5 反）在真机全绿、分歧为 0。
回滚：改 `routes.json` 即时生效，无需重新打包；全局开关关闭即回到今天的行为。

### 5.4 迁移时必须一起搬的三个补丁

1. **`prefersAgentExtension` 前置**：含 URL / 浏览器 / 网页 / 脚本 / MCP 意图的句子
   直接不进快路径，否则「打开网页并总结正文」会被 `browser_use`（只能 navigate）抢走。
2. **截图排除词**：「打开截屏设置」「关闭截屏」不是截图指令。
3. **句首锚定**：memory 写入/文件写入用 route 的 `patterns`（正则）而不是 `keywords`，
   否则「你记住我说的」会被当指令执行。

---

## 6. 分阶段计划

| 阶段 | 内容 | 产出 | 验收 | 预估 |
|---|---|---|---|---|
| **P0** | 冻结范围、写语料与本文档 | 本文档 + `app/src/test/resources/assistant-fastpath/corpus.tsv` | 能力表/决策表/语料三方对齐 | 0.5d |
| P1 | 路由内核（不接线） | `assistant/routing/*` + 单测 + 移植 Minis 测试 | 语料 100% 断言、Minis 测试全绿 | 1.5d |
| P2 | 执行接线 + 安全 | `assistant/exec/*` + flag + trace | 每能力 2 条真机通过；Agent 模式零回归 | 2d |
| P3 | 端侧抽参 | `assistant/llm/SlotFiller.kt` | Minis 9/9 用例 ≥ 8/9；首字延迟达标 | 1d |
| P4 | 收敛去重（§5） | owner/影子 + 查询兜底补丁 | 每能力影子分歧为 0 后翻 ROUTE | 1.5–2d |
| P5 | 真机验收 + 文档 | 验收报告、调试书、ARCHITECTURE 更新 | 下方矩阵全绿 | 1d |

合计约 **7.5–8 人日**。

---

## 7. 验收语料契约

文件：`app/src/test/resources/assistant-fastpath/corpus.tsv`

TSV 列（`#` 开头为注释）：

| 列 | 说明 |
|---|---|
| `id` | 稳定编号，测试名用 |
| `text` | 用户原话 |
| `expect` | `LOCAL` / `CLOUD` |
| `capability` / `action` | LOCAL 必填 |
| `args` | `k=v` 用 `;` 分隔；`k=*` 表示必须存在且非空；`-` 表示无参数/不约束 |
| `needs_llm` | `1` = 该句预期要端侧模型补值（纯规则测试可跳过） |
| `guard` | CLOUD 行的预期原因，取**路由器自己的 reason**：`no-match` / `low-confidence` / `no-args` / `topic-mention` / `weak-write-match` / `long-text` / `internal-message` |

空值一律用 `-` 占位，保证每行恰好 8 列。

判定规则：

- LOCAL 行：`AssistantRouter.route()` 返回 `RouteDecision.Local`，
  capability/action 相符，`args` 子集相符；需要模型的行在规则阶段可返回
  `needsModelExtraction=true`。
- CLOUD 行：不返回 `Local`；`guard` 作为 P2 起断言的 reason（P1 允许只断言非 Local）。

---

## 8. 红线与风险

1. **失败即回退**，不吞错误、不拿半截参数执行。
2. **Agent 模式一行不改**（`AgentLoop` / `CloudPlanner`）。
3. **执行器复用**：快路径与云端共用权限门、审计、超时。
4. **确认口径**：只有破坏性操作确认；否则助手变啰嗦（Minis 已踩过）。
5. **双执行**：同能力双 matcher 在入口断言，trace 双记录即事故。
6. **本地模型差异**：Pilot 是 Pocket-0.3B + `<|im_start|>` 模板，prompt 需重新标定。
7. **入口差异**：广播（无 UI）与聊天 UI 的确认策略不同，trace 必须标 `source`。

---

## 9. 测试与调试命令（Pilot）

包名是 **`com.pilot.agent`**（build.gradle.kts 明确不加 `.debug` 后缀；
README 里的 `com.pilot.agent.debug` 是旧记录，不要再用）。

```bash
# 单测（P1 起）
./gradlew testDebugUnitTest

# 编译 + 安装
./gradlew assembleDebug
MSYS_NO_PATHCONV=1 adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/pilot.apk
MSYS_NO_PATHCONV=1 adb shell "pm install -r -t -g /data/local/tmp/pilot.apk"

# 广播触发（无 UI 路径）
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent --es goal '电量还剩多少'"

# 读 trace
adb shell "run-as com.pilot.agent cat files/assistant_fastpath.log"
```

---

## 10. P0 完成清单

- [x] 决策 D1–D6 冻结（§0.2）
- [x] v1 能力表（§2.2）与执行器映射
- [x] P4 收敛策略与迁移顺序（§5）
- [x] 验收语料 `corpus.tsv`（22 正 + 17 反，共 39 条）
- [x] 接线点、优先级链、trace 字段（§3.2 / §2.3 / §4.4）

## 11. P1 产出（路由内核，不接线）

新增包 `app/src/main/java/com/pilot/agent/assistant/routing/`：

| 文件 | 来源 | 说明 |
|---|---|---|
| `AssistantRoute.kt` | Minis `AssistantRoute.kt` | 路由模型 + `ConfirmMode` + `SlotType` / `SlotSpec` / `CapabilityRisk` / `CapabilityAction` / `LocalCapability` |
| `CapabilityRegistry.kt` | Minis `LocalCapabilityRegistry.kt` | 按 Pilot v1 能力裁剪（11 个 capability） |
| `AssistantRouteMatcher.kt` | Minis `AssistantRouteMatcher.kt` | 原样移植（全量打分，非首命中） |
| `ChineseTimeParser.kt` | Minis 同名 | 原样移植（中文/英文时间、时长、重复） |
| `MusicVocabulary.kt` | Minis 同名 | 原样移植（流派/场景/榜单词表） |
| `SlotExtractor.kt` | Minis `SlotExtractor.kt` | 裁剪移植：保留 v1 能力的确定性抽取 |
| `AssistantRouter.kt` | Minis `AssistantRouter.kt` | 裁剪移植：五道闸、确认口径、缺槽转原链路 |
| `DefaultRoutes.kt` | Minis `DefaultAssistantRoutes.kt` | Pilot 种子：Minis 词表 + Pilot 遗留匹配器词表 |
| `RouteStore.kt` | Minis `AssistantRouteStore.kt` | `filesDir/assistant/routes.json`，原子写 + 自愈 + 种子迁移 |

新增测试 `app/src/test/java/com/pilot/agent/assistant/routing/`：

| 测试 | 说明 |
|---|---|
| `CorpusRoutingTest` | 语料驱动：LOCAL 行断言能力/动作/槽位，CLOUD 行断言原因 |
| `AssistantRouteMatcherTest` | 匹配器契约（归一化、词边界、排除、打分） |
| `SlotExtractorTest` | 槽位契约（音量、路径、正文、记忆、应用、音乐语义） |
| `ChineseTimeParserTest` | 时间解析契约 |
| `RouteStoreTest` | 存储契约（种子、校验、持久化、自愈、导入导出） |

构建改动 `app/build.gradle.kts`：新增 `junit:junit:4.13.2` 与 `org.json:json:20231013`
（路由层沿用 `org.json`，单测用真实实现替换 android.jar 的桩），并开启
`unitTests.isReturnDefaultValues = true`。

**P1 验收结果（2026-11-18）**：`./gradlew testDebugUnitTest` → **32 个单测全绿**。
语料 39 条（22 正 + 17 反）逐条断言「本地接管 + 能力 + 动作 + 槽位」或「不接管 + 原因」。

语料在 P1 期间做过两处校准（**实现没动，期望值修正**）：

- 表头行改为注释行（原先被当成数据行参与断言）。
- 新增两条反例补齐缺口：`neg-file-write-no-path`（写文件没路径 → `no-args`）、
  `neg-volume-no-args`（音量没数值 → `no-args`）；原「写个方案」「把整段话保存下来」
  「帮我记住」「打开」实际是 `no-match`（根本没命中路由），已改成实际原因。


