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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

internal val Muted = Color(0xFF85827C)
internal val Accent = Color(0xFFB66B45)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIFactoryApp { command ->
                RuntimeEngine(applicationContext).execute(command)
            }
        }
    }
}

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
    val termBridge = remember { AiTerminalBridge(context) }
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
                        onRemove = { file ->
                            importedFiles.remove(file)
                            chatAttachments.removeAll { it.uri == file.uri }
                            persistFileRecords(context, importedFiles)
                        }
                    )
                    Page.TERMINAL -> TerminalPage(termBridge)
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
    val ctx = LocalContext.current
    val cfg = remember { AppConfig(ctx.applicationContext) }
    val agent = remember { AgentEngine(ctx.applicationContext) }
    var agentMode by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var model by remember { mutableStateOf(cfg.model) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var maxSteps by remember { mutableStateOf(cfg.maxSteps.toString()) }
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
            TextButton(onClick = { agentMode = !agentMode }) { Text(if (agentMode) "Agent：开" else "Agent：关") }
            TextButton(onClick = { showSettings = !showSettings }) { Text("模型设置") }
        }
        if (showSettings) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(baseUrl, { baseUrl = it; cfg.baseUrl = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it; cfg.model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(maxSteps, { maxSteps = it; it.toIntOrNull()?.let { n -> cfg.maxSteps = n } }, label = { Text("每条消息最大工具调用次数（默认 200）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it; cfg.apiKey = it }, label = { Text("API Key（保存在应用私有存储）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("兼容 OpenAI /chat/completions 接口；本地 llama.cpp server 可填 http://127.0.0.1:8080/v1。Agent 模式需要模型支持工具调用（不支持时自动退回文本协议）。", color = Muted, fontSize = 11.sp)
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
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(if (agentMode) "Agent 工作中…" else "正在请求模型…", color = Muted, fontSize = 12.sp); Spacer(Modifier.width(8.dp)); TextButton(onClick = { job?.cancel() }) { Text("停止") } } }
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
                            if (names.isNotEmpty()) "\n\n附件文件：\n" + names.joinToString("\n") else ""
                        input = ""
                        messages.add(ChatMessage("user", prompt))
                        attachments.toList().forEach { onRemoveAttachment(it) }
                        if (apiKey.isBlank()) messages.add(ChatMessage("assistant", "请先打开模型设置，填写 API 地址、模型名称和 API Key。"))
                        else {
                            busy = true
                            job = scope.launch {
                                try {
                                    if (agentMode) {
                                        val answer = agent.run(prompt, cfg) { ev -> messages.add(ChatMessage("assistant", ev)) }
                                        messages.add(ChatMessage("assistant", answer))
                                    } else {
                                        val answer = withContext(Dispatchers.IO) { requestChat(baseUrl, model, apiKey, messages.toList()) }
                                        messages.add(ChatMessage("assistant", answer))
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    messages.add(ChatMessage("assistant", "已停止。"))
                                    throw e
                                } catch (e: Throwable) {
                                    messages.add(ChatMessage("assistant", "出错：${e.message}"))
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }) { Icon(Icons.Default.ArrowUpward, "发送", tint = Accent) }
                }
                Text(if (agentMode) "Agent 模式：可读写工作区文件、执行命令、构建并签名 APK；安装需你在系统弹窗确认。" else "普通对话模式：不调用任何工具。", Modifier.padding(start = 4.dp, top = 5.dp), color = Muted, fontSize = 10.sp)
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
private fun SettingsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("AIFactory ${BuildConfig.VERSION_NAME} · 独立 Runtime · Android AI Development Workspace", color = Muted)
        RuntimeSetupCard()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("安全策略", fontWeight = FontWeight.SemiBold)
                Text("构建、测试和工作区内修改可自动化。删除数据、访问凭据、网络外发、安装插件和正式发布需要审批。", fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}
