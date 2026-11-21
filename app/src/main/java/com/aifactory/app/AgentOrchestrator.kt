package com.aifactory.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenAI-compatible tool-call loop for safe project inspection.
 * Source changes are proposals only and require an explicit editor save.
 * Build/test require a separate user opt-in and use ProjectCommandRunner's allowlist.
 */
class AgentOrchestrator(private val context: Context) {
    private val workspace = EngineeringWorkspace(context)
    private val commandRunner = ProjectCommandRunner()
    private val pendingChanges = PendingChangeStore(context)
    private val taskEngine = AgentTaskEngine(context)

    suspend fun respond(
        baseUrl: String,
        model: String,
        apiKey: String,
        history: List<ChatMessage>,
        allowBuildTest: Boolean
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            "你是 AIFactory 工程代理。先列出项目，再扫描并按需读取源码。用户要求工程计划时，使用 create_task_plan 保存带依赖的任务 DAG；用户询问进度时，使用 get_task_status。只能通过已注册工具检查项目；不要声称执行过未执行的操作。不要猜测项目 ID。未经用户明确授权不得构建或测试。如需修改源码，必须调用 propose_file_change 提交完整文件建议；该工具只创建待审批项，绝不直接写入。明确告诉用户到项目页面载入全文审阅并手动保存后才会应用。"))
        history.forEach { item ->
            messages.put(JSONObject().put("role", item.role).put("content", item.text))
        }
        val tools = toolDefinitions()
        var toolCount = 0

        repeat(6) {
            val response = withContext(Dispatchers.IO) {
                postChat(baseUrl, model, apiKey, messages, tools)
            }
            val choices = response.optJSONArray("choices")
                ?: return "模型响应缺少 choices：${response}"
            if (choices.length() == 0) return "模型没有返回候选消息。"
            val assistant = choices.getJSONObject(0).optJSONObject("message")
                ?: return "模型响应缺少 message。"
            val calls = assistant.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return assistant.optString("content").ifBlank { "模型没有返回文本。" }
            }

            messages.put(assistant)
            for (i in 0 until calls.length()) {
                if (++toolCount > 12) return "已达到本轮工具调用上限（12 次），请缩小任务范围。"
                val call = calls.getJSONObject(i)
                val callId = call.optString("id")
                val fn = call.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val args = try { JSONObject(fn.optString("arguments", "{}")) }
                    catch (_: Exception) { JSONObject() }
                val result = try {
                    executeTool(name, args, allowBuildTest)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "工具执行失败").toString()
                }
                messages.put(
                    JSONObject().put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result)
                )
            }
        }
        return "已达到最多 6 轮模型工具调用限制。当前工具结果已保留，请继续指定下一步。"
    }

    private fun postChat(
        baseUrl: String,
        model: String,
        apiKey: String,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("temperature", 0.2)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("模型 API HTTP $code：$raw")
            return JSONObject(raw)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun executeTool(name: String, args: JSONObject, allowBuildTest: Boolean): String {
        return when (name) {
            "list_projects" -> {
                val array = JSONArray()
                workspace.listProjects().forEach { p ->
                    array.put(JSONObject().put("id", p.id).put("name", p.name)
                        .put("kind", p.kind).put("file_count", p.fileCount))
                }
                JSONObject().put("ok", true).put("projects", array).toString()
            }
            "scan_project" -> {
                val scan = workspace.scan(args.getString("project_id"))
                val files = JSONArray()
                scan.files.take(300).forEach { files.put(it) }
                JSONObject().put("ok", true).put("project_id", scan.project.id)
                    .put("project_name", scan.project.name).put("build_system", scan.buildSystem)
                    .put("build_command", scan.buildCommand ?: JSONObject.NULL)
                    .put("test_command", scan.testCommand ?: JSONObject.NULL)
                    .put("files", files).put("warnings", JSONArray(scan.warnings)).toString()
            }
            "read_project_file" -> {
                val path = args.getString("path")
                require(!isSensitivePath(path)) { "出于凭据保护，代理不能读取密钥、环境变量或凭据文件。" }
                val value = workspace.readFile(args.getString("project_id"), path, 96 * 1024)
                JSONObject().put("ok", true).put("path", path)
                    .put("content", value).toString()
            }
            "create_task_plan" -> {
                val inputTasks = args.getJSONArray("tasks")
                require(inputTasks.length() in 1..40) { "任务计划必须包含 1 到 40 项" }
                val tasks = (0 until inputTasks.length()).map { i ->
                    val item = inputTasks.getJSONObject(i)
                    val deps = item.optJSONArray("depends_on") ?: JSONArray()
                    NewTask(
                        title = item.getString("title"),
                        description = item.optString("description"),
                        kind = item.optString("kind", "manual"),
                        dependsOn = (0 until deps.length()).map { deps.getString(it) },
                        id = item.optString("id")
                    )
                }
                val created = taskEngine.createPlan(tasks)
                val ready = taskEngine.nextReady()
                val result = JSONArray()
                created.forEach { t ->
                    result.put(JSONObject().put("id", t.id).put("title", t.title)
                        .put("kind", t.kind).put("depends_on", JSONArray(t.dependsOn)))
                }
                JSONObject().put("ok", true).put("tasks", result)
                    .put("ready_count", ready.size)
                    .put("message", "任务 DAG 已保存。当前仅持久化计划，不代表任务已经执行。").toString()
            }
            "get_task_status" -> {
                val result = JSONArray()
                taskEngine.tasks().forEach { t ->
                    result.put(JSONObject().put("id", t.id).put("title", t.title)
                        .put("kind", t.kind).put("status", t.status.name)
                        .put("depends_on", JSONArray(t.dependsOn)).put("result", t.result))
                }
                JSONObject().put("ok", true).put("tasks", result)
                    .put("history_count", taskEngine.history().size).toString()
            }
            "propose_file_change" -> {
                val projectId = args.getString("project_id")
                val path = workspace.validateRelativePath(projectId, args.getString("path"))
                require(!isSensitivePath(path)) { "出于凭据保护，代理不能修改密钥、环境变量或凭据文件。" }
                val proposal = pendingChanges.add(
                    projectId, path, args.getString("content"), args.optString("reason")
                )
                JSONObject().put("ok", true).put("approval_required", true)
                    .put("proposal_id", proposal.id).put("path", proposal.path)
                    .put("message", "修改建议已保存为待审批项；没有写入源码。请用户到项目页面审阅并明确应用。").toString()
            }
            "build_project", "test_project" -> {
                if (!allowBuildTest) {
                    JSONObject().put("ok", false)
                        .put("approval_required", true)
                        .put("message", "用户尚未启用本轮构建/测试权限。请先请求用户明确开启构建与测试授权。").toString()
                } else {
                    val scan = workspace.scan(args.getString("project_id"))
                    val result = commandRunner.run(scan, name == "test_project")
                    JSONObject().put("ok", result.exitCode == 0 && !result.timedOut)
                        .put("command", result.command).put("exit_code", result.exitCode ?: JSONObject.NULL)
                        .put("timed_out", result.timedOut).put("duration_ms", result.durationMs)
                        .put("output", redactSecrets(result.output).take(12000)).toString()
                }
            }
            else -> JSONObject().put("ok", false).put("error", "未注册的工具：$name").toString()
        }
    }

    private fun isSensitivePath(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return name == ".env" || name.startsWith(".env.") ||
            name in setOf("local.properties", "keystore.properties", "credentials.json",
                "service-account.json", "secrets.json", "id_rsa", "id_ed25519") ||
            name.contains("secret") || name.contains("credential") ||
            ext in setOf("jks", "keystore", "p12", "pfx", "pem", "key")
    }

    private fun redactSecrets(value: String): String =
        value.replace(
            Regex("(?i)(api[_-]?key|access[_-]?token|password|passwd|secret|authorization)\\s*[:=]\\s*[^\\s,;]+"),
            "$1=[REDACTED]"
        )

    private fun toolDefinitions(): JSONArray {
        val tools = JSONArray()
        tools.put(tool("list_projects", "列出 AIFactory 私有工作区中的项目。", JSONObject()
            .put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)))
        tools.put(tool("scan_project", "扫描项目结构并识别构建和测试入口。", schema(
            "project_id", "string", "项目 ID", true
        )))
        tools.put(tool("read_project_file", "读取项目内 UTF-8 文本文件，最大 96 KB；路径必须相对项目根目录。", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("project_id", property("string", "项目 ID"))
                .put("path", property("string", "项目相对文件路径")))
            .put("required", JSONArray().put("project_id").put("path"))
            .put("additionalProperties", false)))
        tools.put(tool("create_task_plan", "保存带依赖关系的工程任务 DAG；只创建计划，不会执行任务。", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("tasks", JSONObject()
                .put("type", "array").put("minItems", 1).put("maxItems", 40)
                .put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("id", property("string", "任务 ID，依赖引用时必须提供"))
                        .put("title", property("string", "任务标题"))
                        .put("description", property("string", "任务说明"))
                        .put("kind", property("string", "scan/read/write/build/test/review/manual 之一"))
                        .put("depends_on", JSONObject().put("type", "array")
                            .put("items", JSONObject().put("type", "string"))))
                    .put("required", JSONArray().put("id").put("title").put("kind").put("depends_on"))
                    .put("additionalProperties", false)))
            .put("required", JSONArray().put("tasks"))
            .put("additionalProperties", false)))
        tools.put(tool("get_task_status", "读取当前持久化任务 DAG 和状态历史摘要。", JSONObject()
            .put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)))
        tools.put(tool("propose_file_change", "提出完整文件内容替换建议。只保存为待审批项，不会直接写入源码。", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("project_id", property("string", "项目 ID"))
                .put("path", property("string", "项目相对文件路径"))
                .put("content", property("string", "建议写入的完整 UTF-8 文件内容"))
                .put("reason", property("string", "修改理由")))
            .put("required", JSONArray().put("project_id").put("path").put("content").put("reason"))
            .put("additionalProperties", false)))
        tools.put(tool("build_project", "运行识别出的项目构建命令。只能在用户明确启用构建/测试权限后执行。", schema(
            "project_id", "string", "项目 ID", true
        )))
        tools.put(tool("test_project", "运行识别出的项目测试命令。只能在用户明确启用构建/测试权限后执行。", schema(
            "project_id", "string", "项目 ID", true
        )))
        return tools
    }

    private fun schema(name: String, type: String, description: String, required: Boolean): JSONObject =
        JSONObject().put("type", "object")
            .put("properties", JSONObject().put(name, property(type, description)))
            .put("required", if (required) JSONArray().put(name) else JSONArray())
            .put("additionalProperties", false)

    private fun property(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun tool(name: String, description: String, parameters: JSONObject) =
        JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name)
                .put("description", description).put("parameters", parameters))
}
