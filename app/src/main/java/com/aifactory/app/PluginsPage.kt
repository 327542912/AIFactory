package com.aifactory.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

private val PluginMuted = Color(0xFF85827C)
private val PluginAccent = Color(0xFFB66B45)

/**
 * 一个插件 = 一组 apt 包 + 一个探针。
 * 状态完全由文件系统事实决定（和设置页的 ✓/✗ 用同一套判据），不靠「装过没装过」记状态。
 */
private data class PluginSpec(
    val title: String,
    val packages: List<String>,
    val note: String,
    val probe: (File) -> Boolean
)

@Composable
fun PluginsPage() {
    val ctx = LocalContext.current
    val rt = remember { RuntimeEngine(ctx) }
    val prefix = remember { RuntimePaths.prefix(ctx) }
    val scope = rememberCoroutineScope()

    val specs = remember {
        listOf(
            PluginSpec(
                "Gradle / Android Build",
                listOf("openjdk-21", "gradle", "apksigner", "zip", "unzip"),
                "装完 java/gradle/apksigner 三个叉会变勾。编 APK 还差 arm64 版 aapt2 和 Android SDK。",
                { p -> File(p, "bin/java").exists() && File(p, "bin/gradle").exists() }
            ),
            PluginSpec(
                "CMake / Native Build",
                listOf("cmake", "ninja", "make"),
                "原生构建用；编 Android native 还需要 NDK 与 sysroot。",
                { p -> File(p, "bin/cmake").exists() }
            ),
            PluginSpec(
                "Git Integration",
                listOf("git", "openssh"),
                "装完可直接 clone；认证用 ssh key 或 token。",
                { p -> File(p, "bin/git").exists() }
            ),
            PluginSpec(
                "Python Runtime",
                listOf("python"),
                "Python 3.12 已在自建源编好，装完 bin/python 即用。",
                { p -> File(p, "bin/python").exists() }
            )
        )
    }

    var marks by remember { mutableStateOf(specs.map { it.probe(prefix) }) }
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf("") }
    val busy = running.isNotBlank()

    fun refresh() {
        marks = specs.map { it.probe(prefix) }
    }

    fun install(spec: PluginSpec) {
        if (busy) return
        if (!rt.isInstalled()) {
            log = "Runtime 未安装，先到设置页安装 bootstrap。"
            return
        }
        running = spec.title
        log = "准备安装 ${spec.packages.joinToString(" ")} …"
        scope.launch {
            val hasPkg = File(prefix, "bin/pkg").exists()
            val pm = if (hasPkg) "pkg" else "apt"
            val cmd = "$pm install -y ${spec.packages.joinToString(" ")} 2>&1"
            val sb = StringBuilder()
            val r = try {
                rt.run(cmd, RuntimePaths.home(ctx), 3600) { chunk ->
                    sb.append(chunk)
                    log = tailText(sb.toString(), 1800)
                }
            } catch (e: Throwable) {
                running = ""
                log = "安装失败：${e.message}"
                return@launch
            }
            log = buildString {
                append(if (r.ok) "安装完成（$pm）" else "安装失败，退出码 ${r.exitCode}（$pm）")
                append('\n')
                append(tailText(r.output.ifBlank { sb.toString() }, 1500))
            }
            running = ""
            refresh()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("插件中心", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("每项都是 Runtime 里的一组 apt 包；状态取自文件系统事实，不缓存。", color = PluginMuted)

        if (!rt.isInstalled()) {
            Text("Runtime 未安装，插件都装不了。先去设置页安装 bootstrap。", color = PluginAccent, fontSize = 13.sp)
        }

        specs.forEachIndexed { i, spec ->
            val installed = marks.getOrElse(i) { false }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (installed) "已安装" else "未安装",
                            fontSize = 11.sp,
                            color = if (installed) PluginAccent else PluginMuted
                        )
                    }
                    Text(
                        "apt 包：" + spec.packages.joinToString(" "),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = PluginMuted
                    )
                    Text(spec.note, fontSize = 11.sp, lineHeight = 16.sp, color = PluginMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && rt.isInstalled() && !installed,
                            onClick = { install(spec) }
                        ) { Text(if (running == spec.title) "安装中…" else "安装") }
                        OutlinedButton(
                            enabled = !busy && File(prefix, "bin/apt").exists(),
                            onClick = { install(spec) }
                        ) { Text("重装/更新") }
                    }
                }
            }
        }

        if (log.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("安装输出", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    Text(log, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
