#!/usr/bin/env bash
#
# 打包 andee 并装到平板。
#
# 一把过：查设备 → 验后端连不连得通 → 打包 → 装对应架构的包。
#
#   ./build-and-install.sh                    # 用 local.properties 里的配置
#   ./build-and-install.sh 192.168.1.5        # 顺手把后端地址改成这个再打
#
# 后端地址写在 local.properties，所以这个脚本不猜任何东西 —— 它只负责把那份
# 配置打进去、装上去，然后把「平板能不能连到后端」这件事先验掉。装完才发现连不上
# 是最浪费时间的，所以那一步放在最前面。
set -euo pipefail

cd "$(dirname "$0")"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/corretto-17.0.13/Contents/Home}"
export JAVA_HOME

PROPS=local.properties

# ---- 0. 可选：改后端地址 --------------------------------------------------
if [ $# -ge 1 ]; then
  domain="$1"
  # 回环不带端口的话补上 8080。
  #
  # **端口必须写进域名里**，因为 app 是把 DOMAIN 直接拼进 URL 的
  # （VoiceConfig.codeFlyingBaseUrl = "http://" + DOMAIN），不带端口就等于 80。
  # 而 80 上什么都没有，报出来的是一句「无法连接」，看不出是端口的事。踩过。
  case "$domain" in
    *:*) ;;
    127.0.0.1|localhost) domain="${domain}:8080" ;;
  esac
  sed -i '' -E "s|^CODEFLYING_DOMAIN=.*|CODEFLYING_DOMAIN=${domain}|" "$PROPS"
  sed -i '' -E "s|^CODEFLYING_SSL_ENABLED=.*|CODEFLYING_SSL_ENABLED=false|" "$PROPS"
  echo "后端地址已改为 ${domain}（SSL 关闭）"
fi

host=$(grep -E '^CODEFLYING_DOMAIN=' "$PROPS" | cut -d= -f2)
ssl=$(grep -E '^CODEFLYING_SSL_ENABLED=' "$PROPS" | cut -d= -f2)
hostonly=${host%%:*}
key=$(grep -E '^CODEFLYING_KEY=' "$PROPS" | cut -d= -f2)

# 端口和 scheme 只在探测连通性时用得上。
#
# **不要给真实域名默认 8080。** 这里原来写死了 ${port:-8080} —— 那是为
# `adb reverse`（回环 + 8080）准备的，用到真域名上就把 http://host 探成了
# host:8080，永远不通，而报出来的是一句「平板连不到这台机器」，指向网络。
# 真域名不带端口就是走默认端口（http 80 / https 443）。
scheme=$([ "$ssl" = "true" ] && echo https || echo http)
probe="${scheme}://${host}"
if [ "$hostonly" = "127.0.0.1" ] || [ "$hostonly" = "localhost" ]; then
  revport="${host##*:}"
  [ "$revport" = "$hostonly" ] && revport=8080
fi

echo "════════════════════════════════════════"
echo " 后端  ${probe}"
echo " 令牌  ${key:0:12}…"
echo "════════════════════════════════════════"
echo

# ---- 1. 设备 --------------------------------------------------------------
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "✗ 没有设备。插上 USB，或者 adb connect <平板IP>:5555"
  exit 1
fi
model=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
abi=$("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
echo "设备  ${model}  (${abi})"

# ---- 2. 后端地址是回环的话，先建 USB 反向转发 ------------------------------
# 平板和电脑不在同一个网的时候用这个：adbd 把平板的 127.0.0.1:PORT 转回这台机器，
# 整条路走 USB，不经过任何网络。所以两边在什么网段都无所谓。
#
# 无线方案在这里没用 —— reverse 要的是一条稳定的 adb 通道，而无线 adb 本身
# 就要求同网，那就回到原问题了。
if [ -n "${revport:-}" ]; then
  echo -n "转发  USB ${revport} → 本机 ${revport} … "
  if "$ADB" reverse "tcp:${revport}" "tcp:${revport}" >/dev/null 2>&1; then
    echo "通"
  else
    echo "失败 —— USB 没插或者 adb 掉了"
    exit 1
  fi
fi

# ---- 3. 平板能不能连到后端 ------------------------------------------------
# 先验这个。打包两分钟、装包一分钟，装完才发现网不通是最亏的。
echo -n "连通  ${probe} … "
if "$ADB" shell "curl -s -o /dev/null -w '%{http_code}' -m 8 '${probe}/'" 2>/dev/null | grep -qE '^[0-9]'; then
  echo "通"
else
  echo "不通"
  echo
  echo "  平板连不到这台机器。常见原因："
  echo "   · 不在同一个 WiFi（平板插着 USB 不代表网络也在一起）"
  echo "   · macOS 防火墙拦了 java"
  echo "   · baas 没在跑"
  echo
  echo "  平板自己的地址："
  "$ADB" shell ip -4 addr show 2>/dev/null | grep 'inet ' | grep -v 127.0.0.1 | sed 's/^/    /' || true
  echo
  read -rp "  还是继续打包？[y/N] " ans
  [ "$ans" = "y" ] || exit 1
fi

# ---- 4. 打包 --------------------------------------------------------------
echo -n "打包  "
./gradlew assembleDebug -q 2>&1 | grep -E "error:|FAILED" && { echo "✗ 打包失败"; exit 1; }
echo "完成"

apk="app/build/outputs/apk/debug/app-${abi}-debug.apk"
[ -f "$apk" ] || apk=$(ls app/build/outputs/apk/debug/app-*-debug.apk 2>/dev/null | head -1)
echo "产物  $apk"

grep -E 'CODEFLYING_(DOMAIN|SSL)' app/build/generated/source/buildConfig/debug/net/kuafuai/andee/BuildConfig.java \
  | sed 's/^/      /'

# ---- 5. 安装 --------------------------------------------------------------
# 从 IDEA 或别的机器装过的包签名可能对不上，装不上就卸了重装。
# 卸载会清掉聊天记录和小本本 —— 测试环境没关系，所以这里不拦。
echo -n "安装  "
out=$("$ADB" install -r "$apk" 2>&1 || true)

if echo "$out" | grep -q "Success"; then
  echo "完成（覆盖安装，数据保留）"
elif echo "$out" | grep -qE "no devices|device offline|device unauthorized"; then
  # 设备掉线。这一步是独立的失败，绝不能滑到下面的「卸载重装」去 ——
  # 那会白白清掉平板上的聊天记录和小本本。踩过。
  echo "✗ 设备掉线了（USB 松了？锁屏了？）"
  echo "  $out"
  exit 1
elif echo "$out" | grep -q "signatures do not match"; then
  echo "签名对不上 —— 卸了重装（聊天记录和小本本会清）"
  "$ADB" uninstall net.kuafuai.andee >/dev/null 2>&1 || true
  "$ADB" install "$apk" 2>&1 | tail -1
else
  echo "✗ 装不上"
  echo "  $out"
  exit 1
fi

echo
echo "装好了。语音那条链路是走 ${host} 的 —— 记得那边的 baas 在跑、且是最新代码。"
