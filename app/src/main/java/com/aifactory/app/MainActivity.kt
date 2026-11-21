package com.aifactory.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Muted = Color(0xFF85827C)
private val Accent = Color(0xFFB66B45)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIFactoryApp { command ->
                RuntimeEngine(applicationContext).execute(command)
            }
        }
    }

    private fun dispatchTermuxCommand(command: String): String {
        if (packageManager.packageInfoOrNull("com.termux") == null) return "未检测到 Termux。请先安装并启动 Termux。"
        return try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "AIFactory")
                putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Command dispatched by AIFactory")
            }
            startService(intent)
            "命令已派发给 Termux。注意：此初版尚未实现 stdout/stderr 实时回传。\n$ $command"
        } catch (e: SecurityException) {
            "Termux 拒绝了命令请求。请检查 RUN_COMMAND 权限，并在 Termux 的 ~/.termux/termux.properties 中设置 allow-external-apps=true，然后重启 Termux。"
        } catch (e: Exception) {
            "派发失败：${e.message}"
        }
    }
}

private fun PackageManager.packageInfoOrNull(name: String) = try { getPackageInfo(name, 0) } catch (_: Exception) { null }
private data class ChatMessage(val role: String, val text: String)
private enum class Page(val label: String) { CHAT("对话"), PROJECTS("项目"), FILES("文件"), TERMINAL("终端"), PLUGINS("插件"), SETTINGS("设置") }

@Composable
private fun AIFactoryApp(runCommand: suspend (String) -> String) {
    var page by remember { mutableStateOf(Page.CHAT) }
    var dark by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val importedFiles = remember {
        mutableStateListOf<FileRecord>().apply { addAll(loadFileRecords(context)) }
    }
    val chatAttachments = remember { mutableStateListOf<FileRecord>() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            val record = importFile(context, uri)
            if (record != null) {
                if (importedFiles.none { it.uri == record.uri }) {
                    importedFiles.add(record)
                    persistFileRecords(context, importedFiles)
                }
                if (chatAttachments.none { it.uri == record.uri }) {
                    chatAttachments.add(record)
                }
            }
        }
    }
    val launchFilePicker = {
        filePicker.launch(arrayOf("*/*"))
    }
    val scheme = if (dark) darkColorScheme(
        background = Color(0xFF171715), surface = Color(0xFF232320), primary = Color(0xFFD89A78),
        onBackground = Color(0xFFEDEBE6), onSurface = Color(0xFFEDEBE6)
    ) else lightColorScheme(
        background = Color(0xFFF8F7F4), surface = Color.White, primary = Accent,
        onBackground = Color(0xFF242321), onSurface = Color(0xFF242321)
    )
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Page.entries.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Icon(when (item) {
                                Page.CHAT -> Icons.Default.ChatBubbleOutline
                                Page.PROJECTS -> Icons.Default.Folder
                                Page.FILES -> Icons.Default.AttachFile
                                Page.TERMINAL -> Icons.Default.Terminal
                                Page.PLUGINS -> Icons.Default.Extension
                                Page.SETTINGS -> Icons.Default.Settings
                            }, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AIFactory", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(page.label, fontSize = 12.sp, color = Muted)
                    }
                    IconButton(onClick = { dark = !dark }) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "切换主题") }
                }
                when (page) {
                    Page.CHAT -> ChatPage(
                        attachments = chatAttachments,
                        onPickFiles = launchFilePicker,
                        onRemoveAttachment = { chatAttachments.remove(it) }
                    )
                    Page.PROJECTS -> ProjectsPage()
                    Page.FILES -> FileCenterPage(
                        files = importedFiles,
                        onImport = launchFilePicker,
                        onOpen = { file ->
                            val error = openImportedFile(context, file)
                            if (error != null) {
                                android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onRemove = { file ->
                            importedFiles.remove(file)
                            chatAttachments.removeAll { it.uri == file.uri }
                            persistFileRecords(context, importedFiles)
                        }
                    )
                    Page.TERMINAL -> TerminalPage(runCommand)
                    Page.PLUGINS -> PluginsPage()
                    Page.SETTINGS -> SettingsPage()
                }
            }
        }
    }
}

@Composable
private fun ChatPage(
    attachments: List<FileRecord>,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (FileRecord) -> Unit
) {
    val messages = remember { mutableStateListOf(ChatMessage("assistant", "你好，我是 AIFactory。描述你想构建的软件，我们从需求分析和工程规划开始。")) }
    var input by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var model by remember { mutableStateOf("gpt-4o-mini") }
    var apiKey by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Accent.copy(alpha = .14f)) { Icon(Icons.Default.AutoAwesome, null, Modifier.padding(9.dp), tint = Accent) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("AI 工程师", fontWeight = FontWeight.SemiBold)
                Text(if (apiKey.isBlank()) "模型尚未配置" else "模型：$model", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { showSettings = !showSettings }) { Text("模型设置") }
        }
        if (showSettings) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key（仅保存在内存）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("兼容 OpenAI /chat/completions 接口。关闭应用后密钥会丢失。", color = Muted, fontSize = 11.sp)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("工程流程", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("分析", "规划", "编码", "构建", "测试").forEachIndexed { i, label ->
                        Surface(color = if (i == 0) Accent.copy(alpha = .14f) else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
                            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 11.sp, color = if (i == 0) Accent else Muted)
                        }
                    }
                }
            }
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.role == "user") MaterialTheme.colorScheme.primary.copy(alpha = .13f) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)
                    ) { Text(message.text, Modifier.padding(14.dp), fontSize = 14.sp, lineHeight = 21.sp) }
                }
            }
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在请求模型…", color = Muted, fontSize = 12.sp) } }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            Column(Modifier.padding(12.dp)) {
                if (attachments.isNotEmpty()) {
                    Text("附件 · ${attachments.size}", fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp)
                    attachments.forEach { file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AttachFile, null, tint = Accent,
                                modifier = Modifier.size(16.dp))
                            Text(file.name, Modifier.weight(1f), fontSize = 12.sp,
                                maxLines = 1)
                            IconButton(
                                onClick = { onRemoveAttachment(file) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Close, "移除附件",
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Text(
                        "当前版本保存附件引用和名称，尚未解析文件内容发送给模型。",
                        color = Muted, fontSize = 10.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPickFiles) {
                        Icon(Icons.Default.Add, "添加附件", tint = Accent)
                    }
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("描述你想开发的软件…") }, shape = RoundedCornerShape(18.dp), maxLines = 5)
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = input.isNotBlank() && !busy, onClick = {
                        val names = attachments.map { it.name }
                        val prompt = input.trim() +
                            if (names.isNotEmpty()) "\\n\\n附件文件：\\n" + names.joinToString("\\n") else ""
                        input = ""
                        messages.add(ChatMessage("user", prompt))
                        attachments.toList().forEach { onRemoveAttachment(it) }
                        if (apiKey.isBlank()) messages.add(ChatMessage("assistant", "请先打开模型设置，填写 API 地址、模型名称和 API Key。"))
                        else {
                            busy = true
                            scope.launch {
                                val answer = withContext(Dispatchers.IO) { requestChat(baseUrl, model, apiKey, messages.toList()) }
                                messages.add(ChatMessage("assistant", answer)); busy = false
                            }
                        }
                    }) { Icon(Icons.Default.ArrowUpward, "发送", tint = Accent) }
                }
                Text("Auto 模式目标：规划、编码、构建、测试与修复；本版本尚未开放自主工具调用。", Modifier.padding(start = 4.dp, top = 5.dp), color = Muted, fontSize = 10.sp)
            }
        }
    }
}

private fun requestChat(base: String, model: String, key: String, history: List<ChatMessage>): String {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        val array = JSONArray()
        history.forEach { array.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", model).put("messages", array).put("temperature", 0.2)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) "模型 API 错误（HTTP $code）：$response"
        else JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "模型没有返回文本。")
    } catch (e: Exception) { "请求失败：${e.message}" } finally { connection?.disconnect() }
}

@Composable
private fun ProjectsPage() {
    val context = LocalContext.current
    val workspace = remember(context) { EngineeringWorkspace(context) }
    val runner = remember { ProjectCommandRunner() }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(workspace.listProjects()) }
    var projectName by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf<ProjectScan?>(null) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editPath by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var lastCheckpoint by remember { mutableStateOf("") }
    val projectTreePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching { workspace.importTree(uri, projectName.ifBlank { "Imported Project" }) }
                .onSuccess {
                    projectName = ""
                    refresh()
                    selectedId = it.id
                    scan = workspace.scan(it.id)
                    output = "工程目录已复制到 AIFactory 私有工作区。"
                }
                .onFailure { output = "导入失败：" + it.message }
        }
    }

    fun refresh() {
        projects = workspace.listProjects()
        selectedId?.let { id ->
            scan = runCatching { workspace.scan(id) }.getOrNull()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("工程工作区", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("项目保存在 AIFactory 私有目录。文件写入会先建立本地检查点。", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                projectName, { projectName = it },
                modifier = Modifier.weight(1f),
                label = { Text("新项目名称") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = projectName.isNotBlank(), onClick = {
                runCatching { workspace.createProject(projectName) }
                    .onSuccess { projectName = ""; refresh(); selectedId = it.id; scan = workspace.scan(it.id); output = "项目已创建。" }
                    .onFailure { output = "创建失败：" + it.message }
            }) { Text("创建") }
        }
        OutlinedButton(onClick = { projectTreePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(6.dp))
            Text("从 Android 文件选择器导入整个工程目录")
        }
        if (projects.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("尚无项目。创建项目后可查看工程结构和识别构建系统。", Modifier.padding(16.dp), color = Muted)
            }
        } else {
            Text("项目列表 · ${projects.size}", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(if (scan == null) 1f else 0.7f, fill = true),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(onClick = {
                        selectedId = project.id
                        scan = runCatching { workspace.scan(project.id) }.getOrNull()
                        output = ""
                    }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Accent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.name, fontWeight = FontWeight.SemiBold)
                                Text("${project.kind} · ${project.fileCount} 个文件", color = Muted, fontSize = 11.sp)
                            }
                            if (selectedId == project.id) Icon(Icons.Default.CheckCircle, null, tint = Accent)
                        }
                    }
                }
            }
        }
        scan?.let { current ->
            Divider()
            Text("工程分析：${current.project.name}", fontWeight = FontWeight.SemiBold)
            Text("类型：${current.buildSystem} · 文件：${current.files.size}", color = Muted, fontSize = 12.sp)
            current.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            Text("文件清单", fontWeight = FontWeight.Medium, fontSize = 12.sp)
            LazyColumn(Modifier.weight(0.6f, fill = true)) {
                items(current.files.take(40)) { path ->
                    TextButton(onClick = {
                        editPath = path
                        runCatching { workspace.readFile(current.project.id, path) }
                            .onSuccess { editContent = it; output = "已载入：$path" }
                            .onFailure { editContent = ""; output = "读取失败：" + it.message }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(path, Modifier.fillMaxWidth(), fontSize = 11.sp)
                    }
                }
            }
            OutlinedTextField(
                editPath, { editPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("项目相对路径") },
                singleLine = true
            )
            OutlinedTextField(
                editContent, { editContent = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 190.dp),
                label = { Text("文件内容（UTF-8，最大 2 MB）") },
                minLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = editPath.isNotBlank(), onClick = {
                    runCatching { workspace.readFile(current.project.id, editPath) }
                        .onSuccess { editContent = it; output = "读取成功。" }
                        .onFailure { output = "读取失败：" + it.message }
                }) { Text("读取") }
                Button(enabled = editPath.isNotBlank(), onClick = {
                    runCatching { workspace.writeFile(current.project.id, editPath, editContent) }
                        .onSuccess { lastCheckpoint = it; refresh(); output = "保存成功。检查点：$it" }
                        .onFailure { output = "保存失败：" + it.message }
                }) { Text("保存并创建检查点") }
                OutlinedButton(enabled = lastCheckpoint.isNotBlank(), onClick = {
                    runCatching { workspace.rollback(current.project.id, lastCheckpoint) }
                        .onSuccess { refresh(); output = "已回滚检查点：$lastCheckpoint"; lastCheckpoint = "" }
                        .onFailure { output = "回滚失败：" + it.message }
                }) { Text("回滚") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && current.buildCommand != null, onClick = {
                    scope.launch {
                        busy = true; output = "正在执行构建……"
                        val result = runner.run(current, false)
                        output = "命令：${result.command}\n退出码：${result.exitCode ?: "无"}\n耗时：${result.durationMs} ms\n\n${result.output}"
                        busy = false
                    }
                }) { Text("构建") }
                OutlinedButton(enabled = !busy && current.testCommand != null, onClick = {
                    scope.launch {
                        busy = true; output = "正在执行测试……"
                        val result = runner.run(current, true)
                        output = "命令：${result.command}\n退出码：${result.exitCode ?: "无"}\n耗时：${result.durationMs} ms\n\n${result.output}"
                        busy = false
                    }
                }) { Text("测试") }
                OutlinedButton(onClick = {
                    runCatching { workspace.scan(current.project.id) }
                        .onSuccess { scan = it; output = "扫描完成。" }
                        .onFailure { output = "扫描失败：" + it.message }
                }) { Text("重新扫描") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (output.isNotBlank()) {
            Surface(Modifier.fillMaxWidth().weight(0.7f, fill = true),
                color = Color(0xFF171715), shape = RoundedCornerShape(10.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(output, Modifier.fillMaxWidth().padding(12.dp),
                        color = Color(0xFFE4E1DA), fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalPage(runCommand: suspend (String) -> String) {
    var command by remember { mutableStateOf("pwd && ls -la") }
    var result by remember {
        mutableStateOf(
            "AIFactory 独立 Shell 已接入。\n" +
            "工作目录：AIFactory 私有 workspace"
        )
    }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "AIFactory Runtime",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "独立本地 Shell · 不依赖 Termux App",
            color = Muted,
            fontSize = 12.sp
        )

        OutlinedTextField(
            command,
            { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Shell 命令") },
            minLines = 2,
            enabled = !running
        )

        Button(
            enabled = command.isNotBlank() && !running,
            onClick = {
                scope.launch {
                    running = true
                    result = "正在执行……"
                    try {
                        result = runCommand(command)
                    } catch (e: Exception) {
                        result = "执行失败：${e.message}"
                    } finally {
                        running = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (running) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("执行中")
            } else {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("执行命令")
            }
        }

        Surface(
            Modifier.fillMaxWidth().weight(1f),
            color = Color(0xFF171715),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "终端输出",
                        color = Color(0xFFE4E1DA),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(result)
                            )
                        },
                        enabled = result.isNotEmpty()
                    ) {
                        Text("复制全部")
                    }
                }

                Divider(color = Color(0xFF393936))

                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxSize()
                    ) {
                        item {
                            Text(
                                result,
                                Modifier.fillMaxWidth().padding(14.dp),
                                color = Color(0xFFE4E1DA),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginsPage() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("插件中心", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("插件协议和隔离运行时属于后续里程碑。首版不执行未审查的第三方插件。", color = Muted)
        listOf("Gradle / Android Build", "CMake / Native Build", "Git Integration", "Python Runtime").forEach { name ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Extension, null, tint = Accent); Spacer(Modifier.width(12.dp)); Text(name, Modifier.weight(1f)); Text("规划中", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("AIFactory 0.1.0 · Android AI Development Workspace", color = Muted)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Termux 连接", fontWeight = FontWeight.SemiBold)
                Text("1. 安装并启动 Termux\n2. 在 Termux 中设置 allow-external-apps=true\n3. 重启 Termux\n4. 在 Android 应用权限中允许 AIFactory 使用 RUN_COMMAND", fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("安全策略", fontWeight = FontWeight.SemiBold)
                Text("构建、测试和工作区内修改可自动化。删除数据、访问凭据、网络外发、安装插件和正式发布需要审批。", fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}
