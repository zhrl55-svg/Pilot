# 高德 SDK 的 ROM 预置规则（方案 A，见 NAV_MODULE_PLAN.md）

## 为什么要预置

高德 SDK 塞进 APK 会让主包从 89MB 涨到 165MB（+75MB），
其中绝大部分是「资源」而不是代码：

| 内容 | 体积 | 性质 |
|---|---|---|
| `libAMapSDK_NAVI_v11_3_100.so` | 68.1MB | 导航核心 native |
| 其余 6 个 so（neonui/nui/openssl/apssdk/libc++） | 17.7MB | 依赖库 |
| jar 内 assets（tts 音色/horus 地图包/图标/多语言） | 34.0MB | **素材** |
| jar 内 class（3836 个 .class） | ~20MB | **真正的代码** |

自研 ROM 路线下，so 和素材放 ROM 里是免费的——
APK 只留 class（+20MB），用户装 App 时不用下载 75MB 的导航素材。

## 预置布局

```
/system/vendor/amap/
  lib/                       ← so（arm64-v8a）
    libAMapSDK_NAVI_v11_3_100.so
    libneonui_shared.so
    libnui.so
    libneonuijni_public.so
    libapssdk.so
    libopenssl.so
    libc++_shared.so
  assets/                    ← 高德素材（从 jar 里拆出来）
    tts/  horusAssets/  eyrie/  Localizable/  map_assets/  ae/
    autonavi_Resource1_1_0.png  navi/  icons_assets/  svg/  model/
```

## 运行时怎么找到

高德 SDK 用 `context.getAssets()` 读素材。
导航模块启动时把 assets 根重定向到 `/system/vendor/amap/assets/`：

```kotlin
// RomAssetManager：把 AssetManager 的 open/openFd/list 转发到 ROM 路径
class RomAssetManager(base: AssetManager, private val romRoot: File) : AssetManager() {
    override fun open(fileName: String, accessMode: Int): InputStream =
        FileInputStream(File(romRoot, fileName))

    override fun open(fileName: String, accessMode: Int, bufferSize: Int): InputStream =
        open(fileName, accessMode)

    override fun openFd(fileName: String): AssetFileDescriptor =
        // ROM 里的文件没有 fd 压缩，直接用 ParcelFileDescriptor.open
        ParcelFileDescriptor.open(File(romRoot, fileName), MODE_READ_ONLY)
            .let { AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }

    override fun list(path: String): Array<String> =
        File(romRoot, path).list() ?: emptyArray()
    // …其余方法 fallback 到 base
}
```

再用 `ContextWrapper` 注入给高德 SDK：

```kotlin
class NavContext(base: Context) : ContextWrapper(base) {
    override fun getAssets(): AssetManager = RomAssetManager(base.assets, File("/system/vendor/amap/assets"))
}
```

so 的加载：高德 SDK 用 `System.loadLibrary`，
需要 `android.library.search.path` 指向 ROM 路径，
或用 `System.load("/system/vendor/amap/lib/libXXX.so")` 显式加载。

## ROM 侧集成步骤

1. **产品预置**（`rom/Android.bp`）：
   ```
   PRODUCT_COPY_FILES += \
       $(LOCAL_PATH)/prebuilt/amap/lib/libAMapSDK_NAVI_v11_3_100.so:$(TARGET_COPY_OUT_VENDOR)/amap/lib/libAMapSDK_NAVI_v11_3_100.so \
       …
   ```
2. **selinux**：给 Pilot 进程加读 `/system/vendor/amap` 的权限
   （system/vendor 默认可读，但要确认 untrusted_app 域的 file_contexts）
3. **版本一致**：ROM 与 APK 的高德版本必须一致（11.3.100），
   升级要同步发 ROM。启动时校验版本号，不匹配就禁用导航并提示。

## 拆包脚本（从 jar 里拆出 assets）

```bash
#!/bin/bash
# 把高德 jar 里的 assets 抽出来（jar 里的 so 是 armeabi 的，用不了）
JAR=libs/amap/AMap3DMap_*.jar
OUT=rom/prebuilt/amap/assets
mkdir -p "$OUT"
cd /tmp && unzip -o -q "$JAR" 'assets/*'
mv /tmp/assets/* "$OUT"/
```

## 验证清单

- [ ] `adb shell ls /system/vendor/amap/lib` 能看到 7 个 so
- [ ] `adb shell ls /system/vendor/amap/assets/tts` 有音色文件
- [ ] App 里导航能起（`AMapServices.initialize` 不崩）
- [ ] APK 体积回到 ~110MB（89MB + 高德 class 20MB）
