#!/usr/bin/env bash
# Pilot 模拟器冒烟测试
#
# 用法：
#   bash scripts/smoke-test.sh                # 跑默认三条用例
#   bash scripts/smoke-test.sh "打开设置"      # 只跑一条
#
# 前置：模拟器已启动，adb devices 能看到设备。

set -uo pipefail

PKG="com.pilot.agent.debug"
SVC="$PKG/com.pilot.agent.service.AgentAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
ADB="${ADB:-D:/Android/Sdk/platform-tools/adb.exe}"
DEV="${DEV:-emulator-5554}"
SHOTS="shots"
mkdir -p "$SHOTS"

say() { printf '\n== %s ==\n' "$1"; }
fail() { printf '失败: %s\n' "$1"; }
a() { "$ADB" -s "$DEV" "$@"; }

say "等待设备"
"$ADB" start-server >/dev/null 2>&1
sleep 3
a wait-for-device
until [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

say "安装 APK"
a install -r "$APK" 2>&1 | tail -1

# 关键：install/force-stop 会杀掉进程，系统随即把无障碍服务从 enabled 列表移除
# （AccessibilityManagerService 的防崩溃循环机制）。所以每次装完必须重新打开。
say "重新开启无障碍"
a shell settings put secure enabled_accessibility_services "$SVC"
a shell settings put secure accessibility_enabled 1
sleep 4
if a shell dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -q "label=Pilot"; then
  echo "无障碍服务已连接"
else
  fail "无障碍服务未连接，后续用例会全部失败"
fi

run_case() {
  local goal="$1"
  local tag="$2"
  say "用例: $goal"
  a logcat -c
  # 不要用 am force-stop：会把无障碍踢掉。用 singleTask + onNewIntent 触发即可。
  a shell am start -n "$PKG/com.pilot.agent.ui.MainActivity" --es goal "$goal" >/dev/null
  sleep 14
  a exec-out screencap -p > "$SHOTS/$tag.png"
  echo "截图: $SHOTS/$tag.png"
  local top
  top=$(a shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 topResumedActivity)
  echo "前台: $top"
  a logcat -d -s AndroidRuntime:E 2>/dev/null | tr -d '\r' | tail -8
  # 回到桌面，避免上一条用例的界面干扰下一条
  a shell input keyevent KEYCODE_HOME
  sleep 2
}

if [ $# -ge 1 ]; then
  case "$1" in
    asr)
      say "端侧 ASR 自检（需先把 wav 放进 files/ 目录）"
      a logcat -c
      a shell "am broadcast -a com.pilot.agent.RUN -p $PKG --es asr_test test.wav" >/dev/null
      sleep 25
      a logcat -d 2>/dev/null | tr -d '\r' | grep -E "本地ASR|ASR 自检" | tail -5
      ;;
    llm)
      say "端侧 LLM 自检（需 arm64 真机 + 模型就位）"
      a logcat -c
      a shell "am broadcast -a com.pilot.agent.RUN -p $PKG --es llm_test start" >/dev/null
      sleep 40
      a logcat -d 2>/dev/null | tr -d '\r' | grep -E "本地LLM|LLM 自检" | tail -5
      ;;
    *)
      run_case "$1" "case_$(date +%H%M%S)"
      ;;
  esac
else
  run_case "回桌面" "01_home"
  run_case "打开设置" "02_open_settings"
  run_case "返回" "03_back"
fi

say "完成，截图在 $SHOTS/"
