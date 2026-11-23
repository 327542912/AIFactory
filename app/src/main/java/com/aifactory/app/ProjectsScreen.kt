package com.aifactory.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectChip(name: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .65f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            name,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
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
    // 只有一个项目时直接选中并登记为“当前项目”，避免 Agent 反问“在哪个项目里改”
    LaunchedEffect(Unit) {
        if (projects.size == 1) { selected = projects[0]; WorkState.currentProject = projects[0] }
    }

    // 新建项目向导：方向 → 名字 → 需求 → PRD 预览 → 确认创建
    var showNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(ProjectScaffold.Kind.APP) }
    var newName by remember { mutableStateOf("") }
    var newBrief by remember { mutableStateOf("") }
    var prdPreview by remember { mutableStateOf<String?>(null) }
    // 日志/忙碌/产物全部来自进程级 Pipeline：切页不丢、不取消
    val log = Pipeline.log
    val busy = Pipeline.busy
    val apk = Pipeline.lastApk

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        val f = Pipeline.lastApk
        if (uri != null && f != null) scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                // 导出结果落到 Pipeline 日志，切页也能看到
            Pipeline.appendNote("已导出 APK。")
            } catch (e: Exception) {
                Pipeline.appendNote("导出失败：${e.message}")
            }
        }
    }
    val logExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                val src = Pipeline.lastLog
                ctx.contentResolver.openOutputStream(uri)?.use { o ->
                    if (src != null && src.exists()) src.inputStream().use { it.copyTo(o) }
                    else o.write(Pipeline.log.toByteArray())
                }
                Pipeline.appendNote("日志已导出。")
            } catch (e: Exception) {
                Pipeline.appendNote("日志导出失败：${e.message}")
            }
        }
    }
    val aabExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val f = Pipeline.lastAab
        if (uri != null && f != null) scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                Pipeline.appendNote("AAB 已导出：" + f.name)
            } catch (e: Exception) {
                Pipeline.appendNote("AAB 导出失败：" + e.message)
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            Pipeline.appendNote(importArchive(ctx, rt, uri))
            projects = listProjects(rt)
        }
    }

    fun runBuild(release: Boolean) {
        val name = selected ?: return
        // 交给全局串行调度器：此时若还有 apt 安装或别的构建在跑，会自动排队而不是抢锁
        Pipeline.buildProject(ctx, pk, File(rt.workspace, name), release)
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
            Button(onClick = { showNew = !showNew; prdPreview = null }) { Text("＋ 新建项目") }
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入源码包") }
            OutlinedButton(onClick = { projects = listProjects(rt) }) { Text("刷新") }
        }
        if (apk != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("最近产物", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(apk.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                    Text("体积 " + apk.length() + " B · 包名 " + (Pipeline.lastPkg ?: "?"), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            Pipeline.submit(ctx, "安装最近产物", listOf("安装")) { report ->
                                Installer.install(ctx, apk, Pipeline.lastPkg) { line -> report(line) }
                            }
                        }) { Text("安装") }
                        OutlinedButton(onClick = { exporter.launch(apk.name) }) { Text("导出 APK") }
                    }
                }
            }
        }
        if (showNew) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新建项目", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("1) 选择方向", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 11.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ProjectScaffold.Kind.values().toList()) { k ->
                            FilterChip(selected = kind == k, onClick = { kind = k; prdPreview = null },
                                label = { Text(k.label, fontSize = 11.sp) })
                        }
                    }
                    OutlinedTextField(newName, { newName = it; prdPreview = null },
                        label = { Text("2) 项目名字（英文/数字/下划线，会作为包名）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newBrief, { newBrief = it; prdPreview = null },
                        label = { Text("3) 需求描述：做什么、给谁用、要什么功能") },
                        minLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = newName.isNotBlank(), onClick = {
                            prdPreview = ProjectScaffold.prd(kind, newName.trim(), newBrief)
                        }) { Text("生成需求文档") }
                        Button(enabled = newName.isNotBlank(), onClick = {
                            val nm = ProjectScaffold.slug(newName)
                            val existing = File(rt.workspace, nm).exists()
                            if (existing) {
                                Pipeline.appendNote("项目 $nm 已存在，换一个名字再建，避免覆盖你的代码。")
                            } else {
                                val made = ProjectScaffold.create(rt.workspace, nm, kind, newBrief)
                                projects = listProjects(rt)
                                selected = nm
                                WorkState.currentProject = nm
                                Pipeline.appendNote("已创建 $nm（${kind.label}）：" + made.joinToString(", "))
                                showNew = false
                                prdPreview = null
                                if (kind == ProjectScaffold.Kind.APP) {
                                    Pipeline.buildProject(ctx, pk, File(rt.workspace, nm), false)
                                } else {
                                    Pipeline.appendNote("非 Android 方向不进入 APK 构建；骨架与 PRD.md 已生成，可在文件页修改。")
                                }
                            }
                        }) { Text(if (kind == ProjectScaffold.Kind.APP) "创建并构建 APK" else "创建项目") }
                        TextButton(onClick = { showNew = false; prdPreview = null }) { Text("取消") }
                    }
                    prdPreview?.let { text ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)))
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("prd", text))
                            Pipeline.appendNote("PRD 已复制到剪贴板")
                        }) { Text("复制 PRD") }
                    }
                }
            }
        }
        if (projects.isEmpty()) {
            Text("工作区还没有项目。导入 zip / tar.gz 源码包，或在对话里让 Agent 创建。", fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects) { p ->
                    ProjectChip(
                        name = p,
                        selected = selected == p,
                        onClick = { selected = p; WorkState.currentProject = p },
                        onLongClick = { confirmDelete = p }
                    )
                }
            }
            Text("长按项目名 = 删除项目（会二次确认）", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
        val sel = selected
        val verdict = if (sel != null) VerifyRunner.readVerdict(File(rt.workspace, sel)) else null
        if (sel != null) {
            val color = when (verdict) {
                "PASS" -> Color(0xFF2E7D32)
                "CRASH", "BLANK", "NOT_INSTALLED", "NO_LAUNCHER" -> Color(0xFFB4553C)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
            }
            Text(
                "验证状态：" + (verdict ?: "未验证") +
                    if (verdict == null) "（点「验证运行」：装→启动→取证）" else "",
                fontSize = 11.sp, color = color
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = selected != null && !busy, onClick = { runBuild(false) }) { Text("构建 Debug") }
            Button(enabled = selected != null && !busy, onClick = {
                val name = selected ?: return@Button
                Pipeline.buildProject(ctx, pk, File(rt.workspace, name), false, install = true)
            }) { Text("构建并安装") }
            Button(enabled = selected != null && !busy, onClick = { runBuild(true) }) { Text("构建 Release（签名）") }
            OutlinedButton(enabled = selected != null && !busy, onClick = {
                val name = selected ?: return@OutlinedButton
                val cfg = AppConfig(ctx)
                val dir = File(rt.workspace, name)
                Pipeline.submit(ctx, "发布 $name 到 GitHub", listOf("构建", "打源包", "推送", "上传 Release")) { report ->
                    val br = pk.build(dir, false)
                    report("【构建】" + if (br.ok) "通过 " + (br.apk?.path ?: "") else "失败：" + br.message)
                    if (!br.ok) return@submit "构建没通过，先修构建再发布。\n" + tailText(br.log, 4000)
                    val prd = File(dir, "PRD.md")
                    val notes = (if (prd.exists()) prd.readText().take(2000) else "自动构建发布 " + name)
                    val r = Publisher.publish(ctx, cfg, dir, br.apk, notes) { line -> report(line) }
                    if (r.ok) "✅ " + r.summary + "\n" + (r.releaseUrl ?: "") else "❌ " + r.summary
                }
            }) { Text("发布到 GitHub") }
            OutlinedButton(enabled = selected != null && !busy, onClick = {
                val name = selected ?: return@OutlinedButton
                Pipeline.buildAab(ctx, rt, File(rt.workspace, name))
            }) { Text("构建 AAB（上架）") }
            OutlinedButton(enabled = selected != null && !busy, onClick = {
                val name = selected ?: return@OutlinedButton
                Pipeline.verifyProject(ctx, rt, File(rt.workspace, name))
            }) { Text("验证运行") }
            OutlinedButton(enabled = selected != null && !busy, onClick = { confirmDelete = selected }) { Text("删除项目") }
            if (busy) TextButton(onClick = { Pipeline.clearQueue() }) { Text("清空排队") }
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("aifactory-log", Pipeline.log))
                Pipeline.appendNote("已复制日志（" + Pipeline.log.length + " 字）到剪贴板")
            }) { Text("复制日志") }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                Pipeline.current + (Pipeline.stage.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "") +
                    (if (Pipeline.queued > 0) "（排队 ${Pipeline.queued}）" else ""),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
            )
        }

        val aab = Pipeline.lastAab
        if (aab != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AAB（上架产物）", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(aab.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp)
                Text("${aab.length() / 1024} KB · targetSdk " + AabBuilder.TARGET_SDK, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                Button(onClick = { aabExporter.launch(aab.name) }) { Text("导出 AAB") }
            }
        } else {
            val btJar = File(RuntimePaths.home(ctx), ".aifactory/bundletool.jar")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!btJar.exists()) {
                    TextButton(enabled = !busy, onClick = {
                        Pipeline.submit(ctx, "下载 bundletool", listOf("下载 32MB", "校验")) { report ->
                            AabBuilder(ctx, rt).downloadBundletool { line -> report(line) }
                        }
                    }) { Text("下载 bundletool") }
                }
                Text("AAB 给「普通应用」上架用；AIFactory 本体不能上 Play。", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }

        val out = apk
        if (out != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(out.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${out.length() / 1024} KB", fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Pipeline.appendNote(Installer.install(ctx, out, Pipeline.lastPkg) { line -> Pipeline.appendNote(line) })
                    }) { Text("安装") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            OutlinedButton(onClick = {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("aifactory-log", Pipeline.log))
                Pipeline.appendNote("已复制日志（" + Pipeline.log.length + " 字）到剪贴板")
            }) { Text("复制日志") }
            OutlinedButton(onClick = { logExporter.launch("aifactory-build.log") }) { Text("导出日志") }
            Text(
                Pipeline.lastLog?.let { "存档：" + it.name } ?: "日志未存档",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
            )
        }
        val del = confirmDelete
        if (del != null) AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除项目 $del？") },
            text = { Text("会递归删除 " + del + " 目录下的全部源码、产物与日志，不可恢复。建议先「导出源码包」或推一次 GitHub。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        val dir = File(rt.workspace, del)
                        val left = withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
                        val gone = !dir.exists()
                        Pipeline.appendNote(if (gone) "已删除项目 " + del else "删除失败：" + del + "（" + (left.exceptionOrNull()?.message ?: "仍有文件删不掉") + "）")
                        if (del == WorkState.currentProject) WorkState.currentProject = null
                        projects = listProjects(rt)
                        selected = projects.firstOrNull()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
        Text(
            "提示：Release 使用应用内生成的私有密钥签名；覆盖安装同一应用时签名必须一致。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
        )
    }
}
