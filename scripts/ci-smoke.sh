#!/usr/bin/env bash
# 在 Android TV 模拟器上做冒烟测试：升级路径、Web API、遥控器操作、各界面截图、崩溃检查。
# 用法：ci-smoke.sh <新版本 APK> [旧版本 APK] [当前代码的低版本构建]
set -u

NEW_APK="$1"
OLD_APK="${2:-}"
PROBE_APK="${3:-}"
PKG=io.github.yixuexiaoao.tvstock
OUT=smoke
FAILED=0
PORT=""
mkdir -p "$OUT"
REAL_ADB=$(command -v adb)

# 进度带耗时并写入文件：即使中途卡住被杀，也能看出停在哪一步
note() { echo "[$(printf '%4d' "$SECONDS")s] $*" | tee -a "$OUT/progress.txt" >&2; }

# adb 偶尔会在模拟器上卡死且不会自己退出，每条命令都限时
adb() {
  local limit=45 code
  [ "${1:-}" = "install" ] && limit=150
  timeout "$limit" "$REAL_ADB" "$@"
  code=$?
  [ "$code" = "124" ] && note "adb $1 超时（${limit} 秒）"
  return "$code"
}

finish() {
  trap - EXIT TERM
  note "脚本结束"
  adb logcat -d -t 400 > "$OUT/logcat.txt" 2>&1 || true
  adb logcat -d -b crash > "$OUT/crash.txt" 2>&1 || true
}
trap finish EXIT
trap 'echo "::error::冒烟测试整体超时"; note "整体超时"; exit 124' TERM

install_apk() {
  note "安装 $1"
  adb install -r "$1" && return 0
  note "安装失败，等待设备后重试"
  adb wait-for-device
  sleep 5
  adb install -r "$1"
}

fail() { echo "::error::冒烟测试失败：$*"; note "失败：$*"; FAILED=1; }
pass() { note "通过：$*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; note "截图：$1"; }
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
# 按数值比较：接口可能返回 1500 或 1500.0
num_eq() { python3 -c "import sys; sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<1e-6 else 1)" "$1" "$2" 2>/dev/null; }
# json <表达式>，从标准输入读 JSON，d 是解析结果
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

web_up() { curl -fsS -m 5 "http://127.0.0.1:$PORT/api/meta" > /dev/null 2>&1; }

wait_update_state() {
  local want="$1" state="" deadline=$((SECONDS + 120))
  while [ "$SECONDS" -lt "$deadline" ]; do
    state=$(get /api/state 2> /dev/null | json "d['status']['update']['state']" 2> /dev/null)
    case " $want " in *" $state "*) [ -n "$state" ] && { echo "$state"; return 0; } ;; esac
    # 调起安装器或授权页后 App 退到后台，Web 服务随之关闭；回到 App 再查
    [ -z "$state" ] && launch 3
    sleep 3
  done
  echo "$state"
  return 1
}

top_activity() { adb shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | tr -d '\r'; }
installed_version() { adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r' | cut -d= -f2; }

# 点系统安装窗口的确认按钮（标准对话框的 button1），找不到时退回按键
confirm_installer() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb exec-out cat /sdcard/ui.xml > "$OUT/installer-ui.xml"
  local center
  center=$(python3 - "$OUT/installer-ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'resource-id="android:id/button1"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
print('' if not m else '%d %d' % ((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2))
PY
  )
  note "确认按钮位置：${center:-未找到}"
  if [ -n "$center" ]; then
    # shellcheck disable=SC2086
    adb shell input tap $center
  else
    key KEYCODE_DPAD_RIGHT
    key KEYCODE_DPAD_CENTER
  fi
}

# 模拟器刚启动时网络可能还没好，失败时重试，避免把下载验证跳过去
check_update() {
  local state="" attempt
  for attempt in 1 2 3; do
    post /api/update/check '{}' > /dev/null
    state=$(wait_update_state "AVAILABLE UP_TO_DATE ERROR")
    [ "$state" != "ERROR" ] && break
    note "检查更新第 $attempt 次出错"
    sleep 10
  done
  echo "$state"
}

# ---------- 下载链路：用当前代码的低版本构建，真实下载一个已发布版本 ----------
if [ -n "$PROBE_APK" ] && [ -f "$PROBE_APK" ]; then
  install_apk "$PROBE_APK" || fail "低版本构建安装失败"
  launch
  if find_port; then
    post /api/settings '{"updateChannel":"STABLE"}' > /dev/null
    state=$(check_update)
    echo "低版本构建检查更新结果：$state"
    if [ "$state" = "AVAILABLE" ]; then
      post /api/update/install '{}' > /dev/null
      state=$(wait_update_state "READY ERROR")
      [ "$state" = "READY" ] && pass "当前代码下载并校验通过" || fail "当前代码下载失败：$state"
      # READY 后 App 会调起安装器并退到后台，先回到 App 才能读日志
      sleep 3
      shot 00-update-probe
      # 没有授权时也应该由系统接管（安装器的授权引导），不能停在 App 里只给提示
      TOP=$(top_activity)
      note "下载后的前台界面：$TOP"
      case "$TOP" in
        ""|*"$PKG/"*) fail "下载后没有弹出系统安装界面：$TOP" ;;
        *) pass "下载后自动弹出系统界面" ;;
      esac
      key KEYCODE_BACK
      launch 4
      get '/api/logs?level=D&limit=200' | json "'\n'.join(e['msg'] for e in reversed(d) if e['tag']=='Update')" > "$OUT/update-log.txt" || true
      cat "$OUT/update-log.txt"
      # API 被限流时回退到 github.com 是预期行为，只提示不判失败
      if grep -q '来源 api.github.com' "$OUT/update-log.txt"; then
        pass "下载走 API 资源地址"
      else
        echo "::warning::下载没有走 API 资源地址（可能是接口限流后回退）"
      fi
      # 已授权：走完弹窗、确认、覆盖安装（相当于用户在授权页打开开关之后）
      BEFORE=$(installed_version)
      adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
      post /api/update/install '{}' > /dev/null
      sleep 5
      shot 00b-installer-confirm
      TOP=$(top_activity)
      note "已授权时的前台界面：$TOP"
      case "$TOP" in
        *packageinstaller*) pass "已授权时直接弹出安装窗口" ;;
        *) fail "已授权时没有弹出安装窗口：$TOP" ;;
      esac
      confirm_installer
      AFTER="$BEFORE"
      for _ in $(seq 1 20); do
        sleep 3
        AFTER=$(installed_version)
        [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ] && break
      done
      shot 00c-after-install
      if [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ]; then
        pass "App 内更新完成：$BEFORE -> $AFTER"
      else
        fail "确认后版本没有变化：$BEFORE -> $AFTER"
      fi
    elif [ "$state" = "ERROR" ]; then
      echo "::warning::检查更新出错（可能是接口限流）"
    else
      fail "低版本构建没有发现新版本：$state"
    fi
  else
    fail "低版本构建 Web 服务不可访问"
  fi
  # 上面可能已经更新到最新正式版，版本号高于下面的旧版本，要卸载才能继续
  adb uninstall "$PKG" > /dev/null
fi

# ---------- 升级路径：先装上一个已发布版本，走一遍真实的检查更新和下载 ----------
if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ]; then
  install_apk "$OLD_APK" || fail "旧版本安装失败"
  launch
  if find_port; then
    OLD_VERSION=$(get /api/state | json "d['status']['version']")
    pass "旧版本 $OLD_VERSION 运行在端口 $PORT"
    post /api/stocks '{"code":"600519","costPrice":1400,"shares":100,"targetHigh":99999}' > /dev/null
    post /api/settings '{"updateChannel":"PRE"}' > /dev/null
    state=$(check_update)
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
install_apk "$NEW_APK" || { fail "新版本安装失败"; exit 1; }
launch 8
shot 01-first-run-guide
find_port || { fail "Web 服务不可访问"; adb logcat -d -b crash; exit 1; }
[ "$PORT" = "58080" ] && pass "默认端口 58080" || fail "端口是 $PORT，应为 58080"

STATE=$(get /api/state)
echo "$STATE" | json "d['status']['version']"
if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ]; then
  COST=$(echo "$STATE" | json "[s.get('costPrice') for s in d['stocks'] if s['code']=='sh600519'][0]")
  num_eq "$COST" 1400 && pass "升级后持仓保留" || fail "升级后持仓丢失：$COST"
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
num_eq "$SHARES" 200 && num_eq "$COST" 1500 && pass "加仓摊薄成本" || fail "加仓结果不对：$BUY shares=$SHARES cost=$COST"
post /api/stocks/trade '{"code":"600519","action":"CLEAR","price":1600}' > /dev/null
REALIZED=$(get /api/state | json "[s.get('realizedProfit') for s in d['stocks'] if s['code']=='sh600519'][0]")
num_eq "$REALIZED" 20000 && pass "清仓计入已实现" || fail "已实现盈亏不对：$REALIZED"
post /api/stocks/trade/undo '{"code":"600519"}' > /dev/null
SHARES=$(get /api/state | json "[s.get('shares') for s in d['stocks'] if s['code']=='sh600519'][0]")
num_eq "$SHARES" 200 && pass "撤销记账" || fail "撤销后股数不对：$SHARES"

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
# 焦点先在标签上：下键进入第一项，左右键改值，上键回到标签，右键切换标签
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12-settings-changed
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12a-settings-look
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12b-settings-market
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12c-settings-alert
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12d-settings-stocks
key KEYCODE_DPAD_RIGHT
sleep 1
shot 12e-settings-system
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

# ---------- 美股 ----------
market() { get /api/state | json "d['settings']['market']"; }
SEARCH=$(get '/api/search?q=AAPL')
case "$SEARCH" in *usAAPL*) pass "搜索到美股" ;; *) echo "::warning::美股搜索无结果：$SEARCH" ;; esac
post /api/settings '{"market":"US","layoutId":"4","displayMode":"MIXED"}' > /dev/null
post /api/stocks '{"code":"BRK.B","costPrice":500,"shares":10,"targetHigh":99999}' > /dev/null
sleep 12
US_COUNT=$(get /api/state | json "sum(1 for s in d['stocks'] if s['code'].startswith('us'))")
[ "$US_COUNT" -ge 5 ] && pass "首次切到美股自动添加示例" || fail "美股股票数量不对：$US_COUNT"
US_QUOTED=$(get /api/state | json "sum(1 for s in d['stocks'] if s['code'].startswith('us') and s.get('quote'))")
[ "$US_QUOTED" -ge 4 ] && pass "真实美股行情已获取（$US_QUOTED 只）" || echo "::warning::美股行情异常：$US_QUOTED"
get /api/state | json "[(s['code'], s['quote']['price'], s['quote']['time']) for s in d['stocks'] if s['code'].startswith('us') and s.get('quote')]"
get /api/state | json "d.get('portfolioUs')" | grep -q marketValue && pass "美股持仓单独汇总" || fail "没有美股持仓汇总"
shot 19-us-grid
post /api/settings '{"displayMode":"KLINE"}' > /dev/null; sleep 8; shot 20-us-kline
post /api/settings '{"displayMode":"TEXT"}' > /dev/null; sleep 4; shot 21-us-text
post /api/settings '{"displayMode":"MINUTE","layoutId":"1+5"}' > /dev/null; sleep 8; shot 22-us-minute
post /api/settings '{"displayMode":"MIXED","layoutId":"4"}' > /dev/null; sleep 4
key KEYCODE_DPAD_CENTER
sleep 3
shot 23-us-zoom
key KEYCODE_BACK
sleep 1
# 遥控器切换：上键到顶部（4 画面最多两行，多按一次也停在顶部），左键移到 A股 标签，确认
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_LEFT
sleep 1
shot 24-market-tabs-focus
key KEYCODE_DPAD_CENTER
sleep 3
[ "$(market)" = "CN" ] && pass "顶部标签切到 A 股" || fail "顶部标签切换失败：$(market)"
shot 25-back-to-cn
key KEYCODE_STAR
sleep 3
[ "$(market)" = "US" ] && pass "* 键切到美股" || fail "* 键切换失败：$(market)"
post /api/settings '{"market":"CN"}' > /dev/null
sleep 2

# ---------- 稳定性 ----------
sleep 5
adb shell pidof "$PKG" > /dev/null && pass "进程存活" || fail "进程已退出"
LOG_ERRORS=$(get '/api/logs?level=E&limit=20')
COUNT=$(echo "$LOG_ERRORS" | json "len(d)")
[ "$COUNT" = "0" ] && pass "日志没有错误" || { fail "日志有 $COUNT 条错误：$(echo "$LOG_ERRORS" | head -c 1500)"; }

# ---------- Web 服务随界面启停（放在最后，退出后接口就不可用了）----------
key KEYCODE_MENU
sleep 2
web_up && pass "进入设置页时 Web 服务继续运行" || fail "进入设置页后 Web 服务停止"
key KEYCODE_BACK
sleep 1
key KEYCODE_HOME
sleep 3
web_up && fail "按主页键后 Web 服务仍在运行" || pass "按主页键后 Web 服务关闭"
launch 4
web_up && pass "回到 App 后 Web 服务恢复（端口 $PORT）" || fail "回到 App 后 Web 服务未在端口 $PORT 恢复"
get '/api/logs?level=D&limit=2000' | json "'\n'.join(e['level']+'/'+e['tag']+': '+e['msg'] for e in reversed(d))" > "$OUT/app-log.txt" || true
# 同一条命令发两次，保证落在“再按一次退出”的 2 秒窗口内
adb shell input keyevent KEYCODE_BACK KEYCODE_BACK
sleep 3
web_up && fail "退出后 Web 服务仍在运行" || pass "退出后 Web 服务关闭"
SERVICES=$(adb shell dumpsys activity services "$PKG" | grep -c ServiceRecord || true)
[ "$SERVICES" = "0" ] && pass "没有常驻服务" || fail "仍有 $SERVICES 个服务在运行"
CRASH=$(adb logcat -d -b crash | grep -c "$PKG" || true)
[ "$CRASH" = "0" ] && pass "没有崩溃" || { fail "检测到崩溃"; adb logcat -d -b crash | tail -n 60; }

[ "$FAILED" = "0" ] && echo "冒烟测试全部通过" || echo "冒烟测试有失败项"
exit "$FAILED"
