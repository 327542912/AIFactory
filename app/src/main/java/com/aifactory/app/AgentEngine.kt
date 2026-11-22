package com.aifactory.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自主开发 Agent：模型 ⇄ 工具循环（读写文件、执行命令、构建 APK、安装 APK）。
 * 优先使用 OpenAI 兼容的原生 tool calling；服务端不支持时自动退回到 ```tool 代码块协议。
 */
class AgentEngine(private val ctx: Context) {

    private val rt = RuntimeEngine(ctx)
    private val packager = Packager(ctx, rt)
    private val workspace = RuntimePaths.workspace(ctx)
    private val history = JSONArray()
    private var nativeTools = true

    companion object {
        private val FENCE = Regex("```tool\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
        private val DANGEROUS = Regex("rm\\s+-[a-zA-Z]*[rR][a-zA-Z]*\\s+(/|~|\\\$HOME|\\\$PREFIX|/data)\\s*(\$|;|&)")
    }

    fun reset() {
        while (history.length() > 0) history.remove(0)
    }

    // ---------- 工具定义 ----------

    private class ToolDef(val name: String, val desc: String, val params: Map<String, String>, val required: List<String>)

    private val tools = listOf(
        ToolDef("run_shell", "在 Runtime 中执行 shell 命令（bash）。默认工作目录为工作区。",
            mapOf("command" to "要执行的命令", "cwd" to "相对工作区的目录，可选", "timeout_sec" to "超时秒数，默认 120，最大 3600"),
            listOf("command")),
        ToolDef("read_file", "读取工作区内的文本文件。", mapOf("path" to "相对工作区的路径"), listOf("path")),
        ToolDef("write_file", "创建或覆盖工作区内的文件（自动创建父目录）。",
            mapOf("path" to "相对工作区的路径", "content" to "完整文件内容"), listOf("path", "content")),
        ToolDef("replace_in_file", "把文件中唯一出现的一段文本替换为新文本。",
            mapOf("path" to "文件路径", "old" to "要替换的原文（必须唯一）", "new" to "新文本"), listOf("path", "old", "new")),
        ToolDef("list_dir", "列出目录内容。", mapOf("path" to "相对工作区的目录，默认工作区根"), emptyList()),
        ToolDef("toolchain_status", "查看 JDK/Gradle/SDK/aapt2/apksigner 是否就绪。", emptyMap(), emptyList()),
        ToolDef("build_apk", "用 Gradle 构建项目并（release 时）签名，返回 APK 路径或错误日志。",
            mapOf("project" to "项目目录（相对工作区）", "release" to "true=签名的 release，false=debug（默认）"), listOf("project")),
        ToolDef("install_apk", "唤起系统安装器安装 APK（需要用户在弹窗中确认）。",
            mapOf("path" to "APK 路径（build_apk 返回的路径）"), listOf("path"))
    )

    private fun toolSpecs(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val props = JSONObject()
            t.params.forEach { (k, v) -> props.put(k, JSONObject().put("type", "string").put("description", v)) }
            arr.put(
                JSONObject().put("type", "function").put(
                    "function", JSONObject()
                        .put("name", t.name).put("description", t.desc)
                        .put("parameters", JSONObject().put("type", "object").put("properties", props)
                            .put("required", JSONArray(t.required)))
                )
            )
        }
        return arr
    }

    private fun systemPrompt(): String = buildString {
        appendLine("你是 AIFactory 内置的自主软件工程师，运行在用户的 Android 手机上，拥有一个独立的类 Linux Runtime（不依赖 Termux）。")
        appendLine("工作区：${workspace.path}（每个项目一个子目录）。所有文件工具只能访问工作区。")
        appendLine("工作流程：简述计划 → 用 write_file 创建/修改文件 → 用 build_apk 构建 → 失败时阅读日志、修复、重新构建（同一问题最多重试 5 次）→ 成功后汇报 APK 路径。")
        appendLine("只有用户明确要求安装时才调用 install_apk。不确定环境时先调用 toolchain_status；缺少工具链时告诉用户到 设置 → Runtime 安装，不要自行下载未知来源的二进制。")
        appendLine("可以用 pkg install / pip / npm 安装依赖。pkg 的软件源是本项目自建仓库，只包含已发布的包；找不到时告诉用户把包名加入 GitHub 工作流的 repo_packages 重新发布。不要手动改 apt 源为官方 Termux 仓库（路径前缀不同，会损坏 Runtime）。")
        appendLine("删除、覆盖大量文件前先向用户说明。回答使用中文，简洁。")
        if (!nativeTools) {
            appendLine()
            appendLine("【工具调用协议】需要调用工具时，只输出一个如下格式的代码块，然后停止，等待工具结果：")
            appendLine("```tool")
            appendLine("""{"name":"工具名","arguments":{"参数":"值"}}""")
            appendLine("```")
            appendLine("可用工具：")
            for (t in tools) {
                appendLine("- ${t.name}(${t.params.keys.joinToString(", ")})：${t.desc}")
                t.params.forEach { (k, v) -> appendLine("    $k：$v") }
            }
        }
    }

    // ---------- 主循环 ----------

    suspend fun run(userText: String, cfg: AppConfig, onEvent: (String) -> Unit): String {
        suspend fun emit(s: String) = withContext(Dispatchers.Main) { onEvent(s) }
        history.put(JSONObject().put("role", "user").put("content", userText))
        val limit = cfg.maxSteps.coerceAtLeast(1)
        var lastSig = ""
        var repeats = 0
        // 连续出现完全相同的调用和结果 = 卡住了：4 次提醒换思路，8 次暂停
        fun noteSig(sig: String) {
            if (sig == lastSig) repeats++ else { lastSig = sig; repeats = 1 }
        }
        rt.initialize()
        KeepAliveService.start(ctx)
        try {
            for (step in 1..limit) {
                currentCoroutineContext().ensureActive()
                compactHistory()
                var resp = withContext(Dispatchers.IO) { callModel(cfg) }
                if (resp.first == 400 && nativeTools && resp.second.contains("tool", ignoreCase = true)) {
                    nativeTools = false
                    emit("服务端不支持原生工具调用，已切换到文本协议。")
                    resp = withContext(Dispatchers.IO) { callModel(cfg) }
                }
                if (resp.first !in 200..299) return "模型 API 错误（HTTP ${resp.first}）：${tailText(resp.second, 800)}"

                val msg = try {
                    JSONObject(resp.second).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                } catch (e: Exception) {
                    return "无法解析模型响应：${tailText(resp.second, 500)}"
                }
                val content = if (msg.isNull("content")) "" else msg.getString("content")
                val calls = msg.optJSONArray("tool_calls")

                if (nativeTools && calls != null && calls.length() > 0) {
                    history.put(JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls))
                    if (content.isNotBlank()) emit(content)
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = parseArgs(fn.opt("arguments"))
                        emit("🔧 $name ${briefArgs(args)}")
                        val result = runTool(name, args)
                        emit("↳ ${tailText(result, 500)}")
                        history.put(
                            JSONObject().put("role", "tool").put("tool_call_id", call.getString("id"))
                                .put("content", tailText(result, 12000))
                        )
                        noteSig(name + args.toString() + result.hashCode())
                    }
                    if (repeats >= 8) return "检测到连续 8 次完全相同的操作和结果，已暂停。请告诉我新的方向，或回复“继续”。"
                    if (repeats == 4) history.put(JSONObject().put("role", "user")
                        .put("content", "系统提示：你已连续 4 次执行完全相同的操作并得到相同结果，请换一种思路（例如先读相关文件或日志）。"))
                    continue
                }

                val fenced = if (!nativeTools) FENCE.find(content) else null
                if (fenced != null) {
                    history.put(JSONObject().put("role", "assistant").put("content", content))
                    val before = content.substring(0, fenced.range.first).trim()
                    if (before.isNotEmpty()) emit(before)
                    val result = try {
                        val j = JSONObject(fenced.groupValues[1])
                        val name = j.getString("name")
                        val args = parseArgs(j.opt("arguments"))
                        emit("🔧 $name ${briefArgs(args)}")
                        runTool(name, args)
                    } catch (e: Exception) {
                        "工具调用格式错误：${e.message}"
                    }
                    emit("↳ ${tailText(result, 500)}")
                    history.put(JSONObject().put("role", "user").put("content", "工具结果：\n" + tailText(result, 12000)))
                    noteSig(fenced.groupValues[1] + result.hashCode())
                    if (repeats >= 8) return "检测到连续 8 次完全相同的操作和结果，已暂停。请告诉我新的方向，或回复“继续”。"
                    continue
                }

                history.put(JSONObject().put("role", "assistant").put("content", content))
                return content.ifBlank { "（模型没有返回内容）" }
            }
            return "已达到本轮工具调用上限（$limit 次），任务暂停。回复“继续”我接着做，或在模型设置里调大上限。"
        } catch (e: CancellationException) {
            throw e
        } finally {
            KeepAliveService.stop(ctx)
        }
    }

    private fun parseArgs(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> if (raw.isBlank()) JSONObject() else try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        else -> JSONObject()
    }

    private fun briefArgs(a: JSONObject): String {
        val s = a.toString()
        return if (s.length > 160) s.take(160) + "…" else s
    }

    /** 控制上下文长度：较早的工具结果压缩到 600 字符。 */
    private fun compactHistory() {
        var total = 0
        for (i in 0 until history.length()) total += history.getJSONObject(i).optString("content", "").length
        if (total < 60000) return
        for (i in 0 until history.length() - 8) {
            val m = history.getJSONObject(i)
            val c = m.optString("content", "")
            val isTool = m.optString("role") == "tool" || c.startsWith("工具结果：")
            if (isTool && c.length > 800) m.put("content", c.take(600) + "\n…(已压缩)")
        }
    }

    private fun callModel(cfg: AppConfig): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(cfg.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20000; readTimeout = 300000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
            val body = JSONObject().put("model", cfg.model).put("messages", messages).put("temperature", 0.2)
            if (nativeTools) body.put("tools", toolSpecs()).put("tool_choice", "auto")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: Exception) {
            Pair(-1, "请求失败：${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    // ---------- 工具实现 ----------

    private fun resolve(p: String): File {
        val f = if (p.startsWith("/")) File(p) else File(workspace, p)
        val c = f.canonicalFile
        val root = workspace.canonicalFile
        require(c.path == root.path || c.path.startsWith(root.path + File.separator)) { "路径超出工作区：$p" }
        return c
    }

    private suspend fun runTool(name: String, a: JSONObject): String = try {
        when (name) {
            "run_shell" -> {
                val cmd = a.optString("command")
                if (DANGEROUS.containsMatchIn(cmd)) "已拒绝：该命令可能删除系统或 Runtime 目录。"
                else {
                    val cwd = a.optString("cwd").takeIf { it.isNotBlank() }?.let { resolve(it) } ?: workspace
                    val t = (a.optString("timeout_sec").toIntOrNull() ?: 120).coerceIn(1, 3600)
                    val r = rt.run(cmd, cwd, t)
                    val status = when {
                        r.timedOut -> "超时（${t}s）已终止"
                        r.cancelled -> "已取消"
                        else -> "退出码 ${r.exitCode}"
                    }
                    "$status\n" + tailText(r.output.ifBlank { "(没有输出)" }, 10000)
                }
            }
            "read_file" -> {
                val f = resolve(a.getString("path"))
                if (!f.isFile) "文件不存在：${f.path}"
                else if (f.length() > 400_000) "文件过大（${f.length()} 字节），请用 run_shell 的 head/sed 分段读取。"
                else f.readText()
            }
            "write_file" -> {
                val f = resolve(a.getString("path"))
                f.parentFile?.mkdirs()
                val content = a.getString("content")
                f.writeText(content)
                "已写入 ${f.path}（${content.length} 字符）"
            }
            "replace_in_file" -> {
                val f = resolve(a.getString("path"))
                val old = a.getString("old")
                val text = f.readText()
                val n = text.split(old).size - 1
                when {
                    n == 0 -> "未找到要替换的文本。"
                    n > 1 -> "原文出现 $n 次，不唯一，请提供更长的上下文。"
                    else -> { f.writeText(text.replace(old, a.getString("new"))); "已替换。" }
                }
            }
            "list_dir" -> {
                val d = resolve(a.optString("path").ifBlank { "." })
                val items = d.listFiles()?.sortedBy { it.name }
                    ?: return "目录不存在：${d.path}"
                items.joinToString("\n") { (if (it.isDirectory) "[d] " else "    ") + it.name }.ifBlank { "(空目录)" }
            }
            "toolchain_status" -> packager.toolchainReport()
            "build_apk" -> {
                val dir = resolve(a.getString("project"))
                val release = a.optString("release").equals("true", ignoreCase = true)
                val r = packager.build(dir, release)
                if (r.ok) "${r.message}\nAPK 路径：${r.apk?.path}"
                else "${r.message}\n--- 构建日志（末尾）---\n${tailText(r.log, 7000)}"
            }
            "install_apk" -> {
                val p = a.getString("path")
                val f = File(p).canonicalFile
                val outputs = RuntimePaths.outputs(ctx).canonicalFile
                if (!f.path.startsWith(outputs.path + File.separator)) "只能安装 build_apk 输出目录中的 APK。"
                else withContext(Dispatchers.Main) { Installer.install(ctx, f) }
            }
            else -> "未知工具：$name"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "工具执行出错：${e.message}"
    }
}
