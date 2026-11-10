# 合体架构规划

三个 App 合成一个：**Pilot（Agent 内核）+ nav-agent（导航）+ minis（沙箱终端/浏览器）**，
再加音乐（网易云）和按需补充的 SDK/API。

---

## 一、合体的真正含义

不是把三个 APK 打包成一个，而是**共用一套内核，按能力域拆模块**。

```
┌─────────────────────────────────────────────────────┐
│  交互层  Voice（唤醒/ASR/TTS） + Chat + Overlay      │
├─────────────────────────────────────────────────────┤
│  决策层  Planner 链：规则 → 端侧小模型 → 云端          │
│         意图门：操作 / 查询 / 闲聊                     │
├─────────────────────────────────────────────────────┤
│  执行层                                              │
│  ├ SystemOps      音量/亮度/电话/闹钟（API 直调）      │
│  ├ DirectLaunch   Intent / deep link / 组件直达       │
│  ├ UiAutomation   无障碍点击（兜底）                   │
│  ├ QueryEngine    行情/天气/新闻/快递（HTTP）          │
│  ├ Navigator      高德 SDK（路线/导航/播报）           │
│  ├ Sandbox        终端 + 代码执行（独立进程）          │
│  └ Browser        WebView / 无头抓取                  │
├─────────────────────────────────────────────────────┤
│  底座  Accessibility + 前台服务 + 模型管理 + 日志      │
└─────────────────────────────────────────────────────┘
```

**关键**：决策层只有一套。导航、终端、浏览器都是「执行层的一个工具」，由同一个 Planner 调度。
这是合体相对「三个 App 各自有大脑」的唯一价值——你说「看看前面堵不堵，堵就换条路」，
它需要同时用导航 SDK 和查询能力，三个独立 App 做不到。

---

## 二、五个能力域：边界与依赖

### 1. Agent 内核（已有）

| 项 | 状态 |
|---|---|
| 唤醒/ASR/TTS | ✅ 端侧，实测可用 |
| 规划器链 | ✅ 规则 → 端侧 0.6B → 云端 |
| 技能库 | ✅ 成功轨迹回放 |
| 无障碍执行 | ✅ 节点树 + 手势 |
| 直达 | ✅ 2532 条 App 入口索引 |

**边界**：UI 自动化成功率 60~70%，界面改版就断。这是天花板，不是实现问题。

### 2. 导航（集成高德 SDK）

| 项 | 说明 |
|---|---|
| SDK | 高德导航 V11.3.100，**187MB**，不入 Git |
| 已有实现 | nav-agent 里跑通过：算路、播报接管、HUD、限行规避 |
| 能力边界 | 算路数据比高德 App 旧（版本决定），小区内部道路走不对——这是 SDK 侧改不了的 |
| 权限 | 定位 + 后台定位（FGS 类型必须带 location） |

**合体后的增益**：导航播报和 Agent 对话共用一条 TTS 队列，按优先级抢占。
现在 nav-agent 已经这么做了（NAV > USER > CHAT）。

### 3. 沙箱终端

**方案：直接移植 minis（`D:\minis-src`）的 sandbox 包，不重写。**

源码里已经有生产级实现，`src/android/app/src/main/java/com/openminis/app/sandbox/`：

```
7338 行，20+ 文件
PRootKernel.kt        proot 启动核心
RootfsManager         解压 Alpine rootfs
PtyBridge             PTY，真终端体验（不是管道假装）
PersistentShell       常驻 shell 会话
FreshProcessShell     一次性命令
GlobalShellThrottle   进程限流（防一棵 proot 树吃满 CPU）
ExecutionCoordinator  执行协调
```

**依赖只有两个内部类**（`FileMentionIndex`、`MountedFoldersStore`），改包名就能用。

#### ★ W^X 的解法（已验证，直接抄）

`deps/build_proot.sh` 里写得很清楚：

```
W^X forbids untrusted_app from executing anything with app_data_file label
— chmod +x succeeds, the exec still gets EACCES.
The only executable location is nativeLibraryDir...
```

做法是把可执行文件伪装成 native 库：

```
jniLibs/arm64-v8a/
├── libproot.so           276K   proot 主程序
├── libproot-loader.so     18K   ELF loader（必须一起打）
└── libproot-loader32.so  6.1K   32 位 loader
assets/
└── alpine-minirootfs.tar.gz  3.7M  Alpine 3.21.3
```

**总共 4.3MB**，不是预估的 100MB。三个 loader 缺一不可——只打包 proot 的话沙箱能启动，
但 `execve("/bin/sh")` 会失败，表现为「每条命令都没输出」。

#### 移植清单

- [ ] 拷 `jniLibs/arm64-v8a/` 三个 .so
- [ ] 拷 `assets/alpine-minirootfs.tar.gz`
- [ ] 拷 `sandbox/` 整包，改包名
- [ ] 补两个依赖类（或改成接口）
- [ ] `android:extractNativeLibs="true"`
- [ ] 独立进程 `:sandbox`

#### 剩余约束

- 必须 `arm64-v8a` 单 ABI
- 部分 syscall 在 proot 下不可用（`ptrace` 相关），`docker` 这类嵌套虚拟化跑不了
- DNS 要手工配 `/etc/resolv.conf`（proot 不接管网络栈）
- 每条命令是一个 proot 进程树（libproot + loader + sh），要限流

### 4. 浏览器

两种形态，用途完全不同：

| 形态 | 用途 | 实现 |
|---|---|---|
| **可见 WebView** | 用户要看网页、登录、扫码 | 内置 Activity |
| **无头抓取** | Agent 查资料后自己读 | `WebView` 隐藏实例 或 OkHttp + 正文提取 |

**关键设计**：无头抓取必须能「把网页变成结构化文本」再喂给模型。
直接塞 HTML 会撑爆上下文——参考 `WebExtractTool` 的做法（Tavily Extract 就是干这个的）。

**边界**：需要登录态的页面（淘宝、知乎）抓不到，只能走 UI 自动化。

### 5. 音乐

网易云**没有面向个人开发者的公开 SDK**，只有企业开放平台。所以：

| 路径 | 可行性 | 体验 |
|---|---|---|
| 官方 SDK | ❌ 拿不到 | — |
| deep link（`orpheus://`） | ✅ 能拉起 App 并带搜索词 | 打开 App 搜，用户还得点一下播放 |
| 第三方 API（NeteaseCloudMusicApi） | ⚠️ 违反 ToS，且需自建服务 | 能直接控制播放 |
| 本地曲库（MediaStore） | ✅ 合规 | 只能放本地文件 |

**建议先用 deep link**：`orpheus://song/{id}` 或搜索页，够用且不踩合规线。
真要「说一句就播」，得自建 API 代理——那是产品决策，不是技术问题。

---

## 三、工程硬约束（这才是真正决定排期的）

### 1. 包体积会炸

```
现有 Pilot APK        151MB（含 sherpa 48MB）
高德导航 SDK         +187MB
沙箱 rootfs          +100MB
--------------------------------
合计                ~440MB
```

Google Play 单 APK 上限 150MB，AAB 按需模块上限 4GB。
**必须用 Dynamic Feature / 按需下载**，主包只留 Agent 内核 + 语音。

但自研 ROM 预置就没这个限制——**这是 ROM 路线的一个隐藏优势**。

### 2. 内存会炸

```
ASR 模型常驻         239MB
LLM 权重             335MB（加载后常驻约 300MB）
高德 SDK            ~100MB
沙箱进程            ~200MB
--------------------------------
合计                ~840MB
```

8GB 手机（可用约 4GB）能跑，但**不能同时全开**。
必须做「按需加载 + LRU 卸载」：导航时不占 LLM，对话时不占导航。

### 3. 权限矩阵

| 能力 | 权限 | 类型 |
|---|---|---|
| 无障碍执行 | `BIND_ACCESSIBILITY_SERVICE` | 用户手动开 |
| 语音 | `RECORD_AUDIO` | 运行时 |
| 导航 | `ACCESS_FINE_LOCATION` + 后台定位 | 运行时 + FGS |
| 打电话 | `CALL_PHONE` | 运行时 |
| 调亮度 | `WRITE_SETTINGS` | 特殊，跳设置页 |
| 沙箱 | 存储读写 | 运行时 |

### 4. 进程模型

```
:main       Agent 内核 + UI + 语音（常驻，不能被杀）
:sandbox    沙箱终端（可随时杀）
:navigation 高德 SDK（可随时杀）
```

三个进程各自有内存上限，一个崩了不影响其他。**这是合体后能不能稳定运行的关键**。

---

## 四、分阶段路线

### P0 · 打地基（1~2 周）

- [ ] 多模块拆分：`:core`（决策/执行抽象）、`:feature-voice`、`:feature-agent`
- [ ] 进程模型落地：`:sandbox` 独立进程 + AIDL
- [ ] 按需加载 + LRU 卸载（先管 LLM 和 ASR）
- [ ] QueryEngine 骨架（先接 A股行情，免费无 key，验证链路）

**产出**：能跑「查行情 + 调音量 + 打开设置」的最小合体。

### P1 · 接导航（2~3 周）

- [ ] 高德 SDK 进 `:navigation` 进程
- [ ] 导航播报并入 TTS 队列（按优先级抢占）
- [ ] 「导航去 X」走 QueryEngine 解析地名 → 算路 → 播报
- [ ] 定位权限 + 后台 FGS 引导

**产出**：能说「导航去公司」直接开始导航。

### P2 · 沙箱终端（3~4 周，风险最高）

- [ ] proot + rootfs 方案验证（先跑通 `ls` / `curl`）
- [ ] ABI 单架构（arm64-v8a）
- [ ] 沙箱与主进程通信协议
- [ ] 「在终端里跑 XXX」的自然语言入口

**风险**：这一步可能撞墙（syscall 限制、厂商 SELinux 拦截）。**建议先做 2 天技术验证再排期**。

### P3 · 浏览器 + 音乐（2 周）

- [ ] 可见 WebView + 无头抓取 + 正文提取
- [ ] 网易云 deep link 接入
- [ ] 「查一下 XX 新闻」→ 抓取 → 摘要 → 播报

### P4 · 按需补 SDK/API

行情、天气、快递、新闻……都是「加一个 `Tool` 实现」的量级，
架构上已经预留（QueryEngine 的 tool 注册表）。

---

## 五、需要拍板的三个决策

### 1. 沙箱走哪条路？

- **A. proot 真 Linux**（+100MB，能跑真工具，可能撞墙）
- **B. 内置解释器**（+30MB，只能跑脚本，稳）

我建议 A，但要先花 2 天做技术验证。**这个决定影响整个 P2 的可行性。**

### 2. 音乐要合规还是要好用？

- **deep link**（合规，用户体验是「打开 App 再点一下」）
- **自建 API 代理**（好用，但违反网易云 ToS，且要维护服务端）

### 3. 包体积走 Play 还是 ROM？

- **Play**：必须拆按需模块，主包 ≤150MB
- **ROM 预置**：不限制，但就不是「App 层」了

这条和你「先不做 ROM」的决定直接相关——**如果坚持 App 层，440MB 的包必须拆模块**。

---

## 六、能力边界总表（老板要的）

| 用户说 | 用什么 | 能做到什么程度 |
|---|---|---|
| 声音大一点 | SystemOps | ✅ 完全执行 |
| 给妈妈打电话 | SystemOps + 通讯录 | ✅ 直接拨出 |
| 打开电池 | DirectLaunch | ⚠️ 落点可能被厂商劫持 |
| 导航去公司 | QueryEngine + 高德 | ✅ 算路并导航 |
| 查上证指数 | QueryEngine | ✅ 需要接口（免费） |
| 查一下明天天气 | QueryEngine | ⚠️ 需要 key |
| 给张三发微信说… | UiAutomation | ⚠️ 60~70%，界面改版就断 |
| 播放周杰伦的歌 | deep link | ⚠️ 打开 App 搜到，用户点播放 |
| 在终端里跑 curl | Sandbox | ❓ 取决于方案验证 |
| 帮我订个外卖 | UiAutomation | ⚠️ 能做但不敢放开（涉及支付） |

**最硬的三条边界**：
1. **App 内操作没有 API**，只能点，成功率受界面支配
2. **厂商定制功能没有标准**，落点不可控
3. **支付类操作**不该交给 agent，风险不对等
