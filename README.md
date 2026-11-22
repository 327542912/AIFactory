# AIFactory 0.2.2

Android 上的自主软件开发工作台：自带 Linux Runtime（不依赖 Termux），Agent 可读写文件、执行命令、构建并签名 APK，再唤起系统安装器安装。

## 架构

| 层 | 文件 | 作用 |
|---|---|---|
| Runtime | `RuntimeEngine.kt` `ArchiveInstaller.kt` | Termux 布局的 prefix（`files/usr`）+ home；安装 bootstrap / SDK；流式执行、超时、取消、杀进程树 |
| Agent | `AgentEngine.kt` | 模型⇄工具循环；原生 tool calling，不支持时退回 ```tool 文本协议 |
| 打包 | `Packager.kt` | Gradle 构建 → apksigner 签名（应用内生成私有密钥）→ 输出到 `files/outputs` → FileProvider + 系统安装器 |
| UI | `MainActivity.kt` `ProjectsScreen.kt` `RuntimeSetup.kt` | 对话（Agent 开关/停止）、项目与打包页、Runtime/SDK 安装 |
| 保活 | `KeepAliveService.kt` | 构建/Agent 运行期间前台服务 + WakeLock |

## 关键约束

- **targetSdk 必须是 28**。Android 10+ 禁止 targetSdk≥29 的应用执行私有目录里的二进制，Termux 本身也是这样做的。代价：不能上架 Google Play，仅侧载。
- Runtime 必须用包名 `com.aifactory.app` 重新编译。Termux 官方二进制里写死了 `/data/data/com.termux/...`。
- bootstrap **不能**使用 `--android10`（该模式没有 apt/dpkg，改用 proot，只适用于 targetSdk≥29）。

## 第一次使用

1. 在 GitHub 运行 *Build ARM64 Runtime* 工作流（默认带 `openjdk-21,gradle,aapt2,apksigner,git,zip,unzip`，耗时较长）。完成后下载地址固定为  
   `https://github.com/<用户>/<仓库>/releases/download/runtime-latest/bootstrap-aarch64.zip`
2. 打开 AIFactory → 设置 → Runtime：填入地址，下载并安装（或选择本地 zip）。
3. 安装 Android SDK：把已有的 `android-sdk`（含 `platforms/android-34`、`build-tools`）打成 zip，选择本地文件导入。
4. 对话页填写模型 API，打开 Agent，描述需求；或在「项目」页导入源码包后手动构建。

## 首次编译 AIFactory 自身

仍需一次引导构建（鸡生蛋）：在任何有 Gradle 8.7 + JDK 17+ 的环境执行 `./build_termux.sh`。之后 AIFactory 可以在自己的 Runtime 里构建并升级自己。

## 安装第三方软件

软件源已指向本项目自建的 apt 仓库（工作流发布到 `runtime-repo`）。在终端页或对话里直接：`pkg install python`、`pip install xxx`、`npm i -g xxx`。仓库里没有的包，把包名加入工作流 `repo_packages` 再运行一次即可。

## 已知限制

- 未在真机上验证：bootstrap 第二阶段、Termux 二进制在 targetSdk 28 下的 execve、openjdk 在自定义前缀下的运行。
- 官方 Termux 仓库的包不能直接 `pkg install`（前缀不同）；需要的包请加入工作流的 `extra_packages`。
- 覆盖安装要求签名一致：debug 与应用内 release 密钥不同。
- 同一用户 0 之外（多用户/工作资料）不支持。
- API Key 保存在应用私有存储（未加密）。
