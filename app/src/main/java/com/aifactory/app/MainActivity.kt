package com.aifactory.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal val Muted = Color(0xFF85827C)
internal val Accent = Color(0xFFB66B45)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 28 走 legacy storage：拿到 READ/WRITE_EXTERNAL_STORAGE 后终端才能读 /sdcard
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val need = listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1001)
        }
        Thread { runCatching { RuntimeRepair.ensureUsr(applicationContext) } }.start()
        setContent {
            AIFactoryApp { command ->
                RuntimeEngine(applicationContext).execute(command)
            }
        }
    }
}

internal data class ChatMessage(val role: String, val text: String, val meta: String = "")
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
    val termHost = remember { TerminalHost(context) }
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
                    Page.PROJECTS -> ProjectsScreen()
                    Page.FILES -> FileCenterPage(
                        files = importedFiles,
                        onImport = launchFilePicker,
                        onOpen = { file ->
                            val error = openImportedFile(context, file)
                            if (error != null) {
                                android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onCopyToProjects = { file ->
                            val err = copyFileToProjects(context, file)
                            android.widget.Toast.makeText(
                                context,
                                err ?: ("已复制到 ~/projects/" + file.name),
                                if (err == null) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG
                            ).show()
                        },
                        onRemove = { file ->
                            importedFiles.remove(file)
                            chatAttachments.removeAll { it.uri == file.uri }
                            persistFileRecords(context, importedFiles)
                        }
                    )
                    Page.TERMINAL -> TerminalPage(termHost, dark) { page = Page.SETTINGS }
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
    // 会话状态挂在 ChatRunner（进程级）：切页面、退到后台、甚至重启 App 都不丢
    val messages = ChatRunner.messages
    var input by remember { mutableStateOf(ChatRunner.draft) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChatRunner.load(ctx.applicationContext) }
    val cfg = remember { AppConfig(ctx.applicationContext) }
    var agentMode by remember { mutableStateOf(true) }
    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var model by remember { mutableStateOf(cfg.model) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var bkBase by remember { mutableStateOf(cfg.backupBaseUrl) }
    var bkModel by remember { mutableStateOf(cfg.backupModel) }
    var bkKey by remember { mutableStateOf(cfg.backupApiKey) }
    var showBkKey by remember { mutableStateOf(false) }
    var maxSteps by remember { mutableStateOf(cfg.maxSteps.toString()) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val busy = ChatRunner.busy
    val sessionList = ChatRunner.sessions
    val rtInfo = remember { RuntimeEngine(ctx.applicationContext) }
    // 从上次位置开始渲染：切 tab 回来不再从第 1 条慢慢扫到底
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ChatRunner.scrollIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { ChatRunner.scrollIndex = it }
    }
    // 只有“你本来就在底部”或 Agent 正在输出时才播放滚动动画；刚进页面直接定位，不播长距离动画
    var landed by remember(ChatRunner.currentId) { mutableStateOf(false) }
    LaunchedEffect(messages.size, busy, ChatRunner.currentId) {
        if (messages.isEmpty()) return@LaunchedEffect
        val last = messages.size - 1
        if (!landed) {
            val keep = ChatRunner.scrollIndex.coerceIn(0, last)
            listState.scrollToItem(if (keep > 0) keep else last)
            landed = true
            return@LaunchedEffect
        }
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (busy || visibleLast >= last - 2) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Accent.copy(alpha = .14f)) { Icon(Icons.Default.AutoAwesome, null, Modifier.padding(9.dp), tint = Accent) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("AI 工程师", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    (if (apiKey.isBlank()) "模型未配置" else "$model") +
                        (if (rtInfo.isInstalled()) " · Runtime 本机 ✓" else " · Runtime 未装") +
                        (WorkState.currentProject?.let { " · 项目 " + it } ?: " · 未选项目"),
                    color = if (rtInfo.isInstalled()) Muted else MaterialTheme.colorScheme.error,
                    fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { agentMode = !agentMode }) { Text(if (agentMode) "Agent：开" else "Agent：关") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { ChatRunner.newChat(ctx.applicationContext) }) { Text("＋ 新对话", fontSize = 12.sp) }
            TextButton(onClick = { showHistory = !showHistory }) { Text("历史", fontSize = 12.sp) }
            TextButton(onClick = { showSettings = !showSettings }) { Text("模型设置", fontSize = 12.sp) }
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("chat", messages.joinToString("\n\n") { it.text }))
                Pipeline.appendNote("已复制本会话 " + messages.size + " 条消息")
            }) { Text("复制全部", fontSize = 12.sp) }
        }
        if (showHistory) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("历史对话（每个会话独立上下文，换主题请新建）", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (sessionList.isEmpty()) Text("还没有会话。", color = Muted, fontSize = 11.sp)
                    sessionList.take(20).forEach { m ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (m.id == ChatRunner.currentId) "● " else "") + m.title,
                                Modifier.weight(1f).clickable { ChatRunner.switchTo(ctx.applicationContext, m.id); showHistory = false },
                                fontSize = 12.sp, maxLines = 1
                            )
                            Text(android.text.format.DateFormat.format("MM-dd HH:mm", m.updated).toString(), color = Muted, fontSize = 10.sp)
                            TextButton(enabled = m.id != ChatRunner.currentId && !busy, onClick = { ChatRunner.delete(ctx.applicationContext, m.id) }) { Text("删除", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
        if (showSettings) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(baseUrl, { baseUrl = it; cfg.baseUrl = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it; cfg.model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(maxSteps, { maxSteps = it; it.toIntOrNull()?.let { n -> cfg.maxSteps = n } }, label = { Text("每条消息最大工具调用次数（默认 200）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it; cfg.apiKey = it }, label = { Text("API Key（保存在应用私有存储）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("兼容 OpenAI /chat/completions 接口；本地 llama.cpp server 可填 http://127.0.0.1:8080/v1。Agent 模式需要模型支持工具调用（不支持时自动退回文本协议）。", color = Muted, fontSize = 11.sp)

                    Text("———— 主通道 ↑ / 备用通道 ↓ ————", color = Muted, fontSize = 10.sp)
                    Text(
                        if (cfg.hasBackup) "备用通道（主通道 429 自动切换）· 已配 " + cfg.backupModel
                        else "备用通道（主通道 429 自动切换）· 未配，长任务撞限流会中断",
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        color = if (cfg.hasBackup) Accent else Muted
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple("硅基 GLM-Z1-9B", "https://api.siliconflow.cn/v1", "zhipuai/GLM-Z1-9B-0414"),
                            Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                            Triple("本地 llama.cpp", "http://127.0.0.1:8080/v1", "local"),
                            Triple("清空", "", "")
                        ).forEach { (label, u, m) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (label == "清空") MaterialTheme.colorScheme.surface else Accent.copy(alpha = .12f),
                                modifier = Modifier.clickable {
                                    bkBase = u; cfg.backupBaseUrl = u
                                    bkModel = m; cfg.backupModel = m
                                    if (label == "本地 llama.cpp" || label == "清空") { bkKey = ""; cfg.backupApiKey = "" }
                                }
                            ) { Text(label, Modifier.padding(horizontal = 9.dp, vertical = 7.dp), fontSize = 11.sp, color = if (label == "清空") Muted else Accent, maxLines = 1, softWrap = false) }
                        }
                    }
                    OutlinedTextField(bkBase, { bkBase = it; cfg.backupBaseUrl = it }, label = { Text("备用 API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bkModel, { bkModel = it; cfg.backupModel = it }, label = { Text("备用模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        bkKey, { bkKey = it; cfg.backupApiKey = it },
                        label = { Text("备用 API Key（主/备可以是不同厂商）") },
                        singleLine = true,
                        visualTransformation = if (showBkKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showBkKey = !showBkKey }) { Text(if (showBkKey) "隐藏" else "显示", fontSize = 11.sp) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                var copied by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start
                ) {
                    Surface(
                        color = if (message.role == "user") MaterialTheme.colorScheme.primary.copy(alpha = .13f) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)
                    ) { Text(message.text, Modifier.padding(14.dp), fontSize = 14.sp, lineHeight = 21.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.meta.isNotBlank()) Text(message.meta, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                        TextButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", message.text))
                            copied = true
                        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text(if (copied) "已复制" else "复制", fontSize = 10.sp, color = Muted)
                        }
                    }
                }
            }
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(if (agentMode) "Agent 工作中…" else "正在请求模型…", color = Muted, fontSize = 12.sp); Spacer(Modifier.width(8.dp)); TextButton(onClick = { ChatRunner.stop() }) { Text("停止") } } }
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
                    OutlinedTextField(input, { input = it; ChatRunner.draft = it }, Modifier.weight(1f), placeholder = { Text("描述你想开发的软件…") }, shape = RoundedCornerShape(18.dp), maxLines = 5)
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = input.isNotBlank() && !busy, onClick = {
                        val names = attachments.map { it.name }
                        val prompt = input.trim() +
                            if (names.isNotEmpty()) "\n\n附件文件：\n" + names.joinToString("\n") else ""
                        input = ""
                        ChatRunner.draft = ""
                        val attachNow = attachments.toList()
                        attachments.forEach { onRemoveAttachment(it) }
                        ChatRunner.send(ctx.applicationContext, agentMode, prompt, attachNow)
                    }) { Icon(Icons.Default.ArrowUpward, "发送", tint = Accent) }
                }
                Text(if (agentMode) "Agent 模式：可读写工作区文件、执行命令、构建并签名 APK；安装需你在系统弹窗确认。" else "普通对话模式：不调用任何工具。", Modifier.padding(start = 4.dp, top = 5.dp), color = Muted, fontSize = 10.sp)
            }
        }
    }
}

internal fun requestChat(base: String, model: String, key: String, history: List<ChatMessage>): String {
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
private fun GitHubCard() {
    val ctx = LocalContext.current
    val cfg = remember { AppConfig(ctx.applicationContext) }
    var repo by remember { mutableStateOf(cfg.ghRepo) }
    var token by remember { mutableStateOf(cfg.ghToken) }
    var tag by remember { mutableStateOf(cfg.ghTagPrefix) }
    var status by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun open(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            status = "打不开浏览器：" + e.message
        }
    }
    fun clipText(): String = try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
    } catch (e: Exception) { "" }
    fun copy(text: String) {
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("aifactory", text))
            status = "已复制：" + text
        } catch (e: Exception) { status = "复制失败：" + e.message }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发布：GitHub", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open("https://github.com/settings/tokens/new?scopes=repo&description=AIFactory") }) { Text("① 打开 Token 页") }
                OutlinedButton(onClick = {
                    val name = (WorkState.currentProject ?: "aifactory-app").lowercase().replace(Regex("[^a-z0-9._-]"), "-")
                    open("https://github.com/new?name=" + name + "&visibility=public")
                }) { Text("② 建仓库页") }
            }
            Text("① 里选 repo 权限 → Generate → 复制 github_pat_ 开头那串 → 回来粘到下面。② 可跳过：仓库留空时首次连接自动建。", fontSize = 11.sp, color = Muted)
            OutlinedTextField(
                token, { token = it; cfg.ghToken = it },
                label = { Text("Token（需 repo 权限）") },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "隐藏" else "显示") }
                },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copy(token) }, enabled = token.isNotBlank()) { Text("复制 Token") }
                TextButton(onClick = {
                    val c = clipText()
                    if (c.isBlank()) status = "剪贴板里没有文本；先在浏览器复制 Token"
                    else if (!c.startsWith("gh") && c.length < 20) status = "剪贴板内容不像 Token（" + c.length + " 字符，开头 " + c.take(6) + "）"
                    else { token = c; cfg.ghToken = c; status = "已从剪贴板填入 Token（" + c.length + " 字符）" }
                }) { Text("从剪贴板粘贴") }
            }
            OutlinedTextField(repo, { repo = it; cfg.ghRepo = it }, label = { Text("仓库 owner/repo（可留空）") }, placeholder = { Text("留空 = 连接时自动新建") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tag, { tag = it; cfg.ghTagPrefix = it }, label = { Text("tag 前缀（默认 build）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = token.isNotBlank() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val log = StringBuilder()
                        val who = withContext(Dispatchers.IO) { Publisher.whoami(cfg) { log.appendLine(it) } }
                        if (who.first !in 200..299) {
                            withContext(Dispatchers.Main) { status = "❌ 连接失败（HTTP " + who.first + "）：" + who.second; busy = false }
                            return@launch
                        }
                        val name = who.second
                        val existing = cfg.ghRepo.trim()
                        val r = withContext(Dispatchers.IO) {
                            if (existing.contains("/")) {
                                val c = Publisher.checkRepo(cfg, existing) { }
                                if (c.first in 200..299) Publisher.Result(true, "仓库可访问：" + existing, "https://github.com/" + existing)
                                else Publisher.Result(false, "仓库不可访问（HTTP " + c.first + "）：" + c.second, null)
                            } else {
                                Publisher.ensureRepo(cfg, WorkState.currentProject ?: "aifactory-app") { line -> log.appendLine(line) }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            login = name
                            repo = cfg.ghRepo
                            if (r.ok) {
                                link = r.releaseUrl
                                status = "✅ 已连接 @" + name + "\n" + r.summary + "\n" + (r.releaseUrl ?: "")
                            } else {
                                status = "❌ " + r.summary
                            }
                            busy = false
                        }
                    }
                }) { Text(if (busy) "正在连接…" else "③ 连接并检查") }
                if (link != null) OutlinedButton(onClick = { open(link!!) }) { Text("打开仓库") }
                if (link != null) TextButton(onClick = { copy(link!!) }) { Text("复制地址") }
            }
            login?.let { Text("账号：@" + it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
            status?.let { Text(it, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace) }
            Text("Token 只存应用私有存储，不会写进项目或日志。手填 owner/repo 也能用已有仓库。", fontSize = 11.sp, color = Muted)
            Text(
                "Google Play（2026 现状，别被旧文案误导）：\n" +
                "① 本机可以产 AAB：项目页「构建 AAB（上架）」走 aapt2 --proto-format → bundletool → jarsigner，产物在 files/outputs/*.aab。\n" +
                "② 但 Play 只收 targetSdk ≥ 36 的应用，而 AIFactory 自身必须 targetSdk 28 才能 exec 私有目录二进制；且它要下发执行 ELF，命中 Device and Network Abuse 的“禁止从非 Play 来源下载可执行代码”。所以 AIFactory 本体永远上不了 Play。\n" +
                "③ 能上 Play 的是它编出来的普通应用（纯 java、无 .so）：用「构建 AAB」产 AAB，自己上传。\n" +
                "④ 自动上传要 Play 开发者账号 + 服务账号 JSON（apps 记录必须在 Console 手工建一次，API 建不了）。",
                fontSize = 11.sp, color = Muted
            )
        }
    }
}

@Composable
private fun AdbCard() {
    val ctx = LocalContext.current
    val rt = remember { RuntimeEngine(ctx.applicationContext) }
    val adb = remember { AdbBridge(ctx.applicationContext, rt) }
    val scope = rememberCoroutineScope()
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connPort by remember { mutableStateOf("5555") }
    var out by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("真机取证：无线调试 adb", fontWeight = FontWeight.SemiBold)
            Text(
                if (adb.available()) "adb 已就绪：" + (adb.bin()?.path ?: "")
                else "adb 未安装。Android 不允许普通 App 读别的进程 logcat，所以「看到真实崩溃栈」只有 adb 这一条路。",
                fontSize = 11.sp, color = Muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pairPort, { pairPort = it }, label = { Text("配对端口") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(pairCode, { pairCode = it }, label = { Text("配对码") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("开发者选项 → 无线调试 → 使用配对码配对设备，界面上会给「IP:端口」和 6 位码。", fontSize = 11.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(connPort, { connPort = it }, label = { Text("连接端口") }, singleLine = true, modifier = Modifier.width(120.dp))
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val msg = adb.pair(pairPort, pairCode)
                        withContext(Dispatchers.Main) { out = msg; busy = false }
                    }
                }) { Text("配对") }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val msg = adb.connect(connPort.ifBlank { "5555" })
                        withContext(Dispatchers.Main) { out = msg; busy = false }
                    }
                }) { Text("连接") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val msg = if (adb.available()) adb.devices()
                        else rt.run("apt-get install -y android-tools", RuntimePaths.home(ctx), 300).let {
                            if (it.exitCode == 0 && AdbBridge(ctx, rt).available()) "adb 已安装：" + (AdbBridge(ctx, rt).bin()?.path ?: "")
                            else "安装失败（退出码 " + it.exitCode + "）：\n" + tailText(it.output, 800) +
                                "\n本地 apt 源里没有 android-tools（它必须在 CI 里用本前缀编译，官方 Termux 包直接搬会因 rpath 指向 com.termux 起不来）。"
                        }
                        withContext(Dispatchers.Main) { out = msg; busy = false }
                    }
                }) { Text(if (adb.available()) "检查连接" else "安装 adb") }
            }
            out?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp) }
        }
    }
}


@Composable
private fun SettingsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("AIFactory ${BuildConfig.VERSION_NAME} · 独立 Runtime · Android AI Development Workspace", color = Muted)
        GuideCard()
        GitHubCard()
        AdbCard()
        RuntimeSetupCard()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("安全策略", fontWeight = FontWeight.SemiBold)
                Text("构建、测试和工作区内修改可自动化。删除数据、访问凭据、网络外发、安装插件和正式发布需要审批。", fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}
