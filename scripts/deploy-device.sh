#!/usr/bin/env bash
# Pilot 一键部署到 arm64 真机
#
# 用法：
#   bash scripts/deploy-device.sh              # 单设备
#   bash scripts/deploy-device.sh <序列号>      # 多设备时指定
#
# 做四件事：装 APK → 授权 → 开无障碍 → 推模型 → 跑自检。
# 模型一共 574MB，push 需要一两分钟，脚本会打印进度。

set -uo pipefail
export MSYS_NO_PATHCONV=1

DEV="${1:-}"
PKG="com.pilot.agent.debug"
SVC="$PKG/com.pilot.agent.service.AgentAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
ADB="${ADB:-D:/Android/Sdk/platform-tools/adb.exe}"

# 模型源目录（用环境变量覆盖）
MODEL_ASR="${MODEL_ASR:-D:/workbuddy/2026-11-08-09-32-35/models/asr-sensevoice}"
MODEL_LLM="${MODEL_LLM:-D:/workbuddy/2026-11-08-09-32-35/qwen3-0.6b-mnn-4bit}"

say()  { printf '\n\033[36m== %s\033[0m\n' "$1"; }
ok()   { printf '\033[32m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
die()  { printf '\033[31m%s\033[0m\n' "$1"; exit 1; }

a() { if [ -n "$DEV" ]; then "$ADB" -s "$DEV" "$@"; else "$ADB" "$@"; fi; }

"$ADB" start-server >/dev/null 2>&1
sleep 2

say "1/5 检查设备"
DEVICES=$("$ADB" devices | tr -d '\r' | grep -c "device$")
[ "$DEVICES" -eq 0 ] && die "没检测到设备。检查：USB 线是不是只充电、手机上有没有点「允许 USB 调试」。"
[ "$DEVICES" -gt 1 ] && [ -z "$DEV" ] && die "接了多台设备，请传序列号：bash scripts/deploy-device.sh <序列号>"

ABI=$(a shell getprop ro.product.cpu.abi | tr -d '\r')
MODEL=$(a shell getprop ro.product.model | tr -d '\r')
ANDROID=$(a shell getprop ro.build.version.release | tr -d '\r')
echo "设备: $MODEL  Android $ANDROID  ABI=$ABI"
if [ "$ABI" != "arm64-v8a" ]; then
  warn "主 ABI 不是 arm64-v8a —— 端侧 LLM 需要 arm64，ASR 可能仍可用"
fi

say "2/5 安装 APK（151MB，含 native 库）"
[ -f "$APK" ] || die "找不到 $APK，先跑 ./gradlew assembleDebug"

# `-g` 直接授予所有运行时权限（录音），省掉单独的 pm grant
# `-t` 允许 test 包（debug 包带 android:testOnly 时需要）
#
# 华为 / 荣耀注意：需要先在「开发人员选项 → 监控 ADB 安装应用」关掉，
# 否则首次装一个新包会在手机上要求输密码。关掉之后 adb install 只要 5 秒。
# 检查：adb shell settings get secure adb_install_need_confirm   → 0 表示已关
if [ "$(a shell settings get secure adb_install_need_confirm 2>/dev/null | tr -d '\r')" = "1" ]; then
  warn "「监控 ADB 安装应用」还开着，首次装包可能要求在手机上输密码"
  warn "关闭路径：设置 → 系统和更新 → 开发人员选项 → 监控 ADB 安装应用"
fi

INSTALL_OUT=$(a install -r -t -g "$APK" 2>&1 | tail -2)
echo "$INSTALL_OUT"
echo "$INSTALL_OUT" | grep -q "Success" || die "安装失败，看上面的输出。常见原因：存储不足、签名冲突（先卸载旧包）。"

say "3/5 授权 + 开启无障碍"
# -g 已经给了运行时权限，这里只是兜底（部分 ROM 的 -g 对个别权限不生效）
a shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
a shell settings put secure enabled_accessibility_services "$SVC"
a shell settings put secure accessibility_enabled 1
sleep 4
if a shell dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -q "label=Pilot"; then
  ok "无障碍已连接"
else
  warn "无障碍没连上。部分 ROM（华为/小米）会拦 adb 改这个设置，需要手动开：设置 → 无障碍 → Pilot 系统操作"
fi

# 外部私有目录 → 应用私有目录的搬运由 App 自己做（ModelStore，启动时后台跑）。
# 不用 run-as：HarmonyOS / 部分国产 ROM 上 run-as 的通配符拷贝会静默失败，
# 实测目录建出来了但里面是空的。
say "4/5 推送端侧模型到外部私有目录（554MB）"
EXT="/sdcard/Android/data/$PKG/files/models"
a shell "mkdir -p $EXT/asr-sensevoice $EXT/mnn-llm"

if [ -d "$MODEL_ASR" ]; then
  echo "ASR 模型…"
  a push "$MODEL_ASR/model.int8.onnx" "$EXT/asr-sensevoice/" 2>&1 | tail -1
  a push "$MODEL_ASR/tokens.txt" "$EXT/asr-sensevoice/" 2>&1 | tail -1
else
  warn "跳过 ASR：$MODEL_ASR 不存在"
fi

if [ -d "$MODEL_LLM" ]; then
  echo "LLM 模型…"
  for f in config.json llm_config.json llm.mnn tokenizer.mtok llm.mnn.weight; do
    a push "$MODEL_LLM/$f" "$EXT/mnn-llm/" 2>&1 | tail -1
  done
else
  warn "跳过 LLM：$MODEL_LLM 不存在"
fi

echo "已推送，App 启动时会自己搬到私有目录（约 5~10 秒）"

# 顺带放一份测试音频，ASR 自检要用
WAV="${TEST_WAV:-D:/workbuddy/2026-10-31-21-31-07/nav-agent/android/libs/sherpa/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile/test_wavs/0.wav}"
if [ -f "$WAV" ]; then
  a push "$WAV" "/sdcard/Android/data/$PKG/files/test.wav" 2>&1 | tail -1
fi

say "5/5 启动 App、导入模型、跑自检"
a shell am start -n "$PKG/com.pilot.agent.ui.MainActivity" >/dev/null 2>&1
sleep 2

a logcat -c
a shell "am broadcast -a com.pilot.agent.RUN -p $PKG --ez import_models true" >/dev/null 2>&1
echo "搬运模型到私有目录（554MB，约 10 秒）…"
sleep 18
a logcat -d 2>/dev/null | tr -d '\r' | grep -E "ModelStore|模型导入" | tail -6

a logcat -c
a shell "am broadcast -a com.pilot.agent.RUN -p $PKG --es asr_test test.wav" >/dev/null 2>&1
echo "ASR 自检中…"
sleep 20
a logcat -d 2>/dev/null | tr -d '\r' | grep -E "本地ASR|ASR 自检" | tail -4

a logcat -c
a shell "am broadcast -a com.pilot.agent.RUN -p $PKG --es llm_test start" >/dev/null 2>&1
echo "LLM 自检中（首次加载约 2 秒）…"
sleep 35
a logcat -d 2>/dev/null | tr -d '\r' | grep -E "本地LLM|LLM 自检" | tail -6

say "完成"
cat <<'EOF'
接下来在手机上手动验三件事：

1. 打开 Pilot，看状态卡：
   「端侧ASR 就绪」「端侧LLM 就绪」—— 这两个是端侧跑通的标志。

2. 点底部「说」，对着手机说一句「打开设置」。
   停顿 0.8 秒后会自动断句识别，识别结果直接执行。

3. 说一句复杂的（比如「打开设置然后点电池」），看规划器切换：
   logcat 里应该出现 planner=local 或 planner=cloud。

看实时日志：
  adb logcat -s Pilot PilotVoice
EOF
