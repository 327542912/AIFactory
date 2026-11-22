# AIFactory

**Android 上的自建 AI 开发工作台。不依赖 Termux，不用 proot，不复制 rootfs —— 整套 Linux 运行环境是从源码编译出来的，只把前缀里的包名换成了自己的。**

[![Runtime CI](https://github.com/327542912/AIFactory/actions/workflows/build-aifactory-runtime.yml/badge.svg?branch=master)](https://github.com/327542912/AIFactory/actions/workflows/build-aifactory-runtime.yml)
[![APK CI](https://github.com/327542912/AIFactory/actions/workflows/android-build.yml/badge.svg?branch=app-0.2.2)](https://github.com/327542912/AIFactory/actions/workflows/android-build.yml)
![arch](https://img.shields.io/badge/arch-arm64--v8a-blue)
![android](https://img.shields.io/badge/Android-8.0%2B-green)
![runtime](https://img.shields.io/badge/runtime-Termux%20fork%20(no%20proot)-orange)

<p align="center">
  <img src="docs/images/settings.webp" width="360" alt="AIFactory 设置页">
</div>

## 直接下载 APK

| 渠道 | 地址 | 备注 |
|---|---|---|
| **仓库内文件**（手机点开即下） | [`apk/AIFactory-latest.apk`](https://github.com/327542912/AIFactory/raw/main/apk/AIFactory-latest.apk) | 固定路径，每次构建覆盖更新，链接永不失效 |
| 带版本号的文件 | [`apk/AIFactory-0.2.4-debug.apk`](https://github.com/327542912/AIFactory/raw/main/apk/AIFactory-0.2.4-debug.apk) | 存档用 |
| Release 资产 | [`apk-latest`](https://github.com/327542912/AIFactory/releases/tag/apk-latest) | 与上面同一份文件，附 `.sha256` |
| **Android SDK（arm64，多版本）** | [`sdk-latest/android-sdk-aarch64.zip`](https://github.com/327542912/AIFactory/releases/download/sdk-latest/android-sdk-aarch64.zip) | 133 MB，`platforms/android-34·35·36` + `build-tools/34.0.0·35.0.0·36.0.0`，native 工具全部 aarch64 静态；App 默认地址已指向它 |

> debug 签名，仅供自用；同包名同签名可直接覆盖安装，`/data/data/com.aifactory.app` 里的 Runtime 不会丢。

## 升级与数据保留

| 升级对象 | 怎么做 | 数据 |
|---|---|---|
| App（APK） | 直接覆盖安装。自 0.2.3 起 CI 使用固定 keystore（`keystore/aifactory-debug.jks`，证书 SHA-256 `05:FC:56:5E:…:FF:0B`），签名终身一致 | `/data/data/com.aifactory.app` 全部保留（Runtime、SDK、项目、配置） |
| Runtime（apt 包） | 终端里 `apt update && apt upgrade` | 只替换对应文件，`/data/data/.../files/home` 里的项目不受影响 |
| Runtime（bootstrap zip） | 重装 zip = **整目录替换**：`files/usr` 先改名为 `usr-old` 再删除 | 已用 apt 装的包会全部消失，等于回到出厂状态 |
| Android SDK | 重新导入 zip 会 `rm -rf` 旧 SDK 目录再替换 | 只影响 SDK 本身 |

> 0.2.2 及更早的包每次构建签名随机（实测 `d843c69d…` 与 `fbacebdadd…`），互相覆盖安装会失败、只能卸载，因此从 0.2.2 升到 0.2.3 **需要卸载一次**；从 0.2.3 起不再需要。

---

## 这是什么

一台没 root 的 Android 手机上，要跑 `bash`、`java`、`gradle`、`git`、`apksigner`，最后在手机本地编出 APK —— 这条路通常要靠 Termux，或者靠 proot 套一个 rootfs。AIFactory 走的是第三条路：

**以 `termux-packages` 为构建系统，从源码把整套运行环境重编一遍，把二进制里面写死的前缀从 `/data/data/com.termux/files/usr` 换成 `/data/data/com.aifactory.app/files/usr`，作为本 App 的私有数据直接 `execve`。**

| 项 | 值 |
|---|---|
| 应用包名 / applicationId | `com.aifactory.app` |
| 当前版本 | `0.2.4`（versionCode 7） |
| `targetSdk` | **28**（硬性上限，见下） |
| `minSdk` / `compileSdk` | 26 / 34 |
| Runtime 前缀 | `/data/data/com.aifactory.app/files/usr` |
| 架构 | `aarch64`（arm64-v8a） |
| 是否需要 root / proot / Termux | 都不需要 |

### 为什么 `targetSdk` 必须是 28

Android 10（API 29）起，应用私有目录下的可执行文件被禁止直接 `execve`。`targetSdk 28` 才能让这个 App 执行自己 `files/usr/bin` 下的 ELF —— 这不是偷懒，是这套方案成立的**前提**。改回 34 会直接让整个 Runtime 无法启动。

### 为什么不用 proot

proot 靠 `ptrace` 拦截每个系统调用做路径重写，代价是：每次文件操作都多一次陷阱、线程数受限于调试器、`fork` 密集的构建（Gradle / CMake）慢一个数量级，而且 `ptrace` 在某些 ROM 的 seccomp 策略下直接被拒。改前缀重编虽然只做一次、要几十分钟，但**运行时是零开销的原生执行**。

---

## 快速开始（手机端）

### 1. 安装 App

从 [apk-latest](https://github.com/327542912/AIFactory/releases/tag/apk-latest) 下载 `AIFactory-0.2.4-debug.apk`（debug 签名，仅供自用）。

```text
https://github.com/327542912/AIFactory/releases/download/apk-latest/AIFactory-0.2.4-debug.apk
```

### 2. 安装 Runtime

打开 App → **设置** → 第 1 张卡「安装 Runtime」，地址默认已填好，直接点「下载并安装」（约 29.6 MB，解压后 3200+ 文件、1100+ 软链）。

### 3. 接入自建 apt 源

同一个页面第 2 张卡点「写入软件源并更新」。它做两件事：把 `etc/apt/sources.list` 里的 **官方 Termux 源整行注释**（官方包的路径前缀是 `com.termux`，装进来自建 Runtime 会静默坏掉），换成本仓库发布的自建平铺源：

```text
deb [trusted=yes] https://github.com/327542912/AIFactory/releases/download/runtime-repo/ ./
```

`Ign ... InRelease` 是平铺源 + `[trusted=yes]` 的正常现象，不是错误。

### 4. 装工具链

终端里：

```bash
pkg install openjdk-21 gradle git apksigner zip unzip
```

装完回设置页，`java` / `gradle` / `git` / `apksigner` 四个 ✗ 会变成 ✓（判据是 `files/usr/bin` 下文件是否真实存在，不缓存状态）。

### 5. 装 Android SDK

第 3 张卡的地址已默认填好，直接点「下载并安装」：

```text
https://github.com/327542912/AIFactory/releases/download/sdk-latest/android-sdk-aarch64.zip
```

包内是三条完整版本线，一份覆盖到 `compileSdk 36`：

| 目录 | 内容 | native 工具 |
|---|---|---|
| `platforms/android-34` / `35` / `36` | 各自 `android.jar` + `core-for-system-modules.jar` + `framework.aidl` | — |
| `build-tools/34.0.0` | 官方 jars（`d8.jar`、`apksigner.jar`） | aarch64 静态 `aapt2/aapt/aidl/zipalign/dexdump/split-select` |
| `build-tools/35.0.0` | 同上 | 同上（35.0.2 静态二进制） |
| `build-tools/36.0.0` | 同上 | 同上（**当前用 35.0.2 的 aapt2**，官方 36 无 aarch64 预编译；实测编 API 36 资源可用，遇到 `aapt2` 报错请回报） |

**关键点**：官方 `build-tools` 的 `aapt2` / `zipalign` / `aapt` / `aidl` 是 x86_64 ELF，在 arm64 手机上根本无法 `exec`；这里全部换成 aarch64 静态版，jar 与脚本保持官方原样（它们跑在 JVM 上，与架构无关）。

体积裁剪说明：`platforms/*/data`（每平台约 100 MB 的 framework 资源，`aapt2` 用 `android.jar` 即可）、IDE 用的 `skins/templates/android-stubs-src.jar`、`build-tools/*/lib64`（LLVM/渲染脚本，x86_64）已剔除。

### 6. 在手机上编 APK

```bash
cd ~/projects/<项目>
bash build_termux.sh
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`build_termux.sh` 的 shebang 是 `#!/data/data/com.termux/files/usr/bin/bash`（历史遗留），**在 Runtime 里必须用 `bash build_termux.sh` 调用**，直接 `./build_termux.sh` 会 `bad interpreter`。

---

## 构建与复现（GitHub Actions）

两套独立的流水线，都在 `.github/` 下，且**都只保留 `workflow_dispatch`**——早前挂着 `push` 触发器，每改一行代码都会误触发一轮 36 分钟的重编。

### A. Runtime（[`build-aifactory-runtime.yml`](.github/workflows/build-aifactory-runtime.yml)，分支 `master`）

```bash
gh workflow run build-aifactory-runtime.yml -f skip_bootstrap=false
```

| 输入 | 默认 | 含义 |
|---|---|---|
| `skip_bootstrap` | `false` | `true` = 跳过 bootstrap 重编，只编 apt 包（省 36–47 分钟） |
| `extra_packages` | 空 | 要**塞进 bootstrap zip** 的额外包；填 `none` 表示不打包 |
| `repo_packages` | 空 | 只发布到 **apt 源**的包，如 `openjdk-21,gradle`；填 `none` 表示不打包 |

常用组合：

| 目的 | 参数 |
|---|---|
| 全量重出 bootstrap | `skip_bootstrap=false`，其余留空 |
| 只补 apt 包（日常） | `skip_bootstrap=true`、`extra_packages=none`、`repo_packages=openjdk-21,gradle` |

流水线步骤（按执行顺序）：

1. `Checkout AIFactory` / `Checkout Termux build system` —— 后者是 fork `agnostic-apollo/termux-packages` 的 `infra-improvs` 分支
2. `Configure AIFactory package identity` —— 把包名/前缀写进构建系统
3. 五个确定性补丁：`swig` raw-insert 修 unbound、`docbook-xml` 换 oasis 镜像、`dos2unix`/`nano` 换活源、剔除 `command-not-found`、给全局 CMake 传 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`
4. `Preflight verify source hashes` —— 门禁：下载所有源包并逐字节比对 SHA256，404/410 硬失败、5xx 仅警告
5. `Build matching Android NDK Docker image`（约 5 分钟）
6. `Build ARM64 bootstrap`（**冷启动 36–47 分钟**）
7. `Normalize bootstrap prefix leftovers` —— 把 18 个 shell 脚本正文里残留的 `com.termux` 改掉（二进制跳过），并断言旧前缀 0 处
8. `Locate / Verify / Upload / Publish release` —— 发布到 `runtime-latest`
9. `Build extra packages` → `Publish apt repo` —— 发到 `runtime-repo`

**两个反直觉的细节**

- `build-bootstraps.sh` 里 `TERMUX_BOOTSTRAP_OUTPUT_DIRECTORY` 指向**仓库根目录**，不是 `output/`，zip 落在 `termux-packages/bootstrap-aarch64.zip`，所以定位用的是 `find . -maxdepth 3 -name 'bootstrap-aarch64.zip'`。
- `run-docker.sh` 是 `set -e` 的 `sh`，末尾的 trap 会把"构建成功"的退出码翻成 1 —— 日志明明打印了 `[*] Finished successfully`，步骤却红。workflow 里用 `set +e` 捕获 rc，**只以 zip 是否落盘为判据**，Upload/Publish 一律 `if: always()`。

**为什么没有缓存**：`run-docker.sh` 只挂载仓库目录，`~/.termux-build` 在容器内，runner 结束即销毁 —— 每轮都是从零冷编，这也是 36 分钟起步的原因。

### B. APK（[`android-build.yml`](.github/workflows/android-build.yml)，分支 `app-0.2.2`）

JDK 21 (temurin) + `setup-android` + `sdkmanager platforms;android-34 build-tools;34.0.0` + Gradle 8.7 → `gradle assembleDebug` → artifact `aifactory-debug-apk`。约 6–8 分钟。

> 仓库里**没有 gradle wrapper 目录**，CI 直接调 `gradle`，不要指望 `./gradlew`。

---

## 仓库结构

| 分支 | 内容 | 用途 |
|---|---|---|
| `main`（默认分支） | 只有 README + 文档 | 仓库门面；`workflow_dispatch` 要求 workflow 文件在默认分支 |
| `master` | runtime 构建流水线 + 补丁脚本 | 真正的 CI |
| `app-0.2.2` | App 源码（Compose）+ `android-build.yml` | 出 APK |
| `aifactory-*` | 历史快照 | 存档，App 仍为 0.1.0 / targetSdk 34，**不要直接用** |

Key 文件：

```text
.github/workflows/build-aifactory-runtime.yml   runtime 全流程（21 步）
.github/workflows/android-build.yml             APK 构建
.github/workflows/fixup-runtime-prefix.yml      已发布 zip 的一次性前缀修复
.github/scripts/normalize-bootstrap-prefix.sh   bootstrap 前缀归一化（构建内也会调用）
app/src/main/java/com/aifactory/app/
    Config.kt             RuntimePaths：硬编码 files/usr、默认三个下载地址
    RuntimeEngine.kt      exec 封装、环境变量、statusReport()（✓/✗ 的唯一判据）
    ArchiveInstaller.kt   解压 / 建软链 / chmod（不做路径重写）
    PluginsPage.kt        插件中心：apt 包 + 文件探针 + 实时安装输出
```

---

## 发布资产

| tag | 资产 | 说明 |
|---|---|---|
| [`runtime-latest`](https://github.com/327542912/AIFactory/releases/tag/runtime-latest) | `bootstrap-aarch64.zip` | 29,610,948 B，sha256 `1738bf87…0d750d` |
| [`runtime-repo`](https://github.com/327542912/AIFactory/releases/tag/runtime-repo) | ~200 个 `.deb` + `Packages` / `Packages.gz` / `Release` | 平铺 apt 源 |
| [`apk-latest`](https://github.com/327542912/AIFactory/releases/tag/apk-latest) | `AIFactory-0.2.4-debug.apk` + `.sha256` | 16,507,552 B |
| [`sdk-latest`](https://github.com/327542912/AIFactory/releases/tag/sdk-latest) | `android-sdk-aarch64.zip` + `.sha256` | 139,929,613 B，sha256 `0d5f91d3…57c28` |

资产名固定、URL 稳定，App 里三个地址是写死的默认值，所以**不用改 App 就能换内容**。GitHub API 直传同名 asset 会 422，重发资产要先 `DELETE` 旧的。

---

## 已知限制

| 问题 | 现状 / 原因 | 出路 |
|---|---|---|
| ~~`aapt2` 是 ✗~~ 已解决 | 官方 `build-tools` 的 `aapt2` 是 x86_64 ELF | 已换成 `lzhiyong/android-sdk-tools` 35.0.2 的 aarch64 静态版，随 `sdk-latest` 分发 |
| ~~Android SDK 未打包~~ 已解决 | — | 已发布 `sdk-latest/android-sdk-aarch64.zip`，App 默认地址指向它 |
| `termux-setup-storage` 失效 | `bin/am` 调的是 `com.termux.termuxam.Am` 类名，包名不同 | 自造 `am` 桥接，或改用 `curl` 直接拉文件 |
| 官方源不可用 | 官方包前缀是 `com.termux`，装进来路径全错 | 必须用自建 `runtime-repo` |
| 每轮冷编 36+ 分钟 | `~/.termux-build` 不在挂载目录内 | 把缓存目录也挂出来 / 用自托管 runner |
| 编 JDK 会撞 CMake 4 | 老配方写 `cmake_minimum_required(VERSION 2.8)`，CMake ≥ 4 直接报错 | 已在 workflow 里给全局 CMake 传 `CMAKE_POLICY_VERSION_MINIMUM=3.5` |
| `command-not-found` 已被剔除 | 它的 `generate-db.js` 拿官方镜像的 `Contents-<arch>.gz` 匹配 `bin/`，前缀不同必然失配并 `exit 1` | 需要的话自己生成索引 |
| 仓库无 LICENSE 文件 | Runtime 派生自 GPL-3.0 的 `termux-packages` | 分发二进制前先定许可，别直接商用 |

---

## 排错

| 症状 | 原因 | 处理 |
|---|---|---|
| 日志 `Finished successfully` 但步骤红 | `run-docker.sh` 的 trap 覆盖退出码 | 只看 zip 是否落盘，rc 忽略 |
| `CMake Error ... Compatibility with CMake < 3.5` | CMake 4 拒收老 `cmake_minimum_required` | 传 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` |
| `bad interpreter` | 脚本 shebang 仍是 `com.termux` 前缀 | 用 `bash script.sh` 调，或跑 `normalize-bootstrap-prefix.sh` |
| `pkg` 一执行就报找不到 `termux-setup-package-manager` | 脚本正文里残留旧前缀 | 同上，归一化后旧前缀必须 0 处 |
| `apt update` 报 `Ign ... InRelease` | 平铺源没有 `InRelease`，属正常 | 忽略；`Release` / `Packages` 拉到即成功 |
| 装完 `java` 仍 ✗ | JDK 在 `opt/openjdk/bin/java`，或安装中途失败 | 看设置页 `Runtime 安装完成` 之后手动 `ls files/usr/bin/java files/usr/opt` |
| 构建中断后再跑还是 36 分钟 | 无缓存 | 见「已知限制」 |

---

## 状态

- [x] 源码重编的 ARM64 bootstrap（自建前缀，已发布）
- [x] 自建 apt 源（`pkg install` 可用）
- [x] App 0.2.2：`targetSdk 28`、版本号读 `BuildConfig`、插件页数据驱动
- [ ] JDK / Gradle / apksigner 打进 apt 源（构建中）
- [ ] arm64 版 Android SDK 打包
- [ ] 设备端 `bash build_termux.sh` 出 APK 全链路跑通

---

## 致谢

构建系统来自 [termux-packages](https://github.com/termux/termux-packages) 及其 fork [agnostic-apollo/termux-packages](https://github.com/agnostic-apollo/termux-packages)。没有 Termux 团队十几年的配方积累，这套 prefix 重编方案不可能落地。
