package com.aifactory.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页里的 Runtime / SDK 安装卡片（取代原来的 Termux 连接说明）。 */
@Composable
fun RuntimeSetupCard() {
    val ctx = LocalContext.current
    val cfg = remember { AppConfig(ctx) }
    val rt = remember { RuntimeEngine(ctx) }
    var status by remember { mutableStateOf(rt.statusReport()) }
    var bootUrl by remember { mutableStateOf(cfg.bootstrapUrl) }
    var bootSha by remember { mutableStateOf(cfg.bootstrapSha) }
    var sdkUrl by remember { mutableStateOf(cfg.sdkUrl) }
    var repoUrl by remember { mutableStateOf(cfg.repoUrl) }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun launchTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                progress = "失败：${e.message}"
            } finally {
                busy = false
                status = withContext(Dispatchers.IO) { rt.statusReport() }
            }
        }
    }

    val bootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) launchTask {
            ArchiveInstaller.installBootstrap(ctx, { ctx.contentResolver.openInputStream(uri)!! }) { progress = it }
        }
    }
    val sdkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) launchTask {
            ArchiveInstaller.installSdk(ctx, { ctx.contentResolver.openInputStream(uri)!! }) { progress = it }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Runtime（独立于 Termux）", fontWeight = FontWeight.SemiBold)
            Text(status, fontSize = 12.sp, lineHeight = 18.sp)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(progress, fontSize = 12.sp)
            } else if (progress.isNotBlank()) Text(progress, fontSize = 12.sp, lineHeight = 17.sp)

            HorizontalDivider()
            Text("1. 安装 Runtime（bootstrap-aarch64.zip）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(bootUrl, { bootUrl = it; cfg.bootstrapUrl = it }, label = { Text("下载地址（GitHub Release）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bootSha, { bootSha = it; cfg.bootstrapSha = it }, label = { Text("SHA-256（可选）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && bootUrl.isNotBlank(), onClick = {
                    launchTask { ArchiveInstaller.installBootstrapFromUrl(ctx, bootUrl.trim(), bootSha, { progress = it }) }
                }) { Text("下载并安装") }
                OutlinedButton(enabled = !busy, onClick = { bootPicker.launch(arrayOf("*/*")) }) { Text("选择本地 zip") }
            }

            HorizontalDivider()
            Text("2. 软件源（pkg install 使用）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(repoUrl, { repoUrl = it; cfg.repoUrl = it }, label = { Text("apt 软件源地址") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && rt.isInstalled() && repoUrl.isNotBlank(), onClick = {
                launchTask {
                    val u = rt.configureAptRepo(repoUrl)
                    progress = "已写入软件源 $u，正在 apt update…"
                    val r = rt.run("apt update 2>&1", RuntimePaths.home(ctx), 300)
                    progress = (if (r.ok) "软件源可用。" else "apt update 失败（退出码 ${r.exitCode}）：") +
                        "\n" + tailText(r.output, 900)
                }
            }) { Text("写入软件源并更新") }

            HorizontalDivider()
            Text("3. 安装 Android SDK（zip，含 platforms / build-tools）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(sdkUrl, { sdkUrl = it; cfg.sdkUrl = it }, label = { Text("SDK 下载地址") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && sdkUrl.isNotBlank(), onClick = {
                    launchTask { ArchiveInstaller.installSdkFromUrl(ctx, sdkUrl.trim(), { progress = it }) }
                }) { Text("下载并安装") }
                OutlinedButton(enabled = !busy, onClick = { sdkPicker.launch(arrayOf("*/*")) }) { Text("选择本地 zip") }
            }
            Text(
                "Runtime 与 SDK 都由本仓库的 GitHub Actions 构建（包名 com.aifactory.app）。SDK 内含 platforms/android-34/35/36 与 build-tools/34.0.0/35.0.0/36.0.0，" +
                    "其中 aapt2 / zipalign / aapt / aidl 是编译好的 aarch64 静态二进制（官方包是 x86_64，手机上跑不起来）。地址已默认填好，直接点「下载并安装」。\n\n" +
                    "下载会手动跟随 GitHub 的 302 跳转；中断后按 HTTP Range 断点续传，最多重试 5 次，残包留在缓存里，再点一次接着下。",
                fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
            )
        }
    }
}
