# AIFactory 回归台账

> 每条都是**真实发生过**的缺陷。修完不许删，只许标“已加闸门”。
> `scripts/regression-guard.sh` 会在构建前检查下列断言的代码是否存在，缺一条就失败。
> 新增缺陷必须补一条记录 + 至少一条 guard，否则下一版还可能把它吃掉。

| ID | 症状（用户看到什么） | 根因 | 修复版本 | 闸门（guard 检查的断言） |
|---|---|---|---|---|
| R-01 | `minSdkVersion=未声明（至少要 21）`，产物被丢弃（0.6.1→0.9.5 反复出现） | 自检正则只认 `sdkVersion:'26'`，而 aapt2 打的是 `minSdkVersion:'26'`；且 `aapt2 link` 不读清单里的 min，只认 `--min-sdk-version` | 0.9.6 | `SelfTest.kt` 含 `minSdkVersion:'`；`Packager.kt` 含 `--min-sdk-version 26` |
| R-02 | 构建日志 `not well-formed (invalid token)` at manifest line N | 用字符串在 `<uses-sdk>` 里插属性，遇空值/重复属性就写坏 XML | 0.9.5 | `Packager.kt` 含 `xmlOk(` 与 `return@runCatching`（校验失败即回滚）；含 `aifactory-bak` |
| R-03 | 清单被写坏后再也构建不通过 | 改写前没备份，坏文件无从恢复 | 0.9.4/0.9.5 | 同上，含 `.aifactory-bak` 恢复分支 |
| R-04 | 模型说“已执行”，但 `工具调用 0 次`，文件/终端没动 | 模型把调用写在正文（`🔧 list_dir {...}`），长度 < 40 字绕过零工具拦截，被当最终答复 | 0.9.2 | `AgentEngine.kt` 含 `looseToolCall(` 与 `【未执行】` |
| R-05 | 终端页点不出键盘、输入被吞 | 文本选择模式吞 tap；首次 `showSoftInput` 被窗口焦点吃掉 | 0.9.0 | `TerminalScreen.kt` 含 `stopTextSelectionMode(` 与 `输入行` |
| R-06 | 设置页 GitHub 卡按钮灰、无入口、仓库框只有占位文案 | 只有“要 Token 才能建仓”的按钮，Token 从哪来没入口 | 0.9.2/0.9.3 | `MainActivity.kt` 含 `打开 Token 页`、`连接并检查`、`从剪贴板粘贴` |
| R-07 | `read_file {"path":"app/build.gradle"}` 报 `projects/app/app/...` 不存在 | `resolve()` 把当前项目名当相对路径前缀又拼一次（双前缀） | 0.9.0 | `AgentEngine.kt` 含 `currentProject` 判断同名不加前缀 |
| R-08 | `ls` 报 `No such file`，命令里出现 `.\ /app\ /` | 模型输出 `\ ` / `\/` 转义，shell 拆成多个参数 | 0.9.0 | `AgentEngine.kt` 含 `已清理命令` |
| R-09 | 构建失败却打 `✅ 构建失败（退出码 1）` | `Pipeline.ok` 只认 `startsWith("失败")` | 0.8.2 | `Pipeline.kt` 含 `未通过` 判定 |
| R-10 | 对话说构建成功，项目页/文件中心什么都没有 | 产物没归档、只给相对路径 | 0.8.0 | `Pipeline.kt`/`Packager.kt` 含 `产物已存档` 或 `files/outputs` |
| R-11 | 终端页看不到 Agent 执行的命令（两套 PTY） | `run_shell` 走私有 bash | 0.9.1 | `SessionHub.kt` 存在；`AgentEngine.kt` 含 `SessionHub.run` |
| R-12 | “构建成功”就宣称测试通过，实际没启动 | 没有启动闸门 | 0.8.3 | `AgentEngine.kt` 含 `launch_app`；`VerifyRunner.kt` 含 `verify.json` |
| R-13 | `apt install android-tools` 报 `Unable to locate package` | 本地 apt 源只有 261 包，不含 android-tools（需 CI 用本前缀编译） | 未修（待 runtime workflow） | `AdbBridge.kt` 存在；设置页 AdbCard 给出“必须在 CI 编”的说明 |
| R-14 | 长按项目名没有删除入口（只能先选中再点底部按钮，小屏会被挤） | 项目 chip 用 `FilterChip`，不支持长按 | 0.9.7 | `ProjectsScreen.kt` 含 `combinedClickable` 与 `长按项目名` |
| R-15 | 项目页按钮「构建 Release（签名）」被挤成三行，第三个按钮被右边缘切掉、后两个在屏外 | 7 个按钮塞进一个不可滚动的 `Row`，窄屏下换行并横向溢出（真机上滑一下能看到上半截，是滚动位置，不是纵向裁切——这点我一度误判，已更正） | 0.9.8 | `ProjectsScreen.kt` 含 `horizontalScroll(rememberScrollState())` 与 `softWrap = false` |
| R-16 | 长任务跑到一半模型 429 掉线，任务中断（`模型 API 错误(HTTP 429)`，10 次请求 9 次工具） | 只做主通道短退避，没有备用通道，也没有断点提示 | 0.9.8 | `AgentEngine.kt` 含 `hasBackup` 与 `已切到备用通道`；`MainActivity.kt` 含 `备用模型通道` |
| R-16 | 长任务跑到一半模型 429 掉线，整轮中断（`模型 API 错误(HTTP 429)`，10 次请求 9 次工具） | 只有主通道短退避（2s/4s/6s），无备用通道、无断点提示 | 0.9.8 | `AgentEngine.kt` 含 `已切到备用通道`、`hasBackup`；`MainActivity.kt` 含 `备用模型通道` |
| R-17 | 每次切回对话页，列表从第 1 条一路滑到底，观感像在“重放” | 进入页面就用 `animateScrollToItem(最后一条)`，且滚动位置不跨 tab 保存，回页面时 listState 从 0 开始 | 0.9.8 | `MainActivity.kt` 含 `initialFirstVisibleItemIndex = ChatRunner.scrollIndex`；`Sessions.kt` 含 `var scrollIndex` |
| R-18 | 一键发布到 GitHub 只传了 APK，AAB（上架包）留在设备里要手工搬 | `Publisher.publish` 无 AAB 分支 | 0.9.9 | `Publisher.kt` 含 `app/build/outputs/bundle/app-release.aab` |
| R-19 | Agent 想产 AAB 却在终端手拼 `aapt2 link`，报 `failed to open magic from input: Is a directory` / `failed parsing overlays`；又 `cat .aifactory-build.sh` 报 No such file | 工具表里根本没有 `build_aab`，模型只能即兴拼命令；且脚本实际在 `<项目>/app/.aifactory-build.sh`，少一层 `app/` | 0.10.0 | `AgentEngine.kt` 含 `ToolDef("build_aab"`；`AabBuilder.kt` 用 `-R res.zip` |
| R-20 | 「备用通道」放在设置页，用户在「模型设置」里找不到，以为根本没有多模型切换 | 入口位置与用户心智不一致（多模型属于模型设置，不属于系统设置） | 0.10.1 | `MainActivity.kt` 模型设置弹层含 `备用 API Base URL` 与 `硅基 GLM-Z1-9B` 预设 |
| R-21 | 429 后界面显示「2000s 后自动重试」，等于挂死 | 退避单位错：把 `2000` 当秒，又乘 1000 当毫秒 | 0.10.2 | `AgentEngine.kt` 含 `(2 * n).coerceAtMost(20)` |
| R-22 | 长任务越跑越慢、48 次请求 447s，上下文只涨不降 | `compactHistory` 只在总量>60000 字时截断工具输出，从不丢弃旧轮次，也从不给模型留下摘要 | 0.10.2 | `AgentEngine.kt` 含 `historyBudget`、`早前对话摘要` |
| R-23 | 安装器显示「0.9.9 → 0.9.9」，版本号从 0.9.8 起一直没变 | 版本号行有缩进（`versionCode = 69`），改版脚本用的 `^versionCode` 正则匹配不到，静默失效 | 0.10.3 | `app/build.gradle.kts` 含 `versionName = "0.10.3"`；`aapt2 dump badging` 实测同名 |
| R-24 | 模型设置里「备用通道」下半截被底部输入框挡住，且滑不动 | 设置卡是不带约束的 Card，整列 Column 不可滚，高度超过可用空间被裁 | 0.10.3 | `MainActivity.kt` 含 `heightIn(max = capDp)` 与 `verticalScroll(rememberScrollState())` |
| R-25 | Agent 给手工链项目引入 appcompat，下载 aar 后 `unzip` 再喂 aapt2，反复失败绕圈烧额度 | system prompt 没写「零 gradle 手工链不支持 AAR/依赖解析」，模型按常规 Android 开发习惯行事 | 0.10.4 | `AgentEngine.kt` 含 `不支持 AAR / Gradle 依赖解析` |
| R-26 | DNS 解析失败（`Unable to resolve host`，HTTP -1）时主通道直接熄火，没有切备用 | 切换条件只写了 429，网络错误与 5xx 不在可重试集合里 | 0.10.5 | `AgentEngine.kt` 含 `retryable` 与 `code == -1` |
| R-27 | 清单引用 `@mipmap/ic_launcher_round` 但文件不存在，aapt2 报 `not found` + `failed processing manifest` | 手工链没有 AGP 的默认图标；Agent 用 ImageMagick 造图标又没造 round 版本 | 0.10.5 | `Packager.kt` 含 `ensureIcons(` |
