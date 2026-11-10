# 导航模块架构（v2，2026-11-10）

> 目标：把高德导航接进 Pilot，语音一句话就能导航，播报走统一 TTS 队列。

---

## 一、整体链路

```
用户说「导航去张掖西站」
   │
   ├─ 主进程（:main）
   │   ├─ IntentGate        判是不是操作类指令（"导航"在 EXEC_VERBS）
   │   ├─ HeuristicPlanner  ruleNavigation() 抽目的地 → AgentAction(systemop, navigate, 张掖西站)
   │   └─ AgentLoop         "navigate" 分支 → NavigationClient.startNavi()
   │                          │
   │                          │ AIDL（INavigationService）
   │                          ▼
   └─ 导航进程（:navigation）
       ├─ NavigationService.startNavi()
       │    ├─ LocationProvider.start()      双通道定位（高德 + 系统）
       │    ├─ WebPoiSearch.search()         目的地文本 → 坐标（带距离安全阀）
       │    │     失败 ↓
       │    ├─ SdkPoiSearch.search()         SDK 兜底（1008 歧义时精化关键词）
       │    ├─ WebRoutePlanner.plan()        算路（Web API，返回 steps + polyline）
       │    └─ RouteGuide.start()            引导播报开始
       │
       └─ 定位流 → RouteGuide.onLocation()
            把位置**投影到路线 polyline** → 算进度 → 决定播报哪一句
                   │
                   │ AIDL 回推（NavCallback.onEvent: announce, priority）
                   ▼
            主进程 NavigationClient.onAnnounce
                   │
                   └─ Speaker.speakPriority(text, interrupt = priority >= 1)
                        ├─ 抢占式播报（导航提示要打断普通播报）
                        ├─ 音频焦点 DUCK（压低音乐，不断歌）
                        └─ 麦克风闸门（TTS 声音不被 ASR 听成指令）
```

---

## 二、为什么算路有两套通道

### SDK 通道（AmapNaviEngine）
- 优点：高德官方渲染、车道级导航、实时路况重算、红绿灯倒计时
- **限制：Android 平台 Key 绑包名+签名**

实测踩到的坑：
```
AMapNavi 初始化成功 ver=11.3.100
算路 38.944205,100.416897 → 38.924813,100.427326 ok=true
→ 10 秒后：算路失败 errorCode=3
```
Key 注册在 `com.navagent` 名下，我们的包名是 `com.pilot.agent.debug` → 被拒。
**同一个 Web Key 走 REST 完全正常**（返回 3274m/624s/12 步完整路线）。

### Web 通道（WebRoutePlanner + RouteGuide）
- 优点：不受包名绑定限制，播报完全可控
- 代价：没有地图渲染（NavActivity 先空着）、没有车道级导航
- **当前默认走这条**

### 切换规则
```
AMAP_KEY_PILOT 配了（绑 com.pilot.agent 的专属 Key）
    → SDK 通道（官方体验）
否则
    → Web 通道（能跑，体验差一档）
```
代码里 `NavKeys.sdkKeyReady()` 判断，两套都保留。

---

## 三、目的地解析的两个关键坑

### 坑 1：SDK 的 city 参数不生效
nav-agent 实测：`PoiSearch.Query("水勘院超市", "", "张掖市")` + `setCityLimit(true)`
返回的是**石家庄**的同名 POI，导航一路 1517 公里。

### 坑 2：通用词报 1008 歧义
「张掖西站」「西湖」这类词 SDK 直接 `code=1008`（检索词歧义），搜不出来。

### 我们的对策
1. **Web API 优先**：`city=当前城市` + `citylimit=false`（city 只作偏置）
2. **距离安全阀**（最关键）：拿到候选后按当前定位算距离，**超过 50km 直接丢**
3. 全部超距 → 报「附近没有 X，最近的同名地点在 N 公里外」，**绝不自动导航**

> 2026-11-10 老板提醒「张掖没有西湖啊」——这正是坑 2 的后果。
> 之前的代码写的是「全被过滤就保留最近的一个」，那会把用户往 1500 公里外带。
> 已改成明确报错。

---

## 四、引导播报算法（RouteGuide）

### 进度计算
**不用**「累计走过距离 vs 步长估算」——定位漂移/车速变化误差大。

用**投影法**：
1. 把每步的 polyline 拼成点序列，算累积距离 `cumDistance[i]`
2. 当前位置找最近的路线点（Haversine）
3. 最近点距离 > 500m → 判定漂移，忽略这次更新
4. 最近点所属的步骤 → 当前步

### 播报时机
| 时机 | 触发条件 | 优先级 |
|---|---|---|
| 起步 | `start()` | 1 |
| 第一步 | `start()` 里立刻播 | 1 |
| 换步 | `stepIndex > announcedStep` 且走过 >30m | 1 |
| 提前提醒 | 距当前步终点 < 200m | 1 |
| 到达 | `stepIndex >= steps.size` | 2（紧急） |

防抖：同一步 30 米内不重复播（定位抖动会导致来回跳）。

### 口语化
`向西行驶51米右转` → `向西 51 米右转`（去掉书面语的「行驶」）

---

## 五、进程与内存

| 进程 | 职责 | 实测内存 |
|---|---|---|
| `:main` | 语音、ASR、LLM、agent loop | 480MB |
| `:navigation` | 高德引擎、定位、算路、引导 | 145~250MB |
| `:sandbox` | proot + Alpine | 130MB |

`:navigation` 独立进程的理由：
1. **崩溃隔离**：高德 native 崩了不带走主进程
2. **内存独立计量**：LMK 可以单独回收它

---

## 六、踩过的坑清单

| # | 坑 | 表现 | 解法 |
|---|---|---|---|
| 1 | Key 未注入 | Web API 报 `INVALID_USER_KEY` | `local.properties` 的键不会自动进 gradle project property，必须自己读 Properties 文件（`aapt2 dump` 看到 `value=""` 才发现） |
| 2 | SDK 算路 errorCode=3 | 算路请求成功但 10 秒后失败 | Key 绑包名，换 Web 通道 |
| 3 | 高德定位在副进程不回调 | APSService 起来了但 onLocationChanged 不触发 | 系统 LocationManager 兜底 |
| 4 | 系统定位坐标精度 15 位小数 | 算路报参数错误 | 保留 6 位小数 |
| 5 | 国产 ROM 丢 logcat | 日志完全看不到 | 写文件（`NLog`/`Dbg`） |
| 6 | 华为 ROM 广播队列卡死 | `am broadcast` 显示 `dispatchClockTime=1970`，永远不派发 | `am start -n ...TriggerActivity` 绕开 |
| 7 | NaviLatLng 有参构造参数顺序歧义 | 导航去反方向 | 用无参构造 + setter |
| 8 | AMapNavi 需要 AMapNaviView | 纯 Service 里 getInstance 后不回调 | NavActivity 承载 View |

---

## 七、待办

- [ ] 等 pilot 专属 Key（绑 `com.pilot.agent.debug`）→ 验证 SDK 算路能通
- [ ] SDK 通道通了之后：NavActivity 显示 AMapNaviView 实时路线
- [ ] 「停止导航」指令（现在 stopNavi 接口有，但 agent 侧没接）
- [ ] 「还有多远」查询（getNavState 已有，需要接到对话）
- [ ] 导航中语音指令优先级（导航播报时用户说话 → 暂停播报再听）
