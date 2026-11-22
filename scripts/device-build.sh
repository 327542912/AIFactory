#!/data/data/com.aifactory.app/files/usr/bin/bash
# AIFactory P0：设备端「构建 → 安装 → 启动 → 崩溃检查」闭环脚本
# 用法：bash scripts/device-build.sh [项目目录] [包名/.Activity]
set -uo pipefail

PREFIX="${PREFIX:-/data/data/com.aifactory.app/files/usr}"
export PATH="$PREFIX/bin:$PATH"
export HOME="${HOME:-/data/data/com.aifactory.app/files/home}"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"

PKG="${2:-com.aifactory.app}"
ACTIVITY="${ACTIVITY:-$PKG/.MainActivity}"
PROJ="${1:-$(pwd)}"
LOG="$HOME/.aifactory/logs"
mkdir -p "$LOG"
LOG="$LOG/device-build-$(date +%Y%m%d-%H%M%S).log"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  ✓ %s\n' "$*"; }
bad() { printf '  ✗ %s\n' "$*"; }
die() { bad "$*"; echo "日志：$LOG"; exit 1; }

exec > >(tee -a "$LOG") 2>&1
say "AIFactory device build — $(date)"
echo "  prefix=$PREFIX"
echo "  home=$HOME"
echo "  sdk=$SDK"
echo "  project=$PROJ"

# ---------- 1. 前置检查 ----------
say "1/5 检查工具链"
command -v java   >/dev/null || die "缺少 java：pkg install openjdk-21"
command -v gradle >/dev/null || die "缺少 gradle：pkg install gradle"
JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 17 ] || die "java 版本过低（需要 ≥17）：$JAVA_MAJOR"
ok "java $JAVA_MAJOR / $(command -v java)"
ok "gradle $(gradle --version 2>/dev/null | sed -n 's/^Gradle //p' | head -1)"

[ -d "$SDK/platforms" ] || die "Android SDK 未安装（设置页第 3 步）"
PLATFORM=$(ls "$SDK/platforms" | sort -V | tail -1)
BT=$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] || die "SDK 里没有 build-tools"
AAPT2="$SDK/build-tools/$BT/aapt2"
[ -x "$AAPT2" ] || die "缺 aapt2：$AAPT2"
# aapt2 必须能在本机执行（官方 x86_64 包会在这里直接挂）
"$AAPT2" version >/dev/null 2>&1 || die "aapt2 无法执行（架构不对，本机需要 aarch64 版）：$AAPT2"
ok "platforms=$PLATFORM  build-tools=$BT  aapt2=$("$AAPT2" version 2>/dev/null | head -1)"

if [ -d "$SDK/platform-tools" ]; then
  export PATH="$SDK/platform-tools:$PATH"
  ok "adb=$(command -v adb || echo 未就绪)"
fi

# ---------- 2. 构建 ----------
say "2/5 构建 debug APK"
cd "$PROJ" || die "进不去项目目录：$PROJ"
[ -f settings.gradle ] || [ -f settings.gradle.kts ] || die "这里不像 Gradle 项目（缺 settings.gradle[.kts]）"

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
mkdir -p "$HOME/.gradle"
GPROPS="$HOME/.gradle/gradle.properties"
grep -q "org.gradle.jvmargs" "$GPROPS" 2>/dev/null || cat >>"$GPROPS" <<EOF
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
android.useAndroidX=true
EOF

START=$(date +%s)
gradle assembleDebug --no-daemon --max-workers=2 --console=plain || die "gradle assembleDebug 失败（往上翻编译错误）"
APK=$(find app/build/outputs/apk/debug -name '*.apk' -type f 2>/dev/null | head -1)
[ -n "$APK" ] || die "构建结束但没有 app/build/outputs/apk/debug/*.apk"
ok "产物 $APK（$(du -h "$APK" | cut -f1)）  耗时 $(( $(date +%s) - START ))s"

# ---------- 3. 验签 ----------
say "3/5 校验签名与包名"
APKSIGNER="$PREFIX/bin/apksigner"
[ -x "$APKSIGNER" ] || APKSIGNER="java -jar $SDK/build-tools/$BT/lib/apksigner.jar"
$APKSIGNER verify --print-certs "$APK" 2>&1 | grep -E "DN:|SHA-256 digest" | head -4
$APKSIGNER verify "$APK" >/dev/null 2>&1 && ok "签名有效" || bad "签名校验失败（不阻断，继续）"

# ---------- 4. 安装 ----------
say "4/5 安装到本机"
# 同一台设备上没法用 adb 装自己（adb 需要另一台机器或无线调试），优先走 Shizuku/rish
if command -v rish >/dev/null 2>&1; then
  rish -c "pm install -r -t '$APK'" && ok "已通过 Shizuku(rish) 安装" || bad "rish pm install 失败（Shizuku 是否在运行？）"
elif command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q "device$"; then
  adb install -r -t "$APK" && ok "已通过 adb 安装" || bad "adb install 失败"
else
  say "  没有可用的安装通道（需要 Shizuku/rish，或连一台电脑用 adb）。"
  echo "  手工安装：文件页 → 打开 $APK → 系统安装器"
  echo "  开启无线调试后用电脑：adb install -r -t '$APK'"
  die "跳过安装与启动验证"
fi

# ---------- 5. 启动 + 崩溃检查 ----------
say "5/5 启动并检查崩溃"
if command -v rish >/dev/null 2>&1; then
  rish -c "am start -n $PKG/$ACTIVITY" >/dev/null 2>&1 || bad "am start 失败（$PKG/$ACTIVITY）"
  sleep 6
  PID=$(rish -c "pidof $PKG" 2>/dev/null | tr -d '\r')
  if [ -n "$PID" ]; then ok "进程存活 pid=$PID"; else die "启动 6 秒后进程不存在（闪退）"; fi
  FATAL=$(rish -c "logcat -d -t 300 -v brief" 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" | head -20)
  if [ -n "$FATAL" ]; then bad "发现崩溃："; echo "$FATAL"; else ok "logcat 无 FATAL EXCEPTION"; fi
else
  say "  无 rish，启动验证跳过"
fi

say "完成。完整日志：$LOG"
