#!/usr/bin/env bash
# 在 Android TV 模拟器上做冒烟测试：升级路径、Web API、遥控器操作、各界面截图、崩溃检查。
# 用法：ci-smoke.sh <新版本 APK> [旧版本 APK]
set -u

NEW_APK="$1"
OLD_APK="${2:-}"
PKG=io.github.yixuexiaoao.tvstock
OUT=smoke
FAILED=0
PORT=""
mkdir -p "$OUT"

fail() { echo "::error::冒烟测试失败：$*"; FAILED=1; }
pass() { echo "通过：$*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "截图：$1"; }
key() { adb shell input keyevent "$@"; sleep 1; }
launch() { adb shell am start -W -n "$PKG/.ui.MainActivity" > /dev/null; sleep "${1:-6}"; }

find_port() {
  PORT=""
  for candidate in 58080 58081 8080; do
    adb forward "tcp:$candidate" "tcp:$candidate" > /dev/null
    if curl -fsS -m 5 "http://127.0.0.1:$candidate/api/meta" > /dev/null 2>&1; then
      PORT=$candidate
      return 0
    fi
  done
  return 1
}

get() { curl -fsS -m 20 "http://127.0.0.1:$PORT$1"; }
post() {
  curl -sS -m 20 -H 'X-Requested-With: androidtv-stock' -H 'Content-Type: application/json' \
    -d "$2" "http://127.0.0.1:$PORT$1"
}
# json <表达式>，从标准输入读 JSON，d 是解析结果
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

wait_update_state() {
  local want="$1" state=""
  for _ in $(seq 1 30); do
    state=$(get /api/state | json "d['status']['update']['state']")
    case " $want " in *" $state "*) echo "$state"; return 0 ;; esac
    sleep 3
  done
  echo "$state"
  return 1
}

# ---------- 升级路径：先装上一个已发布版本，走一遍真实的检查更新和下载 ----------
if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ]; then
  adb install -r "$OLD_APK" || fail "旧版本安装失败"
  launch
  if find_port; then
    OLD_VERSION=$(get /api/state | json "d['status']['version']")
    pass "旧版本 $OLD_VERSION 运行在端口 $PORT"
    post /api/stocks '{"code":"600519","costPrice":1400,"shares":100,"targetHigh":99999}' > /dev/null
    post /api/settings '{"updateChannel":"PRE"}' > /dev/null
    post /api/update/check '{}' > /dev/null
    state=$(wait_update_state "AVAILABLE UP_TO_DATE ERROR")
    echo "旧版本检查更新结果：$state"
    if [ "$state" = "AVAILABLE" ]; then
      post /api/update/install '{}' > /dev/null
      # 下载后状态变为 READY，随后调起安装器或未知来源设置页
      state=$(wait_update_state "READY ERROR")
      if [ "$state" = "READY" ]; then pass "更新包下载并校验通过"; else fail "更新包下载失败：$state"; fi
      sleep 3
      shot 00-update-installer
      key KEYCODE_BACK
    elif [ "$state" = "ERROR" ]; then
      # GitHub 接口可能被限流，只提示不判失败
      echo "::warning::检查更新出错（可能是接口限流）"
    fi
  else
    fail "旧版本 Web 服务不可访问"
  fi
  adb shell am force-stop "$PKG"
fi

# ---------- 安装新版本（覆盖安装，验证配置迁移）----------
adb install -r "$NEW_APK" || { fail "新版本安装失败"; exit 1; }
launch 8
shot 01-first-run-guide
find_port || { fail "Web 服务不可访问"; adb logcat -d -b crash; exit 1; }
[ "$PORT" = "58080" ] && pass "默认端口 58080" || fail "端口是 $PORT，应为 58080"

STATE=$(get /api/state)
echo "$STATE" | json "d['status']['version']"
if [ -n "$OLD_APK" ]; then
  COST=$(echo "$STATE" | json "[s.get('costPrice') for s in d['stocks'] if s['code']=='sh600519'][0]")
  [ "$COST" = "1400.0" ] || [ "$COST" = "1400" ] && pass "升级后持仓保留" || fail "升级后持仓丢失：$COST"
fi

# 关闭首次引导
key KEYCODE_BACK

# ---------- Web API 与记账 ----------
post /api/settings '{"guideShown":true,"alertTradingOnly":false,"refreshSec":3,"layoutId":"4","displayMode":"MIXED","screenMargin":16}' > /dev/null
for body in \
  '{"code":"600519","costPrice":1400,"shares":100,"targetHigh":99999,"targetLow":1}' \
  '{"code":"000001","alias":"涨5%d测试","costPrice":10,"shares":1000}' \
  '{"code":"300750"}' '{"code":"sh000001"}' '{"code":"sz399006"}' \
  '{"code":"601318"}' '{"code":"600036"}' '{"code":"000858"}' '{"code":"510300"}'; do
  post /api/stocks "$body" > /dev/null
done

BUY=$(post /api/stocks/trade '{"code":"600519","action":"BUY","price":1600,"shares":100}')
SHARES=$(get /api/state | json "[s.get('shares') for s in d['stocks'] if s['code']=='sh600519'][0]")
COST=$(get /api/state | json "[s.get('costPrice') for s in d['stocks'] if s['code']=='sh600519'][0]")
[ "$SHARES" = "200" ] && [ "$COST" = "1500.0" ] && pass "加仓摊薄成本" || fail "加仓结果不对：$BUY shares=$SHARES cost=$COST"
post /api/stocks/trade '{"code":"600519","action":"CLEAR","price":1600}' > /dev/null
REALIZED=$(get /api/state | json "[s.get('realizedProfit') for s in d['stocks'] if s['code']=='sh600519'][0]")
[ "$REALIZED" = "20000.0" ] && pass "清仓计入已实现" || fail "已实现盈亏不对：$REALIZED"
post /api/stocks/trade/undo '{"code":"600519"}' > /dev/null
SHARES=$(get /api/state | json "[s.get('shares') for s in d['stocks'] if s['code']=='sh600519'][0]")
[ "$SHARES" = "200" ] && pass "撤销记账" || fail "撤销后股数不对：$SHARES"

HTTP=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -d '{}' "http://127.0.0.1:$PORT/api/logs/clear")
[ "$HTTP" = "403" ] && pass "无客户端头的写请求被拒绝" || fail "CSRF 防护异常：$HTTP"
HTML=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "http://127.0.0.1:$PORT/")
[ "$HTML" = "200" ] && pass "后台页面可访问" || fail "后台页面异常：$HTML"

sleep 12
ERROR=$(get /api/state | json "d['status']['lastError']")
QUOTED=$(get /api/state | json "sum(1 for s in d['stocks'] if s.get('quote'))")
[ "$ERROR" = "None" ] && [ "$QUOTED" -ge 5 ] && pass "真实行情已获取（$QUOTED 只）" || echo "::warning::行情异常：error=$ERROR quoted=$QUOTED"

# ---------- 界面截图 ----------
sleep 8
shot 02-grid4-mixed
post /api/settings '{"layoutId":"1+5"}' > /dev/null; sleep 6; shot 03-layout-1plus5
post /api/settings '{"layoutId":"8","displayMode":"TEXT"}' > /dev/null; sleep 5; shot 04-grid8-text
post /api/settings '{"layoutId":"4","displayMode":"KLINE"}' > /dev/null; sleep 8; shot 05-grid4-kline
post /api/settings '{"layoutId":"16","displayMode":"MINUTE"}' > /dev/null; sleep 8; shot 06-grid16-minute
post /api/settings '{"layoutId":"4","displayMode":"MIXED"}' > /dev/null; sleep 5

# ---------- 遥控器 ----------
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_DOWN
shot 07-focus-moved
key KEYCODE_DPAD_CENTER
sleep 3
shot 08-zoom-detail
key KEYCODE_DPAD_DOWN
sleep 4
shot 09-zoom-kline
key KEYCODE_BACK
sleep 1
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep 2
shot 10-quick-menu
key KEYCODE_BACK
key KEYCODE_MENU
sleep 2
shot 11-settings
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12-settings-changed
key KEYCODE_BACK
sleep 1

# ---------- 提醒 ----------
post /api/settings '{"layoutId":"4","displayMode":"MIXED"}' > /dev/null
post /api/alert/test '{"modes":["CELL","BANNER"],"direction":"UP"}' > /dev/null; sleep 1; shot 13-alert-up
post /api/alert/dismiss '{}' > /dev/null; sleep 1
post /api/alert/test '{"modes":["FULLSCREEN"],"direction":"DOWN"}' > /dev/null; sleep 1; shot 14-alert-fullscreen
key KEYCODE_DPAD_CENTER
sleep 1
shot 15-alert-dismissed

# ---------- 主题 ----------
post /api/settings '{"theme":"LIGHT"}' > /dev/null; sleep 3; shot 16-theme-day
post /api/settings '{"theme":"NIGHT"}' > /dev/null; sleep 3; shot 17-theme-night
post /api/settings '{"theme":"DARK","privacy":true}' > /dev/null; sleep 3; shot 18-privacy
post /api/settings '{"privacy":false,"sortMode":"ACTIVE"}' > /dev/null; sleep 3
post /api/settings '{"sortMode":"MANUAL"}' > /dev/null

# ---------- 稳定性 ----------
sleep 5
adb shell pidof "$PKG" > /dev/null && pass "进程存活" || fail "进程已退出"
CRASH=$(adb logcat -d -b crash | grep -c "$PKG" || true)
[ "$CRASH" = "0" ] && pass "没有崩溃" || { fail "检测到崩溃"; adb logcat -d -b crash | tail -n 60; }
LOG_ERRORS=$(get '/api/logs?level=E&limit=20')
COUNT=$(echo "$LOG_ERRORS" | json "len(d)")
[ "$COUNT" = "0" ] && pass "日志没有错误" || { fail "日志有 $COUNT 条错误：$(echo "$LOG_ERRORS" | head -c 1500)"; }
get '/api/logs?level=D&limit=2000' | json "'\n'.join(e['level']+'/'+e['tag']+': '+e['msg'] for e in reversed(d))" > "$OUT/app-log.txt" || true
adb logcat -d -t 400 > "$OUT/logcat.txt" 2>&1 || true

[ "$FAILED" = "0" ] && echo "冒烟测试全部通过" || echo "冒烟测试有失败项"
exit "$FAILED"
