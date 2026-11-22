package com.aifactory.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局串行调度器 —— 全流程的唯一执行入口。
 *
 * 之前是四个互不相干的执行体：ChatRunner / PluginRunner / SetupRunner / 项目页里的
 * rememberCoroutineScope() 局部 job。它们靠「互斥」互相躲（一个在跑就提示另一个等等），
 * 而不是靠「编排」协调：没有队列、没有阶段、没有统一日志、没有产物登记，
 * 而且项目构建挂在页面作用域上——切到终端就取消，构建白跑。
 *
 * 这里只做一件事：所有长任务（apt 装包、gradle 构建、签名、产物登记）排进同一条队列，
 * 同一时刻只有一个在跑，阶段和日志落盘到 home/.aifactory/jobs.json。
 * 这样「对话里说一句 → 改代码 → 构建 → 签名 → 产物 → 安装」就是一条可复现的流水线，
 * 而不是四次互不知道对方在干什么的独立操作。
 */
internal object Pipeline {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private class Task(
        val title: String,
        val stages: List<String>,
        val body: suspend (report: (String) -> Unit) -> String
    )

    private val queue: ArrayDeque<Task> = ArrayDeque()

    var current by mutableStateOf(""); private set
    var stage by mutableStateOf(""); private set
    var stagesDone by mutableStateOf(emptyList<String>()); private set
    var log by mutableStateOf(""); private set
    var queued by mutableStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var lastApk by mutableStateOf<File?>(null); private set
    /** 最近一次构建产物自检解析出的包名，安装后用它确认是否真的装上了。 */
    var lastPkg by mutableStateOf<String?>(null); private set

    /** 最近一次任务的完整日志文件（已落盘，可直接复制/导出）。 */
    var lastLog by mutableStateOf<File?>(null); private set

    fun submit(
        ctx: Context,
        title: String,
        stages: List<String>,
        body: suspend (report: (String) -> Unit) -> String
    ) {
        synchronized(queue) { queue.addLast(Task(title, stages, body)) }
        main.post { queued = queue.size }
        scope.launch {
            lock.lock()
            try {
                while (true) {
                    val t = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    main.post { queued = queue.size }
                    run(ctx, t)
                }
            } finally {
                lock.unlock()
                main.post { busy = false; current = ""; stage = "" }
            }
        }
    }

    /** 工具调用（AgentEngine 的 build_apk 等）也要走这把锁，否则会和排队中的任务抢同一个 workspace。 */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /** 短消息（导出结果、安装结果）也进同一条日志，切页不丢。 */
    fun appendNote(s: String) {
        main.post { log = (log + "\n" + s).takeLast(20000) }
    }

    fun clearQueue() {
        synchronized(queue) { queue.clear() }
        main.post { queued = 0 }
    }

    /** 项目构建的标准流水线：工具链 → gradle → 产物落 outputs。 */
    fun buildProject(ctx: Context, pk: Packager, dir: File, release: Boolean, install: Boolean = false) {
        submit(
            ctx,
            (if (release) "Release 构建 " else "Debug 构建 ") + dir.name,
            listOf("准备工具链", "编译与打包", "签名", "收集产物")
        ) { report ->
            KeepAliveService.start(ctx)
            try {
                val res = pk.build(dir, release) { chunk -> report(chunk) }
                if (res.ok) {
                    main.post { lastApk = res.apk; lastPkg = res.pkg }
                    val msg = "构建成功：" + (res.apk?.name ?: "?") + "（" + ((res.apk?.length() ?: 0L) / 1024) + " KB）"
                    if (install && res.apk != null) {
                        msg + "\n" + Installer.install(ctx, res.apk, res.pkg) { line -> appendNote(line) }
                    } else msg
                } else {
                    res.message + "\n--- 日志末尾 ---\n" + tailText(res.log, 4000)
                }
            } finally {
                KeepAliveService.stop(ctx)
            }
        }
    }

    private suspend fun run(ctx: Context, t: Task) {
        val started = System.currentTimeMillis()
        main.post { busy = true; current = t.title; log = ""; stage = t.stages.firstOrNull() ?: ""; stagesDone = emptyList() }

        val report: (String) -> Unit = { s -> main.post { log = (log + s).takeLast(20000) } }
        val advance: (String) -> Unit = { name ->
            main.post { if (!stagesDone.contains(name)) stagesDone = stagesDone + name }
        }

        val result = try {
            t.body { s ->
                t.stages.firstOrNull { name -> s.contains(name) }?.let { hit -> advance(hit) }
                report(s)
            }
        } catch (e: Throwable) {
            "失败：" + (e.message ?: e.javaClass.simpleName)
        }

        val ok = !result.startsWith("失败")
        val full = log + "\n" + (if (ok) "✅ " else "❌ ") + result
        record(ctx, t.title, ok, System.currentTimeMillis() - started, result)
        // 完整日志落盘：按时间命名，保留最近 200 个，切页/重启都不丢
        val saved = runCatching {
            val dir = File(RuntimePaths.home(ctx), ".aifactory/logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = stamp + "-" + t.title.replace(Regex("[^\p{L}\p{N}]+"), "_") + ".log"
            val f = File(dir, name)
            f.writeText("title: " + t.title + "\nok: " + ok + "\nms: " + (System.currentTimeMillis() - started) +
                "\n\n" + full)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(200)?.forEach { it.delete() }
            f
        }.getOrNull()
        main.post { log = full; lastLog = saved }
    }

    private fun record(ctx: Context, title: String, ok: Boolean, ms: Long, summary: String) {
        runCatching {
            val f = File(RuntimePaths.home(ctx), ".aifactory/jobs.json")
            f.parentFile?.mkdirs()
            val line = JSONObject()
                .put("t", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .put("title", title)
                .put("ok", ok)
                .put("ms", ms)
                .put("summary", summary.take(300))
                .toString()
            f.appendText(line + "\n")
        }
    }
}
