package com.aifactory.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把「对话」和「插件安装」从页面作用域里抬出来。
 *
 * 之前两者都写在 @Composable 的 remember 里：切到别的页面 → 组合被销毁 → 聊天记录和安装日志清空；
 * 更糟的是插件安装用的 rememberCoroutineScope() 会随组合一起取消，apt 进程被静默掐断。
 * 现在状态挂在进程级对象上，页面只是视图；聊天记录还会落盘到 home/.aifactory/chat.json，重启也在。
 */

internal object ChatRunner {
    const val HELLO = "你好，我是 AIFactory。描述你想构建的软件，我们从需求分析和工程规划开始。"

    val messages = mutableStateListOf<ChatMessage>()
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false

    private fun historyFile(ctx: Context) = File(RuntimePaths.home(ctx), ".aifactory/chat.json")

    /** 首次进入对话页时读档；没有档就给一句开场白。 */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = historyFile(ctx)
        if (!f.exists()) {
            messages.add(ChatMessage("assistant", HELLO))
            return
        }
        runCatching {
            val arr = JSONArray(f.readText())
            messages.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(ChatMessage(o.optString("role", "assistant"), o.optString("text")))
            }
        }
        if (messages.isEmpty()) messages.add(ChatMessage("assistant", HELLO))
    }

    fun clear(ctx: Context) {
        messages.clear()
        messages.add(ChatMessage("assistant", HELLO))
        persist(ctx)
    }

    fun persist(ctx: Context) {
        val snapshot = messages.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text)) }
                val f = historyFile(ctx)
                f.parentFile?.mkdirs()
                f.writeText(arr.toString())
            }
        }
    }

    fun send(ctx: Context, agentMode: Boolean, prompt: String) {
        if (busy) return
        val cfg = AppConfig(ctx)
        messages.add(ChatMessage("user", prompt))
        persist(ctx)
        if (cfg.apiKey.isBlank()) {
            messages.add(ChatMessage("assistant", "请先打开模型设置，填写 API 地址、模型名称和 API Key。"))
            persist(ctx)
            return
        }
        busy = true
        job = scope.launch {
            try {
                if (agentMode) {
                    val agent = AgentEngine(ctx)
                    val answer = agent.run(prompt, cfg) { ev -> main.post { messages.add(ChatMessage("assistant", ev)) } }
                    messages.add(ChatMessage("assistant", answer))
                } else {
                    val hist = messages.toList()
                    val answer = withContext(Dispatchers.IO) {
                        requestChat(cfg.baseUrl, cfg.model, cfg.apiKey, hist)
                    }
                    messages.add(ChatMessage("assistant", answer))
                }
            } catch (e: CancellationException) {
                messages.add(ChatMessage("assistant", "已停止。"))
                throw e
            } catch (e: Throwable) {
                messages.add(ChatMessage("assistant", "出错：${e.message}"))
            } finally {
                busy = false
                persist(ctx)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        busy = false
    }
}

/** 插件安装跑在进程级协程里：切页面不取消，日志留在 PluginRunner.log。 */
internal object PluginRunner {
    // Compose 状态：只在主线程写，UI 直接读即可响应
    var running by mutableStateOf("")
        private set
    var log by mutableStateOf("")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    fun install(ctx: Context, title: String, packages: List<String>) {
        if (running.isNotBlank()) return
        val rt = RuntimeEngine(ctx)
        if (!rt.isInstalled()) {
            log = "Runtime 未安装，先到设置页安装 bootstrap。"
            return
        }
        val cfg = AppConfig(ctx)
        running = title
        log = "准备安装 ${packages.joinToString(" ")} …"
        scope.launch {
            try {
                val prefix = RuntimePaths.prefix(ctx)
                var pre = ""
                if (packages.any { it.startsWith("gradle") }) {
                    val sl = File(prefix, "etc/apt/sources.list")
                    val extra = cfg.extraRepoUrl
                    runCatching {
                        if (sl.exists() && !sl.readText().contains("runtime-extra")) {
                            sl.appendText("\n# 补充源：Gradle 8.7 等\ndeb [trusted=yes] $extra ./\n")
                        }
                    }
                    pre = "apt-get update -y >/dev/null 2>&1; "
                }
                // 不能用 pkg：termux-tools 的 pkg 会探测镜像，发现本仓库没有 InRelease 就判 "bad"，
                // 然后把 sources.list 改写成官方 termux 源（前缀 com.termux），把 runtime 带坏。
                val pm = "apt-get"
                val cmd = "${pre}$pm install -y ${packages.joinToString(" ")} 2>&1"
                val sb = StringBuilder()
                val r = rt.run(cmd, RuntimePaths.home(ctx), 3600) { chunk ->
                    sb.append(chunk)
                    main.post { log = tailText(sb.toString(), 1800) }
                }
                val done = buildString {
                    append(if (r.ok) "安装完成（$pm）" else "安装失败，退出码 ${r.exitCode}（$pm）")
                    append('\n')
                    append(tailText(r.output.ifBlank { sb.toString() }, 1500))
                }
                main.post { log = done }
            } catch (e: Throwable) {
                main.post { log = "安装失败：${e.message}" }
            } finally {
                main.post { running = "" }
            }
        }
    }
}

/** Runtime / SDK / deb 的安装任务也挂在进程级：切页面不会取消安装，进度条和结果不会消失。 */
internal object SetupRunner {
    var progress by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    fun report(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) progress = msg
        else main.post { progress = msg }
    }

    fun recompute(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            val s = runCatching { RuntimeEngine(app).statusReport() }.getOrDefault("")
            main.post { status = s }
        }
    }

    fun run(ctx: Context, block: suspend () -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                progress = "失败：" + t.message
            } finally {
                busy = false
                recompute(app)
            }
        }
    }
}
