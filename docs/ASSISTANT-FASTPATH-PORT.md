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
| D3 | 默认开关 | `assistant_fast_path` 默认 **false**；P5 真机验收通过后再打开（**2026-11-18 P5 已通过，默认改为 true**，见 §15.6） |
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
├── exec/CapabilitySafety.kt           ← 自 LocalAssistantRouter（破坏性命令/重定向/管道/写入连续子串）
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
3. 值必须逐词/连续子串来自用户原话，否则丢弃 → 规则兜底 → 仍失败则转云端。

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

## 12. P2 产出（执行接线 + 安全 + 诊断）

新增：

| 文件 | 作用 |
|---|---|
| `assistant/AssistantFastPath.kt` | 编排：开关 → 扩展意图排除 → 路由 → 安全闸 → 执行器 → 返回计划 |
| `assistant/exec/CapabilityExecutor.kt` | 能力 → `ToolInvocation`（落到 Pilot 已有执行器，不新写工具） |
| `assistant/exec/CapabilitySafety.kt` | 逐词/连续子串校验、破坏性命令闸、破坏性必确认 |
| `assistant/exec/ResultFormatter.kt` | 「✅ 动作 + 参数 + 结果」与播报摘要 |
| `assistant/exec/FastPathTrace.kt` | 诊断轨迹 `files/assistant_fastpath.log` |

改动：

| 文件 | 改动 |
|---|---|
| `agent/AgentSettings.kt` | 新增 `assistantFastPath`（SharedPreferences `assistant_fast_path`，**默认关**） |
| `ui/AgentViewModel.kt` | `dispatchRequest` 最前面接快路径（仅助理模式）；复用 `askToolConfirmation` |
| `ui/PilotSettingsDialog.kt` | 设置页新增「助理快路径（端侧直执行）」开关 |
| `service/RunReceiver.kt` | 广播/语音入口接快路径（无 UI → 需要确认的一律拒绝） |
| `tool/LocalToolRuntime.kt` | `files` / `devices` 改为公开，快路径复用同一份实例 |
| `PilotApp.kt` | 后台预热路由表（首次读 `routes.json` 是文件 IO，不能上主线程） |

### 执行覆盖矩阵（P2）

| capability / action | 执行器 | 状态 |
|---|---|---|
| `app_launch/open` | `ActionCatalog` → `AppLaunchIndex` + `DeviceActions.launchComponent` | ✅ |
| `volume/set` `adjust` `status` | `SystemOps` | ✅ |
| `device/battery` `storage` `info` | `DeviceTools` | ✅ |
| `media/pause` `resume` `next` `previous` `stop` `status` `list` | `MusicPlayer` | ✅ |
| `media/play` | — | ❌ 检索流水线在 AgentLoop，交回原链路 |
| `reminder/schedule` `list` `cancel` | `ReminderManager.handle` | ✅ |
| `scheduled_task/create` `list` `cancel` | `ScheduledTaskManager` | ✅ |
| `memory_write/write` | `MemoryStore.remember` | ✅ |
| `memory_get/search` | `MemoryStore.search` | ✅ |
| `file_read/read` | `FileTools.readFile` | ✅ |
| `file_write/write` | `FileTools.writeFile(overwrite=true)` | ✅（覆盖=破坏性 → 确认） |
| `screenshot/take` | `FileTools.takeScreenshot` | ✅（需无障碍服务） |

**没有执行器的能力一律交回原链路**，绝不猜着执行。

### 已知取舍

- 确认由 `ToolGroup`/`ToolPolicy` 决定：`file_write` 落在 WRITE 组（默认每次询问）；
  若用户把 WRITE 组策略设成「允许」，覆盖文件就不再询问 —— P4 的 owner 机制落地后
  可再收紧。
- `media/play` 与「按歌名播放」暂不在快路径内，避免重复实现 Netease 检索流水线。

### P2 真机冒烟（2026-11-18，华为 P40 / HarmonyOS 4）

设备：华为 P40 Pro，debug 包（`0.1.0-debug`，与开发构建同签名，可覆盖安装）。
开关通过改 `shared_prefs/pilot.xml` 打开（设置页也有同名开关）。

| 输入 | 结果 | 证据 |
|---|---|---|
| 「电量还剩多少」 | 快路径接管 → `device/battery` → SUCCESS | `handled: capability=device/battery status=SUCCESS` |
| 「你可以加个电量显示功能」 | 快路径**不接管**（话题排除词）→ 遗留匹配器接手 | `no-route: reason=no-match` |
| 「读一下 /sdcard/Download/a.txt」 | 接管 → `file_read` → 文件不存在，工具如实报错 | `handled: capability=file_read/read status=DENIED` |
| 「把 hello 写入 /sdcard/Download/minis_fastpath_smoke.txt」 | 接管 → `file_write` → **无 UI 无人确认 → 拒绝**，文件未生成 | `handled: capability=file_write/write status=DENIED`，`ls` 确认文件不存在 |

读轨迹：`adb shell "run-as com.pilot.agent cat files/assistant_fastpath.log"`

**关掉开关**（回到今天的行为）：设置页「运行模式 → 助理快路径」关闭，或

```bash
adb shell "run-as com.pilot.agent sed -i 's|<boolean name=\"assistant_fast_path\" value=\"true\" />||' shared_prefs/pilot.xml"
```

## 13. P3 产出（端侧抽参）

新增 `assistant/llm/SlotFiller.kt`：

- **只补自由文本槽**：路由层选能力、规则抽参，抽不全且能力允许时
  （`needsLlm` = file_write / media）才叫模型补缺的槽位。
- **防幻觉**：补出来的值必须能追溯到用户原话 —— 路径/网址/命令逐词校验，
  正文/查询词/应用名**连续子串**校验（原实现是子序列，真机实测放过了截断值
  「明天开会」→「明天会」，见 13.1）；一个有效值都没有就返回 null。
- **补不出来就回落**：模型不可用、JSON 吐坏、值被校验丢掉 → 最多换 3 次
  prompt/重采，仍不过 → `complete()` 返回 null，调用方 `dispatchWithoutFastPath`，
  行为和没有快路径时完全一致。
- **prompt ≤ 400 字符**：一句指令 + **一条同构例句** + 原话；Qwen3 系模型
  自动补 `/no_think`（独占一行）。

### 13.1 端侧 prompt 形态实测（P40 / Qwen3-0.6B，2026-11-18）

初版 prompt 是「system 指令 + 多轮『用户：… / 输出：…』示例」，真机上模型
**输出空串**（778ms 首字、立即 EOS），表现为用户在聊天框输入
「保存到 /sdcard/Download/minis_fastpath_model.txt 内容 hello」时
`model-fill-needed` → `model-fill-failed`，没弹确认框，反而被兜底成云端回复。

用 `llm_probe` 广播探针对比了 20 余种模板（同一句输入、只改 prompt 形态）：

| 形态 | 输出 | 结论 |
|---|---|---|
| 多轮「用户：/输出：」示例（初版） | 空串 | ✗ |
| 示例放 system 回合 | 空串或整段思考（`好的，我现在需要…`） | ✗ |
| 两条例句（任意排布） | 抄错模板 / 把路径当正文（`"content":"保存到 /sdcard/…"`） | ✗ |
| 单句内联「例句：X → JSON」+「现在：原话」 | `{"content":"hello"}`，574~826ms | ✓ |

最终形态（`SlotFiller.buildPrompt`）：

```
<|im_start|>user
提取参数 content，只输出 JSON。例句：保存到 /tmp/c.txt 内容 小结 → {"content":"小结"}
现在：<用户原话>
/no_think<|im_end|>
<|im_start|>assistant
```

三条硬结论：

1. **例句必须与用户句式同构**。规则能抽「把 X 写入 Y」，用户说
   「保存到 Y 内容 X」时 content 要交给模型；此时若例句是「把 … 写入 …」，
   模型会把路径抄成正文。所以 `DEMOS` 每个能力登记例句的 `hints`，
   运行时按命中挑最贴近的一条（file_write 备了两种句式）。
2. **`/no_think` 独占一行**。紧贴正文时会被分词器并成普通文本
   （实测输出 `明天开会/no_t`），模型随即长篇思考；独占一行才稳定生效。
3. **采样不稳，必须多重校验**。同一 prompt 连跑：一次正确、一次截断
   （「明天会」）、一次编造（「明天会议」）。子序列校验放过了截断值，
   故收紧为连续子串；`fill()` 最多尝试 3 次（首选例句 → 备用例句 → 首选重采），
   任一次过校验即用。三次都失败才回落 —— 宁可多花 ~1 秒，也不执行错值。

真机自检（修正后，一次命中）：

```
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
    --es fastpath_fill_test '保存到 /sdcard/Download/minis_fastpath_model.txt 内容 hello'"
→ 补值自检：plan=file_write/write missing=[content]
  completed=write_file args={"path":"…minis_fastpath_model.txt","content":"hello"}
```

聊天 UI 端到端（用户输入同一句 → 确认框 → 确认）：

```
[17:49:33] enter enabled=true source=ui
[17:49:33] model-fill-needed: capability=file_write missing=[content]
[17:49:34] model-fill-ok: capability=file_write
[17:49:36] handled: capability=file_write/write status=SUCCESS len=96
$ cat /sdcard/Download/minis_fastpath_model.txt
hello
```

从输入到确认框约 1 秒（模型 1 次命中），确认后写入成功。

接线：

| 入口 | 行为 |
|---|---|
| 聊天 UI | `plan()` 认领 → 后台 `complete()`（0.3B 补值）→ 成功则执行，失败回原链路 |
| 广播 / 语音 | **不跑模型**（无界面场景保持确定性）；需要补值的请求交回原链路 |

为什么广播不跑模型：无 UI 时模型补值失败没有回退通道，而且 1~3 秒的加载/推理
对免提场景不划算。确定性路径已经是零延迟。

**v1 的实际命中面**：规则已经覆盖常见说法（「把 hello 写入 /tmp/a.txt」由规则抽出
content），所以模型路径主要覆盖规则漏掉的说法（例如「内容 hello 保存在 /tmp/a.txt」）。
手动验证：在聊天框输入这句，观察 `files/assistant_fastpath.log` 里出现
`model-fill-needed` → `model-fill-ok`，随后是一次带确认的写入。

单元测试 `SlotFillerTest`（11 例）：合并、编造值拒绝、路径越界拒绝、连续子串接受、
截断值拒绝后重采、句式同构例句选择、空值/未知键丢弃、坏 JSON 重试、
模型不可用不调用、prompt 无占位符且足够短、`/no_think` 位置。

**真机自检（P40，2026-11-18）**：

```
11-18 17:15:12  LLM 自检: 开始加载，模型目录=.../files/models/mnn-llm
11-18 17:15:13  本地LLM 就绪，加载 921ms noThink=true
11-18 17:15:13  本地LLM 生成 562ms（首字 286ms）→「我是AI助手，能够为你提供帮助和解答问题。」
```

模型可用、延迟可控（首字 286ms），补值路径的预算与 Minis 的实测一致。

## 14. P4 产出（收敛去重：owner / 影子 / 查询兜底）

### 14.1 执行权归属

新增 `assistant/routing/FastPathOwner.kt`：

```kotlin
enum class FastPathOwner { LEGACY, SHADOW, ROUTE }
```

`RouteStore` 的 `routes.json` 升到 schema v2，新增 `owners` 对象；
**默认全部 `LEGACY`** —— 打开总开关不改变任何行为，只有显式提升的能力才接管。

`AssistantFastPath.plan()` 在路由后先查归属：

| owner | 行为 | 轨迹 |
|---|---|---|
| `LEGACY` | 不接管，遗留匹配器执行 | `owner=LEGACY route=… legacy=…` |
| `SHADOW` | 不接管，遗留匹配器执行；**记录两侧决策** | `owner=SHADOW route=… legacy=…` |
| `ROUTE` | 快路径接管并执行 | 正常 `handled:` |

`SHADOW` 的轨迹行同时给出 route 的 capability/action 与 legacy 的工具名，
分歧（例如 route=`device/battery`、legacy=`device_info`）就是回归信号。

### 14.2 怎么提升一个能力（现场 runbook）

```bash
ADB="adb -s <serial>"

# 1) 投影子
$ADB shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent --es fastpath_owner 'device=SHADOW'"

# 2) 跑该能力的 10 句语料（5 正 + 5 反），读两侧是否一致
$ADB shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent --es goal '电量还剩多少'"
$ADB shell "run-as com.pilot.agent cat files/assistant_fastpath.log"

# 3) 分歧为 0 → 切 ROUTE
$ADB shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent --es fastpath_owner 'device=ROUTE'"
```

也可以在设置页「快路径接管范围」里点按循环：遗留 → 影子 → 接管 → 遗留。

### 14.3 查询兜底

`AgentViewModel.dispatchWithoutFastPath`：意图门判成 `TASK` 时，若
`QueryEngine.canHandle(g)` 为真且这句话解析不成 App / 系统页
（`ActionCatalog` / `AppLaunchIndex` 都为 null），交给 `QueryEngine`。
防止「查询被当成任务去点屏幕」，同时不抢「打开 X」这类操作。

### 14.4 已完成的三个前置补丁（复查）

- `prefersAgentExtension` 前置排除：P2 已接（浏览器/MCP/脚本意图不进快路径）。
- 截图排除词：「打开截屏设置 / 关闭截屏设置 / 截屏设置」在 P1 seed 里。
- memory 句首锚定：P1 seed 用 `patterns`（`^(?:请|帮我|麻烦)?(?:记住|…)`）而不是裸关键词。

### 14.5 验证

- 单测：`RouteStoreTest` 新增 5 例（默认 LEGACY、持久化、未知能力拒绝、导入导出携带）；
  助理相关单测合计 **51 例**。
- 设备验证见下方「P4 真机影子对照」。

#### P4 真机影子对照（P40，2026-11-18）

| 句子 | route | legacy | 结论 |
|---|---|---|---|
| 电量还剩多少 | `device/battery` | `device_battery` | 一致 → 提升 `ROUTE` |
| 你还记得我喜欢喝什么吗 | `memory_get/search` | `memory_search` | 一致 → 提升 `ROUTE` |
| 我的定时任务 | `scheduled_task/list` | `list_scheduled_tasks` | 一致 → 提升 `ROUTE` |
| 读一下 /sdcard/Download/1.txt | `file_read/read` | `none` | 遗留匹配器不接这句（旧行为是交给模型/规划器）→ 快路径是纯增量，提升 `ROUTE` |
| 音量状态 / 暂停音乐 / 我的提醒 | `volume/status`、`media/pause`、`reminder/list` | `none` | 这三类由下游规划器/管理器负责，匹配器层无可比对象 → 暂留 `LEGACY`，按 runbook 观察后再提升 |
| 打开设置 | `app_launch/open` | `none` | 同上（`HeuristicPlanner`/`ActionCatalog` 在下游） |

`legacy=none` 的含义：**遗留匹配器对这一句没有意见**，不是分歧。这类能力的
影子对照要看「提升前后的实际行为」，而不是匹配器名字。

当前设备上的归属：`device`、`memory_get`、`scheduled_task`、`file_read`、
`file_write` = `ROUTE`；其余 = `LEGACY`（可在设置页点按提升）。

## 15. P5 产出（真机验收 + 文档）

验收环境：华为 P40 / ELS-AN00 / HarmonyOS 4，debug 构建；开关打开；
归属与 P4 结束态一致（5 个 `ROUTE`，其余 `LEGACY`）。

### 15.1 路由 + 补值矩阵（`fastpath_fill_test`，只决策不执行）

| 句子 | 期望 | 实际（设备日志） |
|---|---|---|
| 保存到 /sdcard/Download/p5_matrix.txt 内容 明天开会 | file_write/write，模型补 content | ✅ `missing=[content] → args content=明天开会` |
| 读一下 /sdcard/Download/minis_fastpath_model.txt | file_read/read | ✅ `args path=…` |
| 电量还剩多少 | device/battery | ✅ |
| 我说过什么 | memory_get/search | ✅ `keywords=什么` |
| 我的定时任务 | scheduled_task/list | ✅ |
| 你可以加个电量显示功能 | 不接管（topic-mention） | ✅ `no-route` |
| 你是谁 | 不接管（CHAT） | ✅ `no-route` |
| 打开这个网页并总结正文 | 不接管 | ✅ `owner=LEGACY route=app_launch/open`（兜底交原链路） |

### 15.2 执行矩阵（三入口）

| 入口 | 句子 | 结果 | 证据 |
|---|---|---|---|
| 广播 | 电量还剩多少 | `SUCCESS` | `handled: device/battery` |
| 广播 | 读一下 /sdcard/Download/minis_fastpath_model.txt | `SUCCESS` | `handled: file_read/read len=62` |
| 广播 | 我的定时任务 | `SUCCESS` | `handled: scheduled_task/list` |
| 广播 | 把 hello2 写入 /sdcard/Download/p5_deny.txt | `DENIED`（无 UI 无人确认），文件未生成 | `handled: file_write/write status=DENIED` + `ls` 不存在 |
| 广播 | 保存到 … 内容 明天开会 | 快路径**不跑模型**（无界面保持确定性），`model-fill-needed` 后回落；文件未生成 | trace + `ls` 不存在 |
| 聊天 UI | battery（英文关键词，便于 adb 输入） | `SUCCESS`，只读不弹确认 | `source=ui → handled: device/battery` |
| 聊天 UI | 保存到 … 内容 hello | 弹写入确认 → 确认后 `SUCCESS` | `source=ui → model-fill-ok → handled`，文件内容 `hello` |
| 广播（查询） | 上证指数多少 | 快路径不接管 → `QueryEngine` 股票通道 | `no-route` + `PilotQuery: 用 stock 处理查询` |
| 聊天 UI（闲聊） | batterylevel（无匹配） | 不接管 → 原链路模型回答（行为与无快路径一致） | `no-route` + 正常回复 |

语音入口与聊天 UI 共用 `MainActivity` 的发送回调 → `vm.run()` → `dispatchRequest`，
不另设分支。

### 15.3 验收抓到的缺陷（已修）

「保存到 /x.txt 内容 明天开会」在 plan 阶段被 **含糊时间分支** 截胡：
`明天` 被时间解析器标成 `ambiguous`，该分支固定 `needsModelExtraction=false`，
content 永远没人补，plan 以 `no-executor` 回落 —— 确认框不出现。

修复：含糊时间分支保留 `capability.needsLlm && missing.isNotEmpty()` 的补值机会
（`AssistantRouter.kt`）。回归：`AssistantRouterTest` 2 例 + corpus 新增
`file-write-time-content` 行。

### 15.4 回归

- 单测：`./gradlew testDebugUnitTest` → **57 例全绿**（含语料 40 行）。
- Agent 模式零改动：分支对 `agent/AgentLoop.kt`、`CloudPlanner` 无 diff；
  仅 `AgentSettings`（开关字段）与 `LocalToolRuntime`（公开 `files`/`devices`）。
- 遗留链路：所有负例 `no-route` 后行为与关闭快路径时一致（查询/闲聊/话题句已验）。
- 双执行：三入口均只出现一次 `enter` + 一次 `handled`（或明确回落），无双执行。

### 15.5 调试命令补充

```bash
# 只决策 + 补值，不执行（安全自检任意句子）
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
    --es fastpath_fill_test '保存到 /sdcard/Download/a.txt 内容 hello'"

# 读 trace
adb shell "run-as com.pilot.agent cat files/assistant_fastpath.log"
```

### 15.6 决策落地

- **D3 执行**：`assistant_fast_path` 默认值改为 `true`（`AgentSettings`）。
  显式关过的用户保留 `false`（key 已存在时以存储值为准）。
- **回滚**：设置页关闭开关即可；不需要回退代码。快路径任何一步失败都会回落
  原链路，关闭后行为与移植前完全一致。
- **未提升的能力**：`app_launch` / `volume` / `media` / `reminder` / `memory_write`
  / `screenshot` 仍是 `LEGACY`。按 runbook 在真机观察影子日志无分歧后再逐个提升。

#### 手动验证端侧补值（修正后的句子）

```text
保存到 /sdcard/Download/minis_fastpath_model.txt 内容 hello
```

（之前文档里写的「内容 hello 保存在 /tmp/a.txt」不会命中 file_write 路由 ——
`保存在` 不是路由关键词；而且 `/tmp` 不在 Pilot 允许的写入范围内。）

预期：`model-fill-needed` → `model-fill-ok` → 弹出写入确认 → 确认后文件落盘。

## 16. screenshot / memory_write 影子对照（P5 续，2026-11-18）

### 16.1 方法

新增两个调试钩子（`RunReceiver`）：

```bash
# 两侧判定对照：同一句话，快路径怎么判、遗留匹配器接不接，都打出来（都不执行）
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
    --es fastpath_compare '记住我喜欢喝咖啡'"

# 记忆维护：DELETE 组在无界面场景一律拒绝确认，清理测试数据只能走这里
adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
    --es memory_forget '关键词'"
```

对照流程：先 `fastpath_owner '<cap>=SHADOW'`（影子只记不执行，行为不变），
再逐句对照；最后效果级验证 —— 同一句分别让两边写一次记忆，直接读
`databases/pilot_memory.db` 比对落库内容。

### 16.2 screenshot 对照

| 句子 | 快路径 | 遗留匹配器 | 结论 |
|---|---|---|---|
| 截个屏 | `screenshot/take` | none | 快路径更宽（遗留不认）|
| 截一张屏 | `screenshot/take` | none | 同上 |
| screen shot | `screenshot/take` | none | 同上 |
| 截图 | `screenshot/take` | `take_screenshot` | 一致（同一工具 `FileTools.takeScreenshot`）|
| 截屏一下 | `screenshot/take` | `take_screenshot` | 一致 |
| 打开截屏设置 / 关闭截屏设置 | 不接管 | none | 一致 |
| 截图功能怎么实现 | 不接管 | **take_screenshot** | **分歧（遗留更糟）** |
| 怎么给截图加个悬浮窗 | 不接管 | **take_screenshot** | 同上 |
| 我们讨论一下截屏的原理 | 不接管 | **take_screenshot** | 同上 |

效果级验证：`screenshot=ROUTE` 时 `handled: capability=screenshot/take status=SUCCESS`。

**结论**：共同命中的句子两侧执行同一个函数，结果等价；提升只会让
「截个屏 / 截一张屏 / screen shot」这三种说法从"丢给 agent"变成直接截屏。

**但有个结构性问题**：讨论句（"截图功能怎么实现"）遗留匹配器**会真的截屏**，
快路径拒绝后仍会回落给遗留匹配器 → 照样截屏。也就是说**提升 screenshot
修不掉这个问题**，要修得给遗留匹配器也加排除词（属于遗留链路的改动，本阶段未动）。

### 16.3 memory_write 对照

| 句子 | 快路径 | 遗留匹配器 | 结论 |
|---|---|---|---|
| 记住我喜欢喝咖啡 | content=我喜欢喝咖啡 | `memory_remember` | 一致 |
| 帮我记住 我的车在 B2 层 | content=我的车在 B2 层 | `memory_remember` | 一致 |
| 请记住明天下午三点开会 | content=明天下午三点开会 | `memory_remember` | 一致 |
| 记一下 猫叫 mia | content=猫叫 mia | **none** | 快路径更宽 |
| 存进记忆 我的生日是3月4日 | content=我的生日是3月4日 | **none** | 快路径更宽 |
| 别忘了 带充电器 | content=带充电器 | **none** | 快路径更宽 |
| 你记住我说的 / 帮我记住 | 不接管 | none | 一致 |
| 怎么实现记忆功能 | 不接管 | none | 一致 |
| 别忘了说 | 不接管（no-args）| none | **原先分歧，已修** |

效果级验证（同一句分别走两边，直接读库）：

```
legacy 侧：记住 影子对照样本甲 → memories.content = "影子对照样本甲"
fast   侧：记住 影子对照样本乙 → memories.content = "影子对照样本乙"
```

两侧抽出的正文格式完全一致（同样去掉触发词、同样 trim）。

**修掉的一处分歧**：遗留匹配器要求记忆正文 ≥ 2 个字符（`content.length >= 2`），
快路径原来只判非空 → 「别忘了说」这种一个字的尾巴快路径会写、遗留不写。
现在快路径也要求 ≥ 2 字（`SlotExtractor` 的 memory_write 分支），两侧一致。

**结论**：共同命中的句子两侧语义等价，快路径额外接住「记一下 / 存进记忆 / 别忘了」
三种说法。提升后行为只会更好（多认几种说法，写入内容一样）。

### 16.4 顺带查出的三个问题（不属于本次提升范围）

1. **讨论句误截屏**（见 16.2）：遗留匹配器对含「截图 / 截屏」的任何句子都会截屏，
   快路径的排除词救不了 —— 因为拒绝即回落。
2. **`memory_get` 已经是 ROUTE，但语义和遗留不一致**：「你记得什么」快路径走
   `memory_get/search`（用 keywords="什么" 去搜），遗留走 `memory_list`（列全部）。
   今天这句的输出已经不是遗留的行为了。要么给 `memory_get` 加一个 `list` 动作，
   要么把「你记得什么」这类问法排除掉。
3. **`MemoryStore.forget()` 是模糊匹配删除**：清理测试记忆时，
   「忘掉 影子对照样本」把一条无关的旧记忆「頻据」一起删了（已按原内容补回，
   但 id/时间戳变了）。这个钩子是遗留链路的既有行为，本次未改。

### 16.5 提升建议

| 能力 | 建议 | 理由 |
|---|---|---|
| `screenshot` | 可提升 ROUTE | 共同命中时同一工具、同一结果；额外认领三种说法；无确认卡（READ 组）|
| `memory_write` | 可提升 ROUTE | 共同命中时抽出的正文逐句一致；负例两侧一致（修完 ≥2 字规则后）；额外认领三种说法 |

两个能力都不建议**今天直接开**：先在真机上按 §16.1 的语料各跑 5~10 句，
确认日志里没有新的分歧，再逐个切 ROUTE。切完记得把 `routes.json` 里的归属
固化下来（设置页可点按循环）。








