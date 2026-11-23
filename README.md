# AIFactory

> **📦 只想拿文件？** → **[下载总入口 / All downloads](https://github.com/327542912/AIFactory/releases/tag/downloads)** —— APK、Runtime bootstrap、Android SDK、Gradle 8.7 的直链、体积、sha256 全在这一页。

在一台 Android 手机上，把一个软件从「一句话需求」做到「可安装的 APK」。

不是云端构建服务，也不是在线 IDE：编译、签名、安装、验证、发布全部在这台手机的私有目录里跑，断网也能构建。

> 当前版本：**0.10.23（versionCode 93）**
> 签名：固定 `release.jks`（CN=AIFactory），可覆盖安装，卸载才会丢 Runtime
> 适用：Xiaomi 2311DRK48C / Android 14（arm64），自带 Linux Runtime（不依赖 Termux）

---

## 它解决什么问题

手机上没有可用的 Android 构建链：gradle 在设备上必 fork Daemon 且要联网拉 `dl.google.com`，一次 `assembleDebug` 直接卡死。所以这里不用 gradle，改为**零网络零依赖的本地五步链**：

```
aapt2 compile → aapt2 link → javac → D8 → jar uf → zipalign → apksigner
```

硬前提：`targetSdk = 28`。API 29+ 禁止执行应用私有目录里的 ELF，而 Runtime 的 `bash`、`aapt2`、`java` 全在 `/data/data/<pkg>/files/usr`。代价是这个 App 自己永远上不了 Google Play（Play 要求 targetSdk ≥ 36），这是设计取舍，不是疏漏。

## 架构

| 层 | 文件 | 作用 |
|---|---|---|
| Runtime | `RuntimeEngine.kt` `ArchiveInstaller.kt` | Termux 布局 prefix（`files/usr`）+ home；安装 bootstrap / SDK；流式执行、超时、取消、杀进程树 |
| Agent | `AgentEngine.kt` | 模型⇄工具循环；原生 tool calling，不支持时退回 ```tool 文本协议；`runGuarded` 兜异常（进程不退出） |
| 打包 | `Packager.kt` | 本地五步链构建 → `release.jks` 签名 → `files/outputs` 归档 → FileProvider + 系统安装器 |
| 通道 | `Pool.kt` `Secrets.kt` | N 个 OpenAI 兼容端点分片 / 粘滞 / 隔离；Key 三层脱敏 |
| 验证 | `VerifyRunner.kt` `VerifyA11yService.kt` | 验证档位状态机；无障碍读窗口文字与手势（可选） |
| 发布 | `GitOps.kt` | `git_push`：向上找 `.git`、密钥扫描、Token 只走请求头 |
| UI | `MainActivity.kt` `ProjectsScreen.kt` `FileHub.kt` | 对话 / 项目 / 文件中心 / 设置（含通道池与模型配置） |
| 保活 | `KeepAliveService.kt` | 构建或 Agent 运行期间前台服务 + WakeLock |

## 能力

| 模块 | 说明 |
|---|---|
| 对话 / Agent | 单轮最多 200~300 步，自动写文件、跑命令、构建、安装、验证 |
| 本地构建 | 产物 `<项目>/app/build/outputs/apk/debug/app-debug.apk`，归档 `files/outputs/<项目>-<时间戳>.apk` |
| 自检 | `apksigner verify`、清单/入口 Activity、targetSdk、dex 字符串校验 |
| 安装启动 | 系统安装器 + `am start`，`verify.json` 记录验证档位 |
| 回归闸门 | `scripts/regression-guard.sh`，R-01…R-52 断言，源码少一个关键串就红 |
| 通道池 | 按成功率与延迟排序，粘住可用端点；硬错误直接隔离 |
| 发布 | `git_push`，Token 从设置读取，不落 `git config` |

## 通道池：为什么不是「主 / 备」两个

免费模型（GLM 免费额度）按端点的分钟级配额计，主备二元切换只会在两个端点之间来回撞墙。所以改成通道池，并区分两类失败：

- **软失败**（429 限流 / 5xx / 网络）：冷却 2~8 轮后自动恢复；若它是当前粘住的端点，**不换端点，原地等 1 秒重试**——能用的慢端点比换到坏端点划算。
- **硬失败**（401 Key 无效 / 402 余额不足 / 403 无权限 / 模型不存在）：**立即隔离**，从候选池整体移除，一次都不再尝试；只有设置页「探测通道」成功后解除。
- 全部被隔离时，**0 秒内返回原因清单**，一个请求都不发。

写法（一行一个；`名称` 可省，`Key` 可省则复用主通道 Key；以 URL 段为锚点解析）：

```
硅基|https://api.siliconflow.cn/v1|zhipuai/GLM-Z1-9B-0414|sk-xxx
qianfan|https://qianfan.baidubce.com/v2|ernie-speed-8k|bce-v3/ALTAK-xxx/yyy
本地|http://127.0.0.1:8080/v1|local|
```

服务商差异（实测）：

| 服务商 | 可用性 |
|---|---|
| 阿里 dashscope | `Bearer sk-…` 标准 OpenAI，可用 |
| 百度千帆 v2 | `bce-v3/ALTAK-…/…` 可作 Bearer；模型需在控制台开通 |
| 火山 ark | 必须标准 `Bearer` Key，格式错会报 `AuthenticationError` |
| 讯飞星辰 | 普通应用 `ak-` **不能直接 Bearer**（`apikey not found`），需「调用密码」或 Token Plan 密钥；HMAC 签名未内置 |
| DeepSeek | 标准 OpenAI；余额不足返回 402 |

## 目录结构

```
app/src/main/java/com/aifactory/app/
  AgentEngine.kt        Agent 主循环 + 工具实现 + 崩溃兜底（runGuarded）
  Pool.kt               通道池：分片 / 粘滞 / 冷却 / 隔离
  Secrets.kt            密钥脱敏（mask / scrub）
  Sessions.kt           会话与聊天状态（进程级 + 落盘）
  Packager.kt           五步构建链、清单修补、安装监控
  VerifyRunner.kt       验证档位（PASS_UI / PASS / LAUNCHED_UNVERIFIED …）
  VerifyA11yService.kt  无障碍读窗口文字与手势（可选）
  GitOps.kt             git_push：向上找 .git、密钥扫描、Token 走请求头
  FileHub.kt            文件中心（产物 / 附件，单列 LazyColumn）
  MainActivity.kt       对话页 / 设置页 / 模型设置 / 通道池 UI
  Config.kt             SharedPreferences 配置
docs/regressions.md     回归台账 R-01…R-52，每条记录"为什么加这个约束"
scripts/regression-guard.sh  源码断言闸门
```

设备侧路径：

| 用途 | 路径 |
|---|---|
| Runtime | `/data/data/com.aifactory.app/files/usr` |
| 工作区 | `files/home/projects`（仓库根，一个顶级目录一个 app） |
| SDK | `files/home/android-sdk`（build-tools 36.0.0、`lib/d8.jar`、`lib/apksigner.jar`） |
| 归档产物 | `files/outputs/` |
| 日志 | `files/home/.aifactory/logs/` |
| 崩溃 | `files/home/.aifactory/logs/crash-*.txt` |

## 发布流水线（GitHub Actions）

推送到分支 `app-0.2.2` 即触发 `.github/workflows/android-build.yml`：

1. `gradle assembleDebug` 构建（JDK 21 + platform android-34 + Gradle 8.7）
2. 校验产物非空、打印体积与 sha256
3. 上传 artifact `aifactory-debug-apk`
4. **把 APK 同步进 `main:apk/`**：`AIFactory-<版本>-debug.apk`、`AIFactory-latest.apk` 与对应 `.sha256`
5. **更新 release `apk-latest`**（滚动替换，旧资产删除，避免下错版本）

签名用的是仓库内 `keystore/aifactory-debug.jks`（密码 `aifactory`，别名 `aifactory`），所以 CI 出的包和本机出的包签名一致，可直接覆盖安装，不需要卸载。

## 自己编译（宿主机）

```bash
export JAVA_HOME=$PWD/bt/jdk
export PATH=$JAVA_HOME/bin:$PATH
./tools/gradle-8.7/bin/gradle -p plug022 :app:assembleDebug --no-daemon --console=plain
# 产物 plug022/app/build/outputs/apk/debug/app-debug.apk
```

验版本：`bt/aapt2x/aapt2 dump badging app-debug.apk | head -1`
跑闸门：`cd plug022 && sh scripts/regression-guard.sh`（期望 `== 全部通过 ==`）

## 版本线（近期）

| 版本 | code | 内容 |
|---|---|---|
| 0.10.23 | 93 | 设置页显示 GitHub 登录状态，401 时清空并给出重建指引 |
| 0.10.22 | 92 | 应用内使用指南同步通道池/隔离/探测；首页介绍重写 |
| 0.10.21 | 91 | 粘住可用端点：成功后不再轮转；限流时原地等 1 秒重试 |
| 0.10.20 | 90 | 硬失败隔离：欠费/Key 错/模型不存在不再进候选，手动探测才解除 |
| 0.10.19 | 89 | 通道池按 URL 段解析，不再死扣段数；丢弃原因逐行报出 |
| 0.10.18 | 88 | 静默丢弃可视化、诊断探测（真实 HTTP 码 + 响应体）、页面显示版本号 |
| 0.10.17 | 87 | Key 三层脱敏，历史记录也遮；首轮强制试一次 |
| 0.10.16 | 86 | 修 4xx 提前退出（402/401 属于端点自身问题，应继续换） |
| 0.10.15 | 85 | 通道池：调用计数分片 + 失败冷却 |
| 0.10.14 | 84 | 崩溃兜底：`runGuarded` + 全局 uncaught handler，双通道全挂不再闪退 |
| 0.10.13 | 83 | 双向熔断（后被隔离机制取代） |
| 0.10.12 | 82 | 产物命名去掉 `debug` |
| 0.10.11 | 81 | `git_push`：单仓库适配、密钥扫描、自动身份 |
| 0.10.10 | 80 | 探测改打 `/chat/completions`（`/models` 探不出 1305） |
| 0.10.9  | 79 | `git_push` 工具 + 文件中心整页单列 |
| 0.10.8  | 78 | 无障碍验证服务 + `waitInstalled` 防误报 |
| 0.10.7  | 77 | 五步链去掉 `zip` 依赖，改位置参数喂 flat |
| 0.10.6  | 76 | `aapt2 -R` 改 `res.zip`（已废弃，见 R-28） |

## 安全

- Key 只以 `sk-****abcd` 出现；日志、聊天记录同样脱敏。
- `git_push` 不把 Token 写进 `.git/config`，只用一次性 `http.extraheader`，输出统一 `redact()`。
- 密钥扫描拦截 `apk/aab/jks/keystore/build/.gradle/local.properties`。
- **Key 一旦出现在截图或聊天里，视为泄露，请去服务商后台撤销重建。**

## 已知限制

- `targetSdk 28`，无法上架 Google Play。
- 无障碍不能程序化开关（无 `WRITE_SECURE_SETTINGS` / adb），需用户手动启用一次。
- 讯飞星辰普通应用鉴权未内置 HMAC 签名。
- 设备上 `zip` / `wget` 不存在，脚本按 `curl` 与位置参数实现。

## License

私有项目，未授权不得分发。签名密钥 `keystore/release.jks` 不随源码分发。
