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
                    step("1", "装 Runtime", "本卡下方「Runtime」第 1 项：选 bootstrap-aarch64.zip。装完应显示 bash ✓。usr 里的包以后还能重装，home 里的项目与 SDK 不会丢。")
                    step("2", "装 Android SDK", "同一张卡的第 2 项：选 android-sdk-aarch64.zip。装完显示 aapt2 ✓、apksigner ✓。")
                    step("3", "装 JDK / Gradle / git", "这三样是 deb，不能靠 apt 直接拉（GitHub 常被墙）。用「文件」页把 deb 选进本地仓库后点安装，或终端里 dpkg -i --force-depends 显式装。JDK 必须凑齐 libandroid-shmem、libandroid-spawn、libjpeg-turbo、littlecms 四个库，否则 postinst 建不出 bin/java。")
                    step("4", "验证工具链", "终端执行：java -version、gradle -v、git --version、python3 --version。四项都出版本号才算齐。")
                    step("5", "多标签终端", "终端页「+ 新标签」可开多条独立 bash，切标签不丢会话。构建放一个标签、查进度放另一个，互不干扰。")
                    step("6", "项目与构建", "「项目」页：导入 zip/tar.gz 源码包 → 选中项目 → 构建 Debug。构建由全局队列执行，切页不取消；产物落在 files/outputs。")
                    step("7", "对话驱动开发", "「对话」页需先在设置里填 base_url / model / api_key。Agent 可调 run_shell、读写文件、build_apk、install_apk；它会先查 toolchain_status，缺工具会告诉你缺哪个。")
                    step("8", "装 Python 包", "不要用 pkg（会改写软件源）。pip 用离线引导：python3 -m ensurepip --upgrade，然后 python3 -m pip --version。pip install 需要 PyPI 可达，不通就下 whl 拷进来装。")
                    step("9", "维护本地包源", "本地仓库就是 files/localrepo 目录：放入 .deb → 自动重建 Packages → apt-get update。升级换同名新版 deb；卸载 dpkg -P 再删文件。dpkg 问 conffile 一律选 N，选 Y 会把 sources.list 覆盖成官方 termux 源，那会损坏 runtime。")
                    Text("常见故障", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    fault("bash ✗ / 提示 Runtime 不完整", "usr 树缺文件，重装 bootstrap zip。")
                    fault("java ✗ 但 lib/jvm 里有文件", "四个依赖库没装或 postinst 没跑：dpkg -i 那四个库，再 dpkg --configure -a。")
                    fault("E: Unable to locate package X", "本地仓库里没有这个包，先把 deb 选进来，不是源坏了。")
                    fault("dpkg frontend lock ... held by pid", "有卡死的 apt/dpkg：cat /proc/<pid>/cmdline 确认后 kill -9，再删三个 lock 文件。")
                    fault("apt 报 com.termux 前缀", "sources.list 被官方源覆盖了：echo 'deb [trusted=yes] file:///data/data/com.aifactory.app/files/localrepo ./' > $PREFIX/etc/apt/sources.list")
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
