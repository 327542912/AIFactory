#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

command -v gradle >/dev/null 2>&1 || { echo "错误：未找到 gradle。请使用现有 Termux Gradle 安装。"; exit 1; }
command -v java >/dev/null 2>&1 || { echo "错误：未找到 Java。"; exit 1; }

JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 17 ]; then
  echo "错误：需要 JDK 17 或更高版本；当前 Java 主版本：$JAVA_MAJOR"
  exit 1
fi

echo "AIFactory 0.2.0 bootstrap build"
echo "Project: $ROOT"
echo "Java: $(java -version 2>&1 | head -n 1)"
echo "Gradle: $(gradle --version | grep '^Gradle ' | head -n 1)"

gradle assembleDebug --no-daemon --max-workers=2
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -s "$APK" ] || { echo "构建结束但 APK 不存在：$APK"; exit 1; }
echo "构建成功：$APK"
ls -lh "$APK"
