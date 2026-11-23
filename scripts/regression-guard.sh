#!/bin/sh
# 回归闸门：把“已经修过的结论”变成构建前必须存在的代码断言。
# 用法： sh scripts/regression-guard.sh   （CI 与本机都跑；缺一条即退出 1）
set -e
SRC=app/src/main/java/com/aifactory/app
fail=0
need() { # need <文件> <断言字符串> <ID>
  if grep -qF -- "$2" "$SRC/$1"; then
    echo "  ok   $3  $1  ~ $2"
  else
    echo "  FAIL $3  $1  缺少: $2"
    fail=1
  fi
}
echo "== 回归闸门 =="
need SelfTest.kt "minSdkVersion:'" "R-01"
need Packager.kt "--min-sdk-version 26" "R-01"
need Packager.kt "xmlOk(" "R-02"
need Packager.kt "aifactory-bak" "R-03"
need AgentEngine.kt "looseToolCall(" "R-04"
need AgentEngine.kt "【未执行】" "R-04"
need TerminalScreen.kt "stopTextSelectionMode(" "R-05"
need TerminalScreen.kt "输入行" "R-05"
need MainActivity.kt "打开 Token 页" "R-06"
need MainActivity.kt "连接并检查" "R-06"
need AgentEngine.kt "已清理命令" "R-08"
need Pipeline.kt "未通过" "R-09"
need AgentEngine.kt "SessionHub.run" "R-11"
need VerifyRunner.kt "verify.json" "R-12"
need AgentEngine.kt "launch_app" "R-12"
need ProjectsScreen.kt "combinedClickable" "R-14"
need ProjectsScreen.kt "长按项目名" "R-14"
need AgentEngine.kt "已切到备用通道" "R-16"
need MainActivity.kt "备用通道（主通道 429 自动切换）" "R-16"
need MainActivity.kt "initialFirstVisibleItemIndex = ChatRunner.scrollIndex" "R-17"
need Sessions.kt "var scrollIndex" "R-17"
need Publisher.kt "app/build/outputs/bundle/app-release.aab" "R-18"
need AgentEngine.kt 'ToolDef("build_aab"' "R-19"
need AgentEngine.kt '"build_aab"' "R-19"
need MainActivity.kt "备用 API Base URL" "R-20"
need MainActivity.kt "硅基 GLM-Z1-9B" "R-20"
need AgentEngine.kt "(2 * n).coerceAtMost(20)" "R-21"
need AgentEngine.kt "historyBudget" "R-22"
need AgentEngine.kt "早前对话摘要" "R-22"
VC=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)
if [ "${VC:-0}" -ge 76 ]; then
  echo "  ok   R-23  app/build.gradle.kts  ~ versionCode=$VC 已真改"
else
  echo "  FAIL R-23  app/build.gradle.kts  versionCode=$VC 没升（缩进坑）"; fail=1
fi
need MainActivity.kt "heightIn(max = capDp)" "R-24"
need AgentEngine.kt "不支持 AAR / Gradle 依赖解析" "R-25"
need AgentEngine.kt "自绘 Canvas" "R-25"
need AgentEngine.kt "code == -1" "R-26"
need Packager.kt "ensureIcons(" "R-27"
need Packager.kt "res.zip" "R-28"
need Packager.kt "--auto-add-overlay" "R-28"
need Packager.kt "fixAppCompatTheme(" "R-29"
need Packager.kt "AppCompatActivity" "R-29"
if [ "$fail" = "0" ]; then echo "== 全部通过 =="; else echo "== 有回归 =="; exit 1; fi
