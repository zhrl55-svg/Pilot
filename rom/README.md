# 自研 ROM 集成指南

把 Pilot 从"一个 App"变成"系统的一部分"，要动的只有五处。按顺序做，每步都有验证命令。

---

## 0. 先搞定签名

`signature` / `privileged` 级权限只认平台签名。AOSP 的密钥在 `build/target/product/security/`，
转成 Java keystore 给 Gradle 用：

```bash
openssl pkcs8 -inform DER -nocrypt -in platform.pk8 -out platform.pem
openssl pkcs12 -export -in platform.x509.pem -inkey platform.pem \
    -out platform.p12 -name platform -passout pass:android
keytool -importkeystore -srckeystore platform.p12 -srcstoretype PKCS12 \
    -srcstorepass android -destkeystore keystore/platform.jks \
    -deststorepass android -alias platform
```

放好 `keystore/platform.jks` 之后，`assembleRelease` 会自动用它签（`app/build.gradle.kts` 里已做条件判断）。
**平台密钥不要进 git**，构建机用环境变量 `PILOT_STORE_PASSWORD` / `PILOT_KEY_PASSWORD` 注入。

---

## 1. 预置 APK 进 system 分区

```bash
cp app/build/outputs/apk/release/app-release.apk vendor/pilot/Pilot.apk
cp rom/Android.bp rom/privapp-permissions-com.pilot.agent.xml vendor/pilot/
```

`device.mk` 加：

```makefile
PRODUCT_PACKAGES += Pilot privapp-permissions-com.pilot.agent
```

验证（刷机后）：

```bash
adb shell pm path com.pilot.agent
# 期望：package:/system/priv-app/Pilot/Pilot.apk
```

如果路径落在 `/data/app`，说明 `PRODUCT_PACKAGES` 没生效，检查 module 名是否拼错。

---

## 2. 白名单授权

文件必须和 APK 同分区。`pm path` 是 `/system/priv-app` → 白名单放 `/system/etc/permissions/`；
是 `/product/priv-app` → 放 `/product/etc/permissions/`。

验证：

```bash
adb shell dumpsys package com.pilot.agent | grep -E "EXECUTE_APP_FUNCTIONS|COMPUTER_CONTROL|INJECT_EVENTS"
```

期望看到 `granted=true`。有 `requested=true granted=false` 的就是白名单没对上，日志里会有
`PrivilegedPermissionAllowlist` 警告。

---

## 3. 平台权限声明（AOSP 分支可能缺）

`android.permission.ACCESS_COMPUTER_CONTROL` 是 Google 给 OEM 侧的权限，纯 AOSP 分支里可能没有定义。
没有定义时 `uses-permission` 声明会被静默忽略，install 不报错但权限永远拿不到。

在 `frameworks/base/core/res/AndroidManifest.xml` 补：

```xml
<permission android:name="android.permission.ACCESS_COMPUTER_CONTROL"
    android:protectionLevel="signature|privileged"
    android:label="@string/permlab_accessComputerControl"
    android:description="@string/permdesc_accessComputerControl" />
```

`EXECUTE_APP_FUNCTIONS` 在 Android 16+ 的 AOSP 里已存在，无需重复声明（重复声明会导致
`Duplicate permission` 编译错误）。

---

## 4. 默认助理 + 无障碍常驻

```makefile
PRODUCT_PACKAGES += PilotAssistantOverlay
```

覆盖的是 `config_defaultAssistant`，改完长按 Home 直接唤起 `AssistActivity`。

无障碍开机自启用 `pilot_settings_defaults.xml`，但先确认字段名：

```bash
grep -rn "accessibility" frameworks/base/packages/SettingsProvider/res/values/defaults.xml
```

验证：

```bash
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility | grep -A3 "Pilot"
```

**这一步有合规代价**：默认开启无障碍会削弱用户控制权，零售版建议保持关闭让用户自己选。
政企定制、专用设备（点餐机、巡检机）可以默认开。

---

## 5. sepolicy

权限拿到 ≠ 能调通。跨进程访问系统服务还要 SELinux 放行。不要凭猜写规则，用审计日志反推：

```bash
adb shell su 0 setenforce 0          # 临时 permissive 定位问题
# 复现一次操作
adb shell dmesg | grep avc            # 或 adb logcat | grep avc
```

把 `avc: denied` 的条目交给 `audit2allow`：

```bash
adb pull /sys/fs/selinux/policy .
audit2allow -p policy < avc.log
```

典型需要放行的方向：agent 的 domain 对 AppFunctions 服务、对虚拟显示服务、
对 `system_app` 的 binder 调用。写完规则记得 `setenforce 1` 回归验证。

---

## 6. 验证清单

| 检查项 | 命令 | 期望 |
|---|---|---|
| 安装位置 | `adb shell pm path com.pilot.agent` | `/system/priv-app/...` |
| 特权权限 | `adb shell dumpsys package com.pilot.agent \| grep granted` | 关键权限 `granted=true` |
| 签名 | `adb shell dumpsys package com.pilot.agent \| grep signatures` | 与 platform 一致 |
| 函数注册表 | `adb shell cmd app_function list-app-functions --package <目标App>` | 列出目标 App 的函数 |
| 无障碍 | `adb shell dumpsys accessibility \| grep Pilot` | 已连接 |
| 默认助理 | `adb shell settings get secure assistant` | `com.pilot.agent` |

---

## 7. 三个会咬人的坑

**CTS 会查白名单。** 预置特权应用如果声明的权限没写进白名单，`CtsPermissionTestCases` 会挂。
反过来，白名单里写了不存在的权限名，`PackageManager` 启动时会打 warning 并忽略——不会崩，
但权限就是没了，很难查。写完跑一遍：

```bash
adb shell dumpsys package com.pilot.agent | grep -i "unknown permission"
```

**GMS 授权。** 如果产品要过 GMS 认证，预置一个能读全屏内容 + 注入输入的应用会被重点审查，
默认开启无障碍几乎必然被拒。要么走 non-GMS 版本，要么保持默认关闭。

**Android 17 的高级保护模式。** Beta 2 起限制非官方无障碍工具调用 API。
系统签名 + priv-app 不受影响，但**同一份代码如果以普通 App 形态发出去会被拦**。
这也是为什么定制品和零售品必须分开打包。

---

## 8. 对齐国标 GB/Z 177—2026 L3

自研 ROM 做 agent，最终大概率要拿 L3 辅助级认证。标准里 L3 的核心能力项和本工程的对应关系：

| 标准能力项 | 工程对应 | 现状 |
|---|---|---|
| 理解复杂意图 | 云端 Planner + 双速路由 | 已实现 |
| 多模态融合感知 | 节点树（已实现）+ 截图 VLM（接口已留） | 部分 |
| 链式推理与任务规划 | AgentLoop 多步循环 | 已实现 |
| 调用工具/操作应用 | DeviceActions + AppFunctionBridge | 已实现（AppFunc 待接通） |
| 生成多模态内容 | 未覆盖 | 缺 |
| 长期记忆与情境适应 | 技能库 + 审计日志 | 部分（缺用户画像） |

缺的两块（多模态生成、长期记忆）是下一阶段的事，不影响先跑通操作闭环。
