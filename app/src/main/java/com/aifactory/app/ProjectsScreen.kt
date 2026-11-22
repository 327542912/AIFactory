package com.aifactory.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun listProjects(rt: RuntimeEngine): List<String> =
    rt.workspace.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.map { it.name }?.sorted() ?: emptyList()

private suspend fun importArchive(ctx: Context, rt: RuntimeEngine, uri: Uri): String =
    withContext(Dispatchers.IO) {
        try {
            rt.initialize()
            var name = "import-${System.currentTimeMillis()}"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val inbox = File(rt.workspace, ".inbox")
            inbox.mkdirs()
            val tmp = File(inbox, name)
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val base = name.substringBeforeLast(".tar.gz").substringBeforeLast(".tgz")
                .substringBeforeLast(".zip").ifBlank { "project" }
            val dest = File(rt.workspace, base)
            when {
                name.endsWith(".zip") -> ArchiveInstaller.unzipTo(tmp, dest)
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> {
                    dest.mkdirs()
                    val r = rt.run("tar xzf ${shQuote(tmp.path)} -C ${shQuote(dest.path)}", rt.workspace, 600)
                    if (!r.ok) return@withContext "解压失败（需要先安装 Runtime）：" + tailText(r.output, 1200)
                }
                else -> return@withContext "不支持的格式：$name（支持 zip / tar.gz）"
            }
            tmp.delete()
            "已导入到 ${dest.path}"
        } catch (e: Exception) {
            "导入失败：${e.message}"
        }
    }

@Composable
fun ProjectsScreen() {
    val ctx = LocalContext.current
    val rt = remember { RuntimeEngine(ctx) }
    val pk = remember { Packager(ctx, rt) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(listProjects(rt)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var apk by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        val f = apk
        if (uri != null && f != null) scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                log += "\n已导出 APK。"
            } catch (e: Exception) {
                log += "\n导出失败：${e.message}"
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            log = importArchive(ctx, rt, uri)
            projects = listProjects(rt)
        }
    }

    fun runBuild(release: Boolean) {
        val name = selected ?: return
        if (busy) return
        busy = true
        log = ""
        apk = null
        job = scope.launch {
            KeepAliveService.start(ctx)
            try {
                val res = pk.build(File(rt.workspace, name), release) { chunk -> log = (log + chunk).takeLast(12000) }
                apk = res.apk
                log = (if (res.ok) "✅ " else "❌ ") + res.message +
                    if (res.ok) "" else "\n\n" + tailText(res.log, 6000)
            } catch (e: CancellationException) {
                log += "\n已取消。"
                throw e
            } catch (e: Throwable) {
                log += "\n失败：${e.message}"
            } finally {
                KeepAliveService.stop(ctx)
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("项目与打包", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "工作区：${rt.workspace.path}",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入源码包") }
            OutlinedButton(onClick = { projects = listProjects(rt) }) { Text("刷新") }
        }
        if (projects.isEmpty()) {
            Text("工作区还没有项目。导入 zip / tar.gz 源码包，或在对话里让 Agent 创建。", fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects) { p ->
                    FilterChip(selected = selected == p, onClick = { selected = p }, label = { Text(p) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = selected != null && !busy, onClick = { runBuild(false) }) { Text("构建 Debug") }
            Button(enabled = selected != null && !busy, onClick = { runBuild(true) }) { Text("构建 Release（签名）") }
            if (busy) TextButton(onClick = { job?.cancel() }) { Text("停止") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        val out = apk
        if (out != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(out.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${out.length() / 1024} KB", fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { log += "\n" + Installer.install(ctx, out) }) { Text("安装") }
                    OutlinedButton(onClick = { exporter.launch(out.name) }) { Text("导出 APK") }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
        ) {
            Text(
                log.ifBlank { "构建日志会显示在这里。" },
                Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp
            )
        }
        Text(
            "提示：Release 使用应用内生成的私有密钥签名；覆盖安装同一应用时签名必须一致。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
        )
    }
}
