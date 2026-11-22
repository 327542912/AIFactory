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
import java.io.File

/** 设置页里的 Runtime / SDK 安装卡片（取代原来的 Termux 连接说明）。 */
@Composable
fun RuntimeSetupCard() {
    val ctx = LocalContext.current
    val cfg = remember { AppConfig(ctx) }
    val rt = remember { RuntimeEngine(ctx) }
    // 安装状态放在进程级 SetupRunner：切页面不会取消下载/解压，也不会丢进度
    val status = SetupRunner.status
    val progress = SetupRunner.progress
    val busy = SetupRunner.busy
    var bootUrl by remember { mutableStateOf(cfg.bootstrapUrl) }
    var bootSha by remember { mutableStateOf(cfg.bootstrapSha) }
    var sdkUrl by remember { mutableStateOf(cfg.sdkUrl) }
    var repoUrl by remember { mutableStateOf(cfg.repoUrl) }
    var mirror by remember { mutableStateOf(cfg.mirrorPrefix) }
    LaunchedEffect(Unit) { SetupRunner.recompute(ctx) }

    val bootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) SetupRunner.run(ctx) {
            ArchiveInstaller.installBootstrap(ctx, { ctx.contentResolver.openInputStream(uri)!! }) { msg -> SetupRunner.report(msg) }
        }
    }
    val sdkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) SetupRunner.run(ctx) {
            ArchiveInstaller.installSdk(ctx, { ctx.contentResolver.openInputStream(uri)!! }) { msg -> SetupRunner.report(msg) }
        }
    }

    val debPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (!uris.isNullOrEmpty()) SetupRunner.run(ctx) {
            val inbox = File(RuntimePaths.home(ctx), ".aifactory/inbox").apply { mkdirs() }
            val saved = ArrayList<File>()
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { i, u ->
                    val f = File(inbox, "local-$i.deb")
                    ctx.contentResolver.openInputStream(u)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    saved += f
                }
            }
            SetupRunner.report("已拷入 ${saved.size} 个 deb（${saved.sumOf { it.length() } / 1048576} MB），开始安装…")
            val cmd = "cd " + shQuote(inbox.path) + " && apt install -y " +
                saved.joinToString(" ") { "./" + it.name } + " 2>&1"
            val r = rt.run(cmd, RuntimePaths.home(ctx), 3600)
            SetupRunner.report((if (r.ok) "本地包安装完成。" else "本地包安装失败（退出码 ${r.exitCode}）：") +
                "\n" + tailText(r.output, 1500))
            saved.forEach { it.delete() }
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
            OutlinedTextField(mirror, { mirror = it; cfg.mirrorPrefix = it },
                label = { Text("下载加速前缀（可选，作用于下面两个下载）") },
                placeholder = { Text("https://ghproxy.net") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("填 https://ghproxy.net 就变成 https://ghproxy.net/https://github.com/…；只加一层代理，sha256 仍按原文件校验。软件源也可以这么写。",
                fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))

            HorizontalDivider()
            Text("1. 安装 Runtime（bootstrap-aarch64.zip）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(bootUrl, { bootUrl = it; cfg.bootstrapUrl = it }, label = { Text("下载地址（GitHub Release）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bootSha, { bootSha = it; cfg.bootstrapSha = it }, label = { Text("SHA-256（可选）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && bootUrl.isNotBlank(), onClick = {
                    SetupRunner.run(ctx) { ArchiveInstaller.installBootstrapFromUrl(ctx, bootUrl.trim(), bootSha, mirror, { msg -> SetupRunner.report(msg) }) }
                }) { Text("下载并安装") }
                OutlinedButton(enabled = !busy, onClick = { bootPicker.launch(arrayOf("*/*")) }) { Text("选择本地 zip") }
            }

            HorizontalDivider()
            Text("2. 软件源（apt-get install 使用）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(repoUrl, { repoUrl = it; cfg.repoUrl = it }, label = { Text("apt 软件源地址") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && rt.isInstalled() && repoUrl.isNotBlank(), onClick = {
                SetupRunner.run(ctx) {
                    val u = rt.configureAptRepo(repoUrl, cfg.extraRepoUrl)
                    SetupRunner.report("已写入软件源 $u，正在 apt update…")
                    val r = rt.run("apt-get update 2>&1", RuntimePaths.home(ctx), 300)
                    SetupRunner.report((if (r.ok) "软件源可用。" else "apt update 失败（退出码 ${r.exitCode}）：") +
                        "\n" + tailText(r.output, 900))
                }
            }) { Text("写入软件源并更新") }

            HorizontalDivider()
            Text("3. 安装 Android SDK（zip，含 platforms / build-tools）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(sdkUrl, { sdkUrl = it; cfg.sdkUrl = it }, label = { Text("SDK 下载地址") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && sdkUrl.isNotBlank(), onClick = {
                    SetupRunner.run(ctx) { ArchiveInstaller.installSdkFromUrl(ctx, sdkUrl.trim(), mirror, { msg -> SetupRunner.report(msg) }) }
                }) { Text("下载并安装") }
                OutlinedButton(enabled = !busy, onClick = { sdkPicker.launch(arrayOf("*/*")) }) { Text("选择本地 zip") }
            }
            HorizontalDivider()
            Text("4. 本地安装包（.deb）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("网速拉不动 105 MB 的 JDK 时：用浏览器/电脑把 deb 下到手机，选进来由 apt 装，依赖仍从上面那个软件源补。可多选。",
                fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            OutlinedButton(enabled = !busy && rt.isInstalled(), onClick = {
                debPicker.launch(arrayOf("application/vnd.debian.binary-package", "application/octet-stream", "*/*"))
            }) { Text("选择本地 .deb") }

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
