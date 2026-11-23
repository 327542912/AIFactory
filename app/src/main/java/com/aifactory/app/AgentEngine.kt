package com.aifactory.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        // 小模型经常写成 ```json / ```kotlin / 裸围栏，或把参数写成 args/parameters：全都容忍
        private val FENCE = Regex("```(?:tool|tool_call|json)?\\s*([\\{\\[].*?[\\}\\]])\\s*```", RegexOption.DOT_MATCHES_ALL)
        private val LOOSE_TOOL = Regex("(?:^|\\n)[ \\t]*(?:🔧\\s*)?([a-z][a-z0-9_]{2,})\\s+(\\{[^\\n]*\\})[ \\t]*(?=\\n|$)")
    private val KNOWN_TOOLS = setOf(
        "run_shell", "read_file", "write_file", "replace_in_file", "list_dir", "toolchain_status",
        "build_apk", "install_apk", "launch_app", "verify_app", "adb", "pipeline_status", "read_log",
        "download_url", "decompile_apk", "inspect_apk", "fetch_url", "remember"
    )
    private val DANGEROUS = Regex("rm\\s+-[a-zA-Z]*[rR][a-zA-Z]*\\s+(/|~|\\\$HOME|\\\$PREFIX|/data)\\s*(\$|;|&)")
    }

    fun reset() {
        while (history.length() > 0) history.remove(0)
    }

    // ---------- 工具定义 ----------

    private class ToolDef(val name: String, val desc: String, val params: Map<String, String>, val required: List<String>)

    /** 给 UI 展示的性能指标：步数 / 工具调用次数 / 模型请求次数。 */
    var lastStats: String = ""; private set

    private val tools = listOf(
        ToolDef("verify_app", "真机验证：装 → 启动 →（有 adb 时）抓崩溃栈与截图 → 写 verify.json。构建成功不等于能跑，汇报完成前必须跑它。",
            mapOf("project" to "项目名（相对工作区），留空=当前项目"), emptyList()),
        ToolDef("adb", "直接在手机上跑 adb 命令（需先在设置页做无线调试配对）。拿 logcat、screencap、am start、pm list 都靠它。",
            mapOf("command" to "adb 后面的参数，例如 \"shell logcat -d -b crash | tail -40\""), listOf("command")),
        ToolDef("run_shell", "在终端页那条 bash 里执行命令（用户能看见）；终端页没开时退化成私有 bash。默认工作目录为工作区。",
            mapOf("command" to "要执行的命令", "cwd" to "相对工作区的目录，可选", "timeout_sec" to "超时秒数，默认 120，最大 3600"),
            listOf("command")),
        ToolDef("read_file", "读取工作区内的文本文件。", mapOf("path" to "相对工作区的路径"), listOf("path")),
        ToolDef("write_file", "创建或覆盖工作区内的文件（自动创建父目录）。",
            mapOf("path" to "相对工作区的路径", "content" to "完整文件内容"), listOf("path", "content")),
        ToolDef("replace_in_file", "把文件中唯一出现的一段文本替换为新文本。",
            mapOf("path" to "文件路径", "old" to "要替换的原文（必须唯一）", "new" to "新文本"), listOf("path", "old", "new")),
        ToolDef("list_dir", "列出目录内容。", mapOf("path" to "相对工作区的目录，默认工作区根"), emptyList()),
        ToolDef("toolchain_status", "查看 JDK/Gradle/SDK/aapt2/apksigner 是否就绪。", emptyMap(), emptyList()),
        ToolDef("build_apk", "构建项目：有 app/src/main 布局时走本地工具链（aapt2+javac+D8+签名，零网络），并自动自检；否则走 Gradle。返回 APK 路径或错误日志。",
            mapOf("project" to "项目目录（相对工作区）", "release" to "true=签名的 release，false=debug（默认）"), listOf("project")),
        ToolDef("install_apk", "唤起系统安装器安装 APK（需要用户在弹窗中确认）。",
            mapOf("path" to "APK 路径（build_apk 返回的路径）"), listOf("path")),
        ToolDef("launch_app", "装完后真正启动应用（用 PackageManager 调起 LAUNCHER），用来验证它能不能起来。注意：Android 不允许第三方 App 读别的进程 logcat，所以这里只能确认\"能启动、没立即崩\"，界面是否正常要用户看或截图。",
            mapOf("package" to "包名（install_apk 返回的包名）", "path" to "或给 APK 路径，会从 APK 读包名"), emptyList()),
        ToolDef("pipeline_status", "查看流水线现场：当前在跑什么、最近任务成功/失败、最近一次构建日志与产物、自检结果。排查问题时先调用它。",
            emptyMap(), emptyList()),
        ToolDef("read_log", "读取日志文件（默认最近一次任务日志），可指定行数。",
            mapOf("path" to "日志路径，留空=最近一次", "lines" to "读取最后多少行，默认 200"), emptyList()),
        ToolDef("download_url", "把 http(s) 网址的文件下载到工作区（二进制安全，可用于下载 jar/zip/deb/apk）。",
            mapOf("url" to "下载地址", "path" to "保存到工作区的相对路径", "max_mb" to "大小上限 MB，默认 200"), listOf("url", "path")),
        ToolDef("decompile_apk", "把 APK 反编译成 Java 源码（jadx）。没装 jadx 时会自动下载官方 jadx 到 .aifactory/tools/jadx。",
            mapOf("path" to "APK 路径", "out" to "输出目录（默认 work/jadx-out）"), listOf("path")),
        ToolDef("inspect_apk", "解析 APK：包名/版本/权限/入口 Activity/minSdk、文件清单、dex 里出现的类数量。用户上传 APK 或要审查产物时用它（不要靠猜）。",
            mapOf("path" to "APK 路径（工作区相对路径或绝对路径）"), listOf("path")),
        ToolDef("fetch_url", "抓取一个 http(s) 网址的文本内容（内置网络栈，不依赖 curl）。用于查文档、看仓库文件、下载文本。",
            mapOf("url" to "要抓取的网址", "max_chars" to "最多返回多少字符，默认 6000"), listOf("url")),
        ToolDef("remember", "把一条经验写进长期记忆（跨会话保留），用于自我改进：踩过的坑、验证有效的参数、环境限制。",
            mapOf("note" to "要记住的一句话"), listOf("note"))
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
        appendLine("交付闸门（硬规则，不可绕过）：build_apk 成功 ≠ 应用可用。没有调用 launch_app 真正把它启动起来，禁止说「测试通过 / 已运行 / 能正常使用」，只能说「构建成功，尚未验证运行」。装完就调 launch_app；启动失败或用户反馈闪退，先 inspect_apk 看入口 Activity 与权限，再改代码重编。")
        appendLine("只有用户明确要求安装时才调用 install_apk。不确定环境时先调用 toolchain_status；缺少工具链时告诉用户到 设置 → Runtime 安装，不要自行下载未知来源的二进制。")
        appendLine("可以用 apt-get install / pip / npm 安装依赖。不要用 pkg（会改写软件源）。apt 的源是本项目自建仓库，只包含已发布的包；找不到时告诉用户把包名加入 GitHub 工作流的 repo_packages 重新发布。不要手动改 apt 源为官方 Termux 仓库（路径前缀不同，会损坏 Runtime）。")
        appendLine("删除、覆盖大量文件前先向用户说明。回答使用中文，简洁。")
        appendLine()
        // 当前项目：用户一选中/新建，这里就有；模型不该再反问“在哪个项目里改”
        val cur = WorkState.currentProject
        if (cur != null) {
            val dir = File(workspace, cur)
            appendLine("【当前项目】" + cur + "  路径：" + dir.path + (if (dir.exists()) "" else "（目录不存在）"))
            if (dir.exists()) {
                val tree = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }
                    .filterNot { it.contains("/build/") || it.startsWith(".") }.take(30).toList()
                if (tree.isNotEmpty()) appendLine("项目文件（前 30 个）：" + tree.joinToString(", "))
                if (File(dir, "PRD.md").exists()) appendLine("这个项目有 PRD.md：动手前先 read_file 读它，按验收标准做。")
            }
            appendLine("用户说“改这个/继续做/打包出来”时，默认就指当前项目，不要再问是哪个项目。")
        } else {
            appendLine("【当前项目】未选择。若用户让改代码但没指明项目，先 list_dir 列出工作区再问一句，不要凭空假设。")
        }
        appendLine("【附件规则】用户上传的文件已落地到 .attachments/ 下（消息里会给绝对路径）。")
        appendLine("APK 不要靠猜：先用 inspect_apk 看包名/权限/入口，需要源码就用 decompile_apk（没有 jadx 会自动下载）。")
        appendLine("【终端规则】run_shell 就在 App 的 Runtime 里执行，和终端页、构建共用同一套文件系统与 PREFIX；工具结果第一行会标注执行环境。")
        appendLine("【当前流水线现场（每次对话开始时自动刷新）】")
        appendLine(Pipeline.snapshot(ctx).take(2500))
        val mem = File(RuntimePaths.home(ctx), ".aifactory/memory.md")
        if (mem.exists()) {
            appendLine("【长期记忆（你之前用 remember 记下的经验）】")
            appendLine(mem.readText().takeLast(1500))
        }
        appendLine("【自我改进要求】遇到失败先 pipeline_status + read_log 定位到具体一步，再改代码/环境；")
        appendLine("导致失败的根因若是环境（缺包/缺库/权限），用 run_shell 修；若是代码，用 replace_in_file 修；")
        appendLine("验证有效的做法或踩过的坑，用 remember 记下来，避免下次重复犯错。")
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

    suspend fun run(userText: String, cfg: AppConfig, prior: List<Pair<String, String>> = emptyList(), onEvent: (String) -> Unit): String {
        suspend fun emit(s: String) = withContext(Dispatchers.Main) { onEvent(s) }
        // 只继承本会话的最近几条，换主题就新建会话，互不污染
        prior.takeLast(8).forEach { (role, text) ->
            history.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user")
                .put("content", text.take(1500)))
        }
        history.put(JSONObject().put("role", "user").put("content", userText))
        val limit = cfg.maxSteps.coerceAtLeast(1)
        var steps = 0; var toolCalls = 0; var requests = 0
        var buildOk: Boolean? = null   // 交付闸门：最近一次构建是否通过
        var forced = 0
        var zeroToolRounds = 0
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
                requests++; steps = step
                var resp = withContext(Dispatchers.IO) { callModel(cfg) }
                // 免费模型常被限流（GLM 的 1305 / 429）：先主通道退避 3 次，仍不行且配了备用通道就切换。
                // 关键：history 一直在，切通道只是换“谁来回话”，工具链条和已做步骤都不丢。
                var onBackup = false
                var throttle = 0
                fun backoff(n: Int, hint: Int): Int =
                    if (hint > 0) hint.coerceAtMost(30) else (2000L * n).coerceAtMost(20000L).toInt()
                while (resp.first == 429) {
                    if (!onBackup) {
                        if (throttle < 3) {
                            throttle++
                            val wait = backoff(throttle, lastRetryAfter)
                            emit("模型限流（HTTP 429），" + wait + "s 后自动重试（" + throttle + "/3，主通道）")
                            delay(wait * 1000L)
                            resp = withContext(Dispatchers.IO) { callModel(cfg) }
                        } else if (cfg.hasBackup) {
                            onBackup = true; throttle = 0
                            emit("主通道连续限流，已切到备用通道：" + cfg.backupModel + "（步骤与上下文不丢）")
                            resp = withContext(Dispatchers.IO) {
                                callModel(cfg, cfg.backupBaseUrl, cfg.backupModel, cfg.backupApiKey)
                            }
                        } else break
                    } else {
                        if (throttle < 3) {
                            throttle++
                            val wait = backoff(throttle, lastRetryAfter)
                            emit("备用通道也限流（HTTP 429），" + wait + "s 后重试（" + throttle + "/3）")
                            delay(wait * 1000L)
                            resp = withContext(Dispatchers.IO) {
                                callModel(cfg, cfg.backupBaseUrl, cfg.backupModel, cfg.backupApiKey)
                            }
                        } else break
                    }
                }
                if (onBackup && resp.first in 200..299) emit("（本轮后续请求使用备用通道 " + cfg.backupModel + "）")
                if (resp.first == 400 && nativeTools && resp.second.contains("tool", ignoreCase = true)) {
                    nativeTools = false
                    emit("服务端不支持原生工具调用，已切换到文本协议。")
                    resp = withContext(Dispatchers.IO) { callModel(cfg) }
                }
                if (resp.first !in 200..299) return "模型 API 错误（HTTP " + resp.first + "）：" + tailText(resp.second, 800) +
                    "\n已做了 " + (steps - 1) + " 步、工具调用 " + toolCalls + " 次；上下文没丢——" +
                    "回复「继续」我会从断点接着跑（不会重头再来）。" +
                    (if (cfg.hasBackup) "" else "\n建议在设置页配一个「备用模型通道」：主通道限流时自动切换，长任务就不会半路掉线。")

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
                        toolCalls++
                        val result = runTool(name, args)
                        if (name == "build_apk") buildOk = !(result.startsWith("❌") || result.contains("构建失败") || result.contains("失败："))
                        emit("↳ ${tailText(result, 500)}")
                        history.put(
                            JSONObject().put("role", "tool").put("tool_call_id", call.getString("id"))
                                .put("content", tailText(result, 12000))
                        )
                        noteSig(name + args.toString() + result.hashCode())
                    }
                    if (repeats >= 8) return "检测到连续 8 次完全相同的操作和结果，已暂停。请告诉我新的方向，或回复“继续”。"
                    if (repeats == 4) history.put(JSONObject().put("role", "user")
                        .put("content", "系统提示：你已连续 4 次执行完全相同的操作并得到相同结果——这是死循环信号。立即换路径，不要再重复同一调用。可选路径：" +
                            "① 先用 pipeline_status/read_log 看清失败在哪一步，而不是重跑；" +
                            "② 环境问题用 run_shell 探测（ls/which/ldd/echo $?），缺包就换装法（自建 apt 源失败→手动搬 deb/改 rpath）；" +
                            "③ 构建问题换工具链（gradle 失败→本地 aapt2 链；缺 android.jar→检查 sdk/platforms）；" +
                            "④ 文档/报错含义不确定用 fetch_url 查官方文档；" +
                            "⑤ 同一方法最多试两次，第三次必须改方案，并把根因用 remember 记下。"))
                    continue
                }

                // 模型想收工但至今一次工具都没调用：不管第几轮都打回，最多打回 2 次
                if (toolCalls == 0 && zeroToolRounds < 2 && content.length > 40) {
                    zeroToolRounds++
                    history.put(JSONObject().put("role", "assistant").put("content", content))
                    history.put(JSONObject().put("role", "user").put("content",
                        "【执行闸门】你还没有做任何实际动作。不要只给方案：先用 toolchain_status/pipeline_status 看清现场，" +
                        "然后直接开始动手（写代码/跑命令/构建），失败就修，直到产出可安装的 APK 或明确写出阻塞证据。"))
                    continue
                }
                if (toolCalls == 0 && content.length > 40) {
                    return "模型这次只输出了文字、没有产生任何工具调用，所以文件、终端、构建都没有真正执行——" +
                        "上面那些“已清理/已创建/已写文件”的说法是它编的，不是事实。\n" +
                        "可执行工具：run_shell / write_file / read_file / build_apk / pipeline_status / read_log 等。\n" +
                        "原因通常是当前模型不支持工具调用（qwen3.5-4B 这类小模型常见）：请在模型设置里换成支持 tools 的模型（如 qwen3-30B-A3B、glm-4、deepseek-chat），或把 Agent 开关关掉再纯聊天。"
                }

                // 模型有时把调用写成正文一行：🔧 list_dir {"path":"."}。以前这行会被当成“已执行”显示出去，
                // 而计数器是 0 次——等于骗人。这里按已知工具名真执行。
                val loose = if ((calls == null || calls.length() == 0) && FENCE.find(content) == null) looseToolCall(content) else null
                if (loose != null) {
                    history.put(JSONObject().put("role", "assistant").put("content", content))
                    val before = content.replaceRange(loose.third, "").trim()
                    if (before.isNotEmpty()) emit(before)
                    emit("🔧 " + loose.first + " " + briefArgs(loose.second) + "（模型写在正文里，已真实执行）")
                    toolCalls++
                    val r = runTool(loose.first, loose.second)
                    if (loose.first == "build_apk") buildOk = !(r.startsWith("❌") || r.contains("构建失败") || r.contains("失败："))
                    emit("↳ " + tailText(r, 500))
                    history.put(JSONObject().put("role", "user").put("content", "工具结果：\n" + tailText(r, 12000)))
                    noteSig(loose.first + loose.second.toString() + r.hashCode())
                    if (repeats >= 8) return "检测到连续 8 次完全相同的操作和结果，已暂停。请告诉我新的方向，或回复“继续”。"
                    continue
                }

                // 原生工具没给调用、但正文里有围栏 JSON 时也执行（小模型混用两种格式很常见）
                val fenced = FENCE.find(content)
                if (fenced != null) {
                    history.put(JSONObject().put("role", "assistant").put("content", content))
                    val before = content.substring(0, fenced.range.first).trim()
                    if (before.isNotEmpty()) emit(before)
                    val result = try {
                        val j = JSONObject(fenced.groupValues[1])
                        val name = j.getString("name")
                        val args = parseArgs(j.opt("arguments"))
                        emit("🔧 $name ${briefArgs(args)}")
                        toolCalls++
                        val r = runTool(name, args)
                        if (name == "build_apk") buildOk = !(r.startsWith("❌") || r.contains("构建失败") || r.contains("失败："))
                        r
                    } catch (e: Exception) {
                        "工具调用格式错误：${e.message}"
                    }
                    emit("↳ ${tailText(result, 500)}")
                    history.put(JSONObject().put("role", "user").put("content", "工具结果：\n" + tailText(result, 12000)))
                    noteSig(fenced.groupValues[1] + result.hashCode())
                    if (repeats >= 8) return "检测到连续 8 次完全相同的操作和结果，已暂停。请告诉我新的方向，或回复“继续”。"
                    continue
                }

                // 交付闸门：构建/自检没通过时不许收工，强制回去修（最多两次），避免“报错就停”
                if (buildOk == false && forced < 2) {
                    forced++
                    emit("构建未通过（" + forced + "/2 次自动返工）：继续定位并修复，直到自检全绿。")
                    history.put(JSONObject().put("role", "user").put("content",
                        "【交付闸门】上一次 build_apk 结果是失败。不要结束回答：先用 pipeline_status / read_log 定位失败的那一步，" +
                        "改代码或改环境后重新 build_apk，直到构建成功且自检全部 [通过] 再汇报。若确实无法修复，明确写出阻塞点、证据和你建议的下一步。"))
                    continue
                }
                history.put(JSONObject().put("role", "assistant").put("content", content))
                val warn = if (toolCalls == 0)
                    "【未执行】本轮没有发生任何真实工具调用：下面只是模型的文字输出，文件/终端/构建都没有动过。\n\n" else ""
                return warn + content.ifBlank { "（模型没有返回内容）" }
            }
            return "已达到本轮工具调用上限（$limit 次），任务暂停。回复“继续”我接着做，或在模型设置里调大上限。"
        } catch (e: CancellationException) {
            throw e
        } finally {
            lastStats = "模型请求 " + requests + " 次 · 工具调用 " + toolCalls + " 次"
            KeepAliveService.stop(ctx)
        }
    }

    /** 下载任意二进制到工作区；这是 Agent“自己找工具”的手，不依赖 curl。 */
    private fun downloadUrl(url: String, path: String, maxMb: Int): String {
        val out = resolve(path)
        out.parentFile?.mkdirs()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AIFactory/1.0")
            }
            val code = conn.responseCode
            lastRetryAfter = (conn.getHeaderField("Retry-After") ?: "").trim().toIntOrNull() ?: 0
            if (code !in 200..299) return "HTTP " + code + "，下载失败"
            val cap = maxMb.toLong() * 1024 * 1024
            var total = 0L
            conn.inputStream.use { ins -> out.outputStream().use { outs ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    if (total > cap) return "超过大小上限 " + maxMb + "MB，已中止（已写 " + total + " B）"
                    outs.write(buf, 0, n)
                }
            } }
            "已下载 " + total + " B → " + out.path
        } catch (e: Exception) {
            "下载失败：" + (e.message ?: e.javaClass.simpleName)
        } finally { conn?.disconnect() }
    }

    /** 反编译 APK：没有 jadx 就自己下官方包（zip 里的 lib 目录 jar 用 java -cp 跑）。 */
    private suspend fun decompileApk(ctx: Context, apkPath: String, outArg: String): String = withContext(Dispatchers.IO) {
        val apk = resolve(apkPath)
        if (!apk.exists()) return@withContext "找不到 APK：" + apkPath
        val tools = File(RuntimePaths.home(ctx), ".aifactory/tools").apply { mkdirs() }
        fun findJadx(): File = tools.listFiles()?.firstOrNull { f -> f.isFile && f.name.startsWith("jadx") && f.name.endsWith(".jar") }
            ?: tools.listFiles()?.firstOrNull { f -> f.isFile && f.name.endsWith(".jar") }
            ?: File(tools, "missing")
        var jar = findJadx()
        if (!jar.exists()) {
            val zipPath = File(workspace, ".dl/jadx.zip")
            zipPath.parentFile?.mkdirs()
            val dl = downloadUrl("https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip", zipPath.path, 200)
            if (!dl.startsWith("已下载")) {
                return@withContext "没有 jadx，自动下载失败：" + dl +
                    "\n建议：不要在手机上反编译大 APK，直接改你自己的工程源码（当前项目 " + (WorkState.currentProject ?: "未选择") + "）；" +
                    "真要看反编译结果，把 jadx jar 手动放进 .aifactory/tools/ 再调本工具。"
            }
            runCatching {
                java.util.zip.ZipFile(zipPath).use { z ->
                    val jars = z.entries().toList().filter { e -> !e.isDirectory && e.name.startsWith("lib/") && e.name.endsWith(".jar") }
                    jars.forEach { e ->
                        val dest = File(tools, e.name.substringAfterLast('/'))
                        z.getInputStream(e).use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
                    }
                }
            }.onFailure { return@withContext "解压 jadx 失败：" + it.message }
            jar = findJadx()
        }
        if (!jar.exists()) return@withContext "jadx 解压后仍找不到 jar"
        val out = if (outArg.isBlank()) File(workspace, "jadx-out") else resolve(outArg)
        val java = File(RuntimePaths.prefix(ctx), "bin/java")
        val pb = ProcessBuilder(
            if (java.exists()) java.path else "java", "-Xmx1g",
            "-cp", File(tools, "*").path, "jadx.cli.JadxCLI", "--no-res", "-d", out.path, apk.path
        ).redirectErrorStream(true).start()
        val text = pb.inputStream.bufferedReader().use { it.readText() }
        val code = pb.waitFor()
        "退出码 " + code + "\n" + text.takeLast(2000) + "\n输出目录：" + out.path
    }

    /** 解析 APK：包名/版本/权限/入口/最小 SDK + 文件清单。避免模型对二进制靠猜。 */
    private fun inspectApk(ctx: Context, pathArg: String): String {
        val f = runCatching {
            val p = File(pathArg)
            if (p.isAbsolute) p else File(workspace, pathArg)
        }.getOrNull()
        if (f == null || !f.exists()) return "找不到 APK：$pathArg（请给出存在的路径，例如 files/outputs/xxx.apk）"
        return buildString {
            appendLine("APK：${f.name}  ${f.length()} B")
            val aapt2 = File(RuntimePaths.home(ctx), "android-sdk").walkTopDown()
                .firstOrNull { it.name == "aapt2" && it.isFile } 
            if (aapt2 != null) {
                val out = runCatching {
                    val pb = ProcessBuilder(aapt2.absolutePath, "dump", "badging", f.absolutePath)
                        .redirectErrorStream(true).start()
                    pb.inputStream.bufferedReader().use { it.readText() }.also { pb.waitFor() }
                }.getOrElse { "aapt2 失败：" + it.message }
                val lines = out.lines().filter { l ->
                    l.startsWith("package:") || l.startsWith("sdkVersion") || l.startsWith("targetSdkVersion") ||
                        l.contains("launchable-activity") || l.startsWith("uses-permission") || l.startsWith("application-label")
                }
                appendLine(lines.take(25).joinToString("\n"))
            } else appendLine("（未找到 aapt2，跳过 badging）")
            runCatching {
                val z = java.util.zip.ZipFile(f)
                val names = z.entries().toList().map { it.name }
                val dex = names.filter { it.startsWith("classes") && it.endsWith(".dex") }
                appendLine("文件 " + names.size + " 个；dex：" + dex.joinToString(","))
                val top = names.filter { !it.startsWith("META-INF") }.take(60)
                appendLine("清单：" + top.joinToString(", "))
                z.close()
            }.onFailure { appendLine("读 zip 失败：" + it.message) }
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

    /** 服务端在 429 里给的 Retry-After（秒）；没有就是 0。 */
    private var lastRetryAfter = 0

    private fun callModel(
        cfg: AppConfig,
        baseUrl: String = cfg.baseUrl,
        model: String = cfg.model,
        key: String = cfg.apiKey
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20000; readTimeout = 300000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            }
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.2)
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


    /** 从正文里认出 `[🔧] name {json}` 这种行（每行一个），只认已知工具名。 */
    private fun looseToolCall(text: String): Triple<String, JSONObject, IntRange>? {
        var best: Triple<String, JSONObject, IntRange>? = null
        for (m in LOOSE_TOOL.findAll(text)) {
            val name = m.groupValues[1]
            if (name !in KNOWN_TOOLS) continue
            val args = try { parseArgs(m.groupValues[2]) } catch (e: Exception) { continue }
            val r = m.range
            if (best == null || r.first < best!!.third.first) best = Triple(name, args, r)
        }
        return best
    }

    private fun resolve(p: String): File {
        // 相对路径优先落在「当前项目」里：模型写 app/src/main/... 时是想改当前工程，
        // 不是想在 workspace 根下再造一个 app/（之前就是这么把文件写歪的）
        val cur = WorkState.currentProject
        val rel = p.trim()
        val first = rel.substringBefore('/')
        val alreadyInProject = cur != null && (first == cur || rel.startsWith(cur + "/"))
        val base = if (!p.startsWith("/") && cur != null && cur.isNotBlank() && !alreadyInProject)
            File(workspace, cur) else workspace
        val f = if (p.startsWith("/")) File(p) else File(base, p)
        val c = f.canonicalFile
        val root = workspace.canonicalFile
        require(c.path == root.path || c.path.startsWith(root.path + File.separator)) { "路径超出工作区：$p" }
        return c
    }

    private suspend fun runTool(name: String, a: JSONObject): String = try {
        when (name) {
            "run_shell" -> {
                val rawCmd = a.optString("command")
                // 模型常把 "ls -la ./app/build/res/" 写成 "ls -la .\ /app\ /build\ /res\ /"，
                // 每段路径变成独立参数，必然 No such file。这里还原后再执行，并在结果里说明改了什么。
                val cmd = rawCmd.replace("\\ ", "").replace("\\/", "/")
                val rewritten = cmd != rawCmd
                if (DANGEROUS.containsMatchIn(cmd)) "已拒绝：该命令可能删除系统或 Runtime 目录。"
                else {
                    val cwd = a.optString("cwd").takeIf { it.isNotBlank() }?.let { resolve(it) } ?: workspace
                    val t = (a.optString("timeout_sec").toIntOrNull() ?: 120).coerceIn(1, 3600)
                    // 优先写进用户眼前那条终端会话；它能看见、输出也被 tee 落盘
                    val shared = SessionHub.run(ctx, cmd, cwd, t)
                    if (shared != null) {
                        val status = if (shared.timedOut) "超时（" + t + "s，已发 Ctrl-C）" else "退出码 " + shared.code
                        (if (rewritten) "【已清理命令】原文有 \"\\ \" 转义，已还原为：" + cmd + "\n" else "") +
                            "【执行环境】终端页同一条 bash（用户在屏幕上能看到这条命令）\n" + status + "\n" +
                            tailText(shared.text.ifBlank { "(没有输出)" }, 10000)
                    } else {
                    val r = rt.run(cmd, cwd, t)
                    val status = when {
                        r.timedOut -> "超时（${t}s）已终止"
                        r.cancelled -> "已取消"
                        else -> "退出码 ${r.exitCode}"
                    }
                    // 明确告知在哪执行：装好 Runtime 是本机 bash；没装会退化成系统 /system/bin/sh（能力差很多）
                    val head = if (rt.isInstalled())
                        "【执行环境】本机 Runtime 私有 bash（@" + RuntimePaths.prefix(ctx).path + "，工作目录 " + cwd.path +
                            "；这条会话不显示在终端页，终端页那个 bash 是另一条独立进程）"
                    else
                        "【执行环境】系统 sh（Runtime 未安装：命令跑在 /system/bin/sh，没有 bash/coreutils/java；本次结果不代表完整 Runtime）"
                    (if (rewritten) "【已清理命令】原文有 \"\\ \" 转义，已还原为：" + cmd + "\n" else "") +
                        head + "\n$status\n" + tailText(r.output.ifBlank { "(没有输出)" }, 10000)
                    }
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
            "pipeline_status" -> Pipeline.snapshot(ctx)
            "read_log" -> {
                val p = a.optString("path")
                val lines = a.optString("lines").toIntOrNull() ?: 200
                val f = if (p.isBlank()) Pipeline.lastLog else File(p)
                if (f == null || !f.exists()) "没有日志文件：${p.ifBlank { "（没有任何任务日志）" }}"
                else f.readLines().takeLast(lines.coerceIn(1, 5000)).joinToString("\n")
            }
            "inspect_apk" -> inspectApk(ctx, a.getString("path"))
            "download_url" -> downloadUrl(a.getString("url"), a.getString("path"), a.optString("max_mb").toIntOrNull() ?: 200)
            "decompile_apk" -> decompileApk(ctx, a.getString("path"), a.optString("out"))
            "fetch_url" -> {
                val u = a.getString("url")
                val cap = a.optString("max_chars").toIntOrNull() ?: 6000
                runCatching {
                    val c = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "AIFactory/1.0")
                    }
                    val code = c.responseCode
                    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    "HTTP " + code + " (" + body.length + " chars)\n" + body.take(cap)
                }.getOrElse { "抓取失败：" + (it.message ?: it.javaClass.simpleName) }
            }
            "remember" -> {
                val note = a.getString("note")
                val mem = File(RuntimePaths.home(ctx), ".aifactory/memory.md")
                mem.parentFile?.mkdirs()
                mem.appendText("- " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + " " + note + "\n")
                "已记下。"
            }
            "build_apk" -> {
                val projArg = a.optString("project").ifBlank { WorkState.currentProject ?: "" }
                if (projArg.isBlank()) "没有指定项目，也没有当前项目。先 list_dir 看工作区，或让用户在项目页选中项目。" else {
                val dir = resolve(projArg)
                val release = a.optString("release").equals("true", ignoreCase = true)
                // 排进全局串行队列：避免与排队中的构build/apt 抢同一目录与进程
                val r = Pipeline.exclusive { packager.build(dir, release) }
                val envHead = if (rt.isInstalled()) "【构建环境】本机工具链（离线五步链）" else "【构建环境】Runtime 未安装，构建会失败"
                if (r.ok) "$envHead\n${r.message}\nAPK 路径：${r.apk?.path}"
                else "$envHead\n${r.message}\n--- 构建日志（末尾）---\n${tailText(r.log, 7000)}"
                }
            }
            "launch_app" -> {
                val hint = a.optString("package").ifBlank { a.optString("path") }
                if (hint.isBlank()) "需要 package 或 path" else {
                    val pm = ctx.packageManager
                    val pkg = if (a.optString("package").isNotBlank()) a.optString("package") else {
                        val f = File(hint).let { if (it.isAbsolute) it else File(workspace, hint) }
                        @Suppress("DEPRECATION")
                        pm.getPackageArchiveInfo(f.path, 0)?.packageName ?: ""
                    }
                    if (pkg.isBlank()) "解析不到包名：$hint"
                    else {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent == null) "包 $pkg 没有 LAUNCHER Activity（清单里缺 intent-filter MAIN/LAUNCHER），装上也点不开。"
                        else withContext(Dispatchers.Main) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                ctx.startActivity(intent)
                                "已启动 $pkg。能否正常显示需要用户确认；我读不到它的 logcat（Android 权限隔离）。"
                            } catch (e: Exception) {
                                "启动失败：" + (e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                }
            }
            "verify_app" -> {
                val projArg = a.optString("project").ifBlank { WorkState.currentProject ?: "" }
                if (projArg.isBlank()) "没有指定项目也没有当前项目，无法验证。" else {
                    val dir = resolve(projArg)
                    if (!dir.isDirectory) "项目不存在：" + dir.path
                    else {
                        val v = VerifyRunner.verify(ctx, rt, dir) { line -> SessionHub.notify(line) }
                        "【验证结论】" + v.verdict + "\n" + v.detail +
                            (if (v.json != null) "\n证据文件：" + v.json.path else "")
                    }
                }
            }
            "adb" -> {
                val bridge = AdbBridge(ctx, rt)
                if (!bridge.available()) "adb 未安装：Runtime 里没有 adb。设置页有「安装 adb」入口（走本地 apt 源，需要先在 CI 里把 android-tools 打进源）。" else {
                    val sub = a.optString("command").ifBlank { "devices" }
                    val r = rt.run(shQuote(bridge.bin()!!.path) + " " + sub, RuntimePaths.home(ctx), 120)
                    "【adb " + sub.split(" ").first() + "】退出码 " + r.exitCode + "\n" + tailText(r.output.ifBlank { "(没有输出)" }, 6000)
                }
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
