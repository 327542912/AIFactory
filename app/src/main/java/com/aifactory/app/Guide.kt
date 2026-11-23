package com.aifactory.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用内使用指南。
 *
 * 起因：功能都做完了，但没有一处告诉用户「从零到能编出 APK 要走哪几步」，
 * 于是只能靠对话里一条条贴命令——这不该是使用方式。
 */
@Composable
fun GuideCard() {
    var open by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("使用指南（从零到编出 APK）", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (open) "收起" else "展开", fontSize = 12.sp)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    step("1", "装 Runtime", "设置 → Runtime 第 1 项：选 bootstrap-aarch64.zip。装完显示 bash ✓。usr 可重装，home 里的项目不丢。targetSdk 28 是我们写入清单的硬前提（API 29+ 禁止 execve 私有目录 ELF）。")
                    step("2", "装 Android SDK", "同一张卡第 2 项：选 android-sdk-aarch64.zip。装完 aapt2 ✓、apksigner ✓。本地链只需要 build-tools 与 platforms/android.jar。")
                    step("3", "装 JDK / git（可选 Gradle）", "JDK 是本机链的必需品（javac、D8、apksigner 都要它）：用「文件」页把 deb 选进本地仓库再装，或终端 dpkg -i --force-depends。Gradle 不再必需——本机链不需要它，且设备上 gradle 常因 DNS/网络失败。")
                    step("4", "验证工具链", "终端执行：java -version、git --version、bash --version、python3 --version。工具链缺哪项，构建日志会直接写出来，也可以用 Agent 的 toolchain_status 查。")
                    step("5", "新建项目", "「项目」页「＋ 新建项目」：选方向（Android App / 网站 / 微信小程序 / 支付宝小程序）→ 填名字（同时作为包名）→ 写需求 → 生成 PRD 预览并可复制 → 创建。Android 方向创建完自动进入构建；其余方向生成可运行骨架 + PRD.md。")
                    step("6", "构建、自检、日志", "构建走 aapt2→javac→D8→zipalign→apksigner 五步本地链，零网络。构建前会自愈清单（补 package、补 uses-sdk），构建后跑 7 条自检（清单/dex/资源/签名 v1/v2/包名/入口/minSdk/targetSdk），任一失败即判构建失败。日志落盘 .aifactory/logs，可复制、可导出。")
                    step("7", "安装与启动", "「构建并安装」会先把 APK 拷到 cache/apks 再发安装 Intent，安装后自动轮询启动。若系统弹窗拒绝，用「导出」把 APK 拿到文件管理器里手动装（同签名覆盖才可升级）。")
                    step("8", "模型与通道池", "「模型设置」填主通道 base_url / model / key；「通道池」一行一个端点（名称|https://地址|模型|Key，Key 可省=复用主通道）。区分两类失败：429/5xx 是软冷却，2~8 轮后自动恢复；401/402/403/模型不存在是硬失败，立即隔离、从候选池移除、一次都不再试。全部被隔离时 0 秒返回原因清单。当前能跑的端点会被「粘住」，不再为轮转主动切换；它偶尔限流时原地等 1 秒重试。改完点「探测通道」，成功的端点会标 ✅ 并解除隔离。")
                    step("9", "对话驱动（Agent）", "「对话」页填 base_url / model / api_key。Agent 工具：run_shell、read_file、write_file、replace_in_file、list_dir、build_apk、install_apk、pipeline_status、read_log、fetch_url、download_url、inspect_apk、decompile_apk、remember。每轮自动注入流水线现场与长期记忆。模型必须支持 tools；4B 小模型常不支持，会出现“只说不做”，新版会识别并明确报错。")
                    step("10", "终端与并发", "终端页可开多标签，和 Agent、构建共用同一个 PREFIX 与 workspace。构建走全局串行队列（排队数显示在项目页）；apt/dpkg 有系统锁，别同时跑两个安装。")
                    step("11", "包源与 Python", "不要用 pkg（会改写 sources.list 并判源坏）。本地仓库是 files/localrepo：放 deb → 重建 Packages → apt-get update。pip 用 python3 -m ensurepip；PyPI 不可达时下 whl 拷进来装。注意 numpy/scipy 的 manylinux 轮子在 Android bionic 上 dlopen 必失败。")
                    Text("常见故障", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    fault("bash ✗ / 提示 Runtime 不完整", "usr 树缺文件，重装 bootstrap zip。")
                    fault("java ✗ 但 lib/jvm 里有文件", "四个依赖库没装或 postinst 没跑：dpkg -i 那四个库，再 dpkg --configure -a。")
                    fault("模型报 402 / 401 / 模型不存在", "这是端点自身拒绝，不是网络抖动：402 充值，401 换 Key，模型不存在去控制台开通。修好后点「探测通道」，成功会解除隔离并回到候选池。")
                    fault("E: Unable to locate package X", "本地仓库里没有这个包，先把 deb 选进来，不是源坏了。")
                    fault("dpkg frontend lock ... held by pid", "有卡死的 apt/dpkg：cat /proc/<pid>/cmdline 确认后 kill -9，再删三个 lock 文件。")
                    fault("apt 报 com.termux 前缀", "sources.list 被官方源覆盖了：echo 'deb [trusted=yes] file:///data/data/com.aifactory.app/files/localrepo ./' > ${'$'}PREFIX/etc/apt/sources.list")
                    Text(
                        "提示：构建时若要边看进度，别用 tail（会缓冲到结束才输出）。用 nohup 写日志，另一标签 tail -f。",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                    )
                }
            }
        }
    }
}

@Composable
private fun step(no: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(no + ".", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(body, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
        }
    }
}

@Composable
private fun fault(problem: String, fix: String) {
    Column {
        Text(problem, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
        Text("→ " + fix, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
    }
}
