# 导航模块拆包方案

> 目标：把高德导航塞进 Pilot，**不把主包撑爆**。
> 现状：Pilot APK 89MB；高德 SDK 全量塞进去 → **+150MB**（jar 68MB + so 87MB，去重后 ~120MB）。

---

## 一、体积构成（实测 nav-agent 的 SDK）

| 组成 | 体积 | 性质 | 能不能拆走 |
|---|---|---|---|
| `libAMapSDK_NAVI_v11_3_100.so` | **68MB** | 导航核心 native | ❌ 必须带 |
| `libneonui_shared.so` / `libnui.so` / `libneonuijni_public.so` | 14MB | 导航 UI 渲染 | ❌ 必须带（导航界面要用） |
| `libopenssl.so` / `libc++_shared.so` / `libapssdk.so` | 4.5MB | 依赖库 | ⚠️ 可去重（系统已有 libc++） |
| jar 内 `assets/` | **48MB** | 地图瓦片/箭头/TTS 音色/离线模型 | ✅ **大头在这** |
| jar 内 class（3836 个） | 20MB | Java API | ⚠️ 可裁（只用 Navi/Location/Search） |

`assets/` 明细：

```
horusAssets/     11MB   离线地图资源包（.pack）
tts/             8.6MB  导航语音音色 + 语言数据（languagedata_embedded.bin 4.7MB）
autonavi_Resource 8.5MB 图标位图
eyrie/           4.5MB  引擎资源
Localizable/     3.9MB  多语言文案
map_assets/      3.7MB  地图底图素材
ae/              2.6MB  特效
navi/            1.7MB  导航专用素材
```

---

## 二、三条路线对比

### 方案 A：ROM 预置（推荐给自研 ROM 路线）

高德 so + assets 随 ROM 打包，APK 只留 jar 的 class 部分（20MB）。

```
APK 增量：+20MB（只有 class，so 和 assets 从 /system/vendor 里读）
ROM 增量：+120MB（放 /system/vendor/amap/）
```

- ✅ 体积最优，APK 只涨 20MB
- ✅ 离线可用（这是自研 ROM 的特权）
- ✅ 不用做下载逻辑、版本管理、完整性校验
- ❌ 只有自研 ROM 上能用，普通手机装 Pilot 没导航
- ❌ 依赖 ROM 与 APK 的高德版本一致（升级要同步发 ROM）

**适配方式**：高德 SDK 读 assets 时用的是 `context.getAssets()`。
我们让导航模块启动时把 `/system/vendor/amap/assets/` 挂到
`ContextWrapper.getAssets()` 的返回上——即**重写一个 AssetManager 代理**，
把 `open("tts/...")` 转发到 ROM 路径。代码量小（一个继承 AssetManager 的
子类，override open/openFd/list）。

### 方案 B：独立 APK + 按需下载（适合普通手机）

导航做成**独立 APK**（`:navigation` 另起一个包），Pilot 通过 AIDL 调它。

```
Pilot APK 增量：0
导航 APK：120MB（首次使用时下载安装）
```

- ✅ 主包零膨胀
- ✅ 普通手机也能用
- ❌ 要做下载器（断点续传、校验）、安装器、版本管理
- ❌ 两个 App 间的通信要用 IPC 传导航状态、接管播报
- ❌ 用户要多装一个 App，体验割裂

### 方案 C：Android App Bundle + 动态特性模块（Play 生态）

`:navigation` 做成 dynamic feature module。

- ✅ 单一 APK，按需下载缺失模块
- ❌ **必须有 Google Play 的 split 机制**（bundle 用 `bundletool` 拆成
  base.apk + feature.apk，由系统装到同一 uid 下）——
  国内 ROM 没 Play，`bundletool` 装出来的 split 需要手动处理
- ❌ 自研 ROM 要实现 split 安装逻辑（自己做 APK 差分安装，很麻烦）

---

## 三、推荐路线

**先做 A（ROM 预置），留 B 的接口。**

理由：
1. 项目定位就是自研 ROM 路线（`rom/` 目录已经有 Android.bp / privapp-permissions）
2. 导航是低频功能，68MB so 常驻 APK 里没意义，但放 ROM 里完全免费
3. 方案 A 的实现成本最低（主要是 AssetManager 代理 + ROM 打包脚本）

### 分阶段实施

**Phase 1（本周）：导航模块骨架 + 能力接入**
- 新建 `:navigation` 模块（Android library），依赖高德 jar + so
- 实现 `NavigationBridge`（AIDL）：Pilot 侧调 `startNavi(dest)` / `stopNavi()` /
  `getState()`，导航侧回推状态与播报文本
- 播报并入统一 TTS 队列：导航语音优先级高于普通播报（打断式），
  普通播报在导航中降级为 duck
- 把 nav-agent 的 `AmapNavController` / `AmapSdkGeocoder` / `NavActivity`
  精简移植（5400 行 → 估 2500 行，砍掉它自己的语音/ASR/车机相关）

**Phase 2：ROM 预置**
- `rom/` 加 `amap` 预置分区规则：so → `/system/vendor/amap/lib/`，
  assets → `/system/vendor/amap/assets/`
- 实现 `RomAssetManager`（AssetManager 代理），navigation 模块用 ContextWrapper
  注入，让高德 SDK 以为资源在 APK 里
- ROM 打包脚本加体积校验（高德 so 单独 strip，只留 arm64）

**Phase 3（可选）：普通手机路径**
- Phase 1 的 AIDL 接口天然支持远程（独立 APK），需要时再实现方案 B 的下载器

---

## 四、依赖清单（要带进 Pilot 的东西）

```
libs/amap/AMap3DMap_*.jar              → :navigation/lib（class 20MB）
libs/amap/arm64-v8a/*.so               → :navigation/jniLibs（87MB，Phase1 先带着）
assets/（高德的 48MB 素材）             → Phase1 先在 APK 里，Phase2 挪 ROM
```

需要的高德能力（Phase 1 就要）：
- `AMapNavi` 导航引擎 + `AMapNaviView` 界面
- `AMapLocation` 定位（算路起点）
- `AMapSearch` POI 搜索（导航目的地解析）
- 高德 Key（`AMapServices`, `AMapNavi` 各要一个）

---

## 五、风险

| 风险 | 应对 |
|---|---|
| 高德 SDK 的 so 依赖 `libc++_shared.so` 与其他库冲突 | 单独模块隔离；必要时 `packaging { pickFirst }` |
| nav-agent 的代码耦合它自己的 ASR/车机逻辑 | 移植时砍掉，只留导航核心 |
| 导航语音与 TTS 播报互相打断 | 统一 TTS 队列 + 优先级（Phase 1 第一天就要定好） |
| 高德 SDK 需要定位权限与后台定位 | 自研 ROM 已给 privileged，白名单要加 `ACCESS_BACKGROUND_LOCATION` |
