#!/usr/bin/env bash
# Pilot 回归脚本：跑固定指令集，从文件日志里抽结论。
#
# 用法：
#   bash scripts/regress.sh              # 跑全部 10 条
#   bash scripts/regress.sh 声音 查询      # 只跑标题/指令含这些词的
#
# 为什么不 force-stop：华为上 force-stop 会把无障碍踢掉，服务要几十秒才回绑，
# 后面的用例全报「无障碍服务未连接」。改成不杀进程，靠新任务顶掉旧任务。
# 为什么不用 am broadcast：广播队列会卡死（dispatchClockTime=1970），走 TriggerActivity。

set -uo pipefail
export MSYS_NO_PATHCONV=1

DEV="${DEV:-192.168.2.32:5555}"
PKG="${PKG:-com.pilot.agent}"
ADB="${ADB:-adb}"
LOG="files/pilot.log"
WAIT="${WAIT:-12}"

a() { "$ADB" -s "$DEV" "$@"; }

# 标题|指令|预期关键词（命中任一算过）|允许失败(1=预期内)
CASES=(
  "打开设置|打开设置|已打开,set|0"
  "回桌面|回桌面|桌面|0"
  "返回|返回|返回|0"
  "点击电池|点击电池|电池|0"
  "打开蓝牙|打开蓝牙|蓝牙|0"
  "声音大一点|声音大一点|%|0"
  "打电话|给10086打电话|拨,电话|1"
  "上证指数|查上证指数|上证|0"
  "闲聊|你是谁|闲聊,交流|0"
  "播放晴天|播放晴天|晴天|0"
)

ensure_a11y() {
  local ok
  ok=$(a shell "dumpsys accessibility | grep -c 'label=Pilot'" 2>/dev/null | tr -d '\r')
  if [ "${ok:-0}" = "0" ]; then
    a shell "settings put secure enabled_accessibility_services $PKG/$PKG.service.AgentAccessibilityService" >/dev/null 2>&1
    a shell "settings put secure accessibility_enabled 1" >/dev/null 2>&1
    sleep 6
  fi
}

run_case() {
  local title="$1" goal="$2" expect="$3" allowfail="$4"

  # 每条都从桌面开始，避免上一条停在搜索页/设置页影响判断
  a shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 1
  a shell "run-as $PKG sh -c ': > $LOG'" >/dev/null 2>&1
  a shell "am start -n $PKG/$PKG.ui.TriggerActivity --es goal '$goal'" >/dev/null 2>&1
  sleep "$WAIT"

  local out
  out=$(a shell "run-as $PKG cat $LOG" 2>/dev/null | tr -d '\r')

  local verdict="无结论" steps
  steps=$(printf '%s' "$out" | grep -c "result=")
  if printf '%s' "$out" | grep -q "run failed"; then
    verdict="异常"
  elif printf '%s' "$out" | grep -q "无障碍服务未连接"; then
    verdict="未连接"
  elif printf '%s' "$out" | grep -qE "已中止|auto-deny"; then
    verdict="中止"
  elif printf '%s' "$out" | grep -qE "result=ok|replay done|查询完成|intent gate: 闲聊|已打开|收尾不归位"; then
    verdict="完成"
  fi

  local hit="-" w
  IFS=',' read -ra WORDS <<< "$expect"
  for w in "${WORDS[@]}"; do
    if printf '%s' "$out" | grep -q -- "$w"; then hit="$w"; break; fi
  done

  local mark="✗"
  [ "$hit" != "-" ] && mark="✓"
  [ "$verdict" = "中止" ] && [ "$allowfail" = "1" ] && mark="○"

  local invalid
  invalid=$(printf '%s' "$out" | grep -c "无效动作" || true)

  printf '%s %-10s 结论=%-6s 步骤=%-2s 关键词=%-6s 无效动作=%s\n' \
    "$mark" "$title" "$verdict" "$steps" "$hit" "$invalid"
  printf '%s\n' "$out" | grep -E "planner=|result=|无效动作|收尾不归位|查询完成|run failed" | tail -4 | sed 's/^/     /'
}

echo "设备 $DEV  每条等待 ${WAIT}s"
ensure_a11y
echo "--------------------------------------------------------"
for c in "${CASES[@]}"; do
  IFS='|' read -r title goal expect allowfail <<< "$c"
  if [ "$#" -gt 0 ]; then
    match=0
    for f in "$@"; do case "$title$goal" in *"$f"*) match=1 ;; esac; done
    [ "$match" = "0" ] && continue
  fi
  run_case "$title" "$goal" "$expect" "$allowfail"
done
echo "--------------------------------------------------------"
echo "✓=命中预期  ✗=没命中  ○=预期内中止"
