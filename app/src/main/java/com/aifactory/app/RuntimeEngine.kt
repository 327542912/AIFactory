package com.aifactory.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ExecResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !cancelled
}

/**
 * AIFactory 自带 Runtime：Termux 布局的 prefix（usr）+ home，不依赖 Termux App。
 * 未安装 bootstrap 时回退到 /system/bin/sh，只能执行系统自带命令。
 */
class RuntimeEngine(private val context: Context) {

    private val prefix = RuntimePaths.prefix(context)
    private val home = RuntimePaths.home(context)
    val workspace: File = RuntimePaths.workspace(context)

    fun isInstalled(): Boolean = File(prefix, "bin/bash").exists()

    fun initialize() {
        listOf(home, workspace, RuntimePaths.outputs(context), RuntimePaths.logs(context))
            .forEach { it.mkdirs() }
        if (isInstalled()) File(prefix, "tmp").mkdirs()
    }

    private val passthroughEnv = listOf(
        "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE", "EXTERNAL_STORAGE",
        "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
        "ANDROID_RUNTIME_ROOT", "ANDROID_ASSETS", "BOOTCLASSPATH",
        "DEX2OATBOOTCLASSPATH", "STANDALONE_SYSTEMSERVER_JARS", "SYSTEMSERVERCLASSPATH"
    )

    fun envVars(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (k in passthroughEnv) System.getenv(k)?.let { m[k] = it }
        m["HOME"] = home.path
        m["PREFIX"] = prefix.path
        m["TMPDIR"] = File(prefix, "tmp").path
        m["PATH"] = "${prefix.path}/bin:${prefix.path}/opt/openjdk/bin:/system/bin:/system/xbin"
        m["LANG"] = "en_US.UTF-8"
        m["TERM"] = "xterm-256color"
        m["GRADLE_USER_HOME"] = File(home, ".gradle").path
        val jdk = File(prefix, "opt/openjdk")
        if (jdk.exists()) m["JAVA_HOME"] = jdk.path
        val sdk = RuntimePaths.sdk(context)
        if (sdk.exists()) {
            m["ANDROID_HOME"] = sdk.path
            m["ANDROID_SDK_ROOT"] = sdk.path
        }
        // Termux 二次开发环境变量（第二阶段脚本与部分包会读取）
        m["TERMUX__PREFIX"] = prefix.path
        m["TERMUX__HOME"] = home.path
        m["TERMUX_APP__PACKAGE_NAME"] = context.packageName
        m["TERMUX_APP__DATA_DIR"] = "/data/data/${context.packageName}"
        m["TERMUX_APP__FILES_DIR"] = RuntimePaths.base(context).path
        // targetSdk 28 可直接 execve，不需要 system linker 方案
        m["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "disable"
        listOf("libtermux-exec-ld-preload.so", "libtermux-exec.so")
            .map { File(prefix, "lib/$it") }
            .firstOrNull { it.exists() }
            ?.let { m["LD_PRELOAD"] = it.path }
        return m
    }

    suspend fun run(
        command: String,
        cwd: File = workspace,
        timeoutSec: Int = 120,
        extraEnv: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        initialize()
        if (command.isBlank()) return@withContext ExecResult(null, "命令为空。")

        val shell = if (isInstalled()) File(prefix, "bin/bash").path else "/system/bin/sh"
        cwd.mkdirs()
        val pidFile = File(context.cacheDir, "pid-${System.nanoTime()}")

        val pb = ProcessBuilder(
            shell, "-c",
            "echo \$\$ > \"\$AIF_PIDFILE\"; eval \"\$AIF_CMD\""
        )
        pb.directory(cwd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        env.putAll(envVars())
        env.putAll(extraEnv)
        env["AIF_CMD"] = command
        env["AIF_PIDFILE"] = pidFile.path

        val process = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext ExecResult(null, "无法启动进程：${e.message}")
        }

        val sb = StringBuilder()
        val reader = Thread {
            try {
                InputStreamReader(process.inputStream, Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n)
                        synchronized(sb) {
                            sb.append(chunk)
                            if (sb.length > 600_000) sb.delete(0, 200_000)
                        }
                        onOutput?.invoke(chunk)
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var timedOut = false
        var cancelled = false
        try {
            while (true) {
                if (process.waitFor(300, TimeUnit.MILLISECONDS)) break
                if (!isActive) { cancelled = true; break }
                if (System.currentTimeMillis() > deadline) { timedOut = true; break }
            }
        } finally {
            if (process.isAlive) killTree(process, pidFile)
            pidFile.delete()
        }
        reader.join(2000)

        val out = synchronized(sb) { sb.toString() }
        try {
            val log = File(RuntimePaths.logs(context), "${System.currentTimeMillis()}.log")
            log.writeText("$ $command\n\n$out")
        } catch (_: Exception) {
        }
        val code = if (timedOut || cancelled) null else try { process.exitValue() } catch (_: Exception) { null }
        ExecResult(code, out, timedOut, cancelled)
    }

    private fun killTree(process: Process, pidFile: File) {
        try {
            val pid = pidFile.readText().trim().toIntOrNull()
            if (pid != null) {
                val script = "for c in \$(pgrep -P $pid); do pkill -9 -P \$c; done; pkill -9 -P $pid; kill -9 $pid"
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", script))
                    .waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        }
        process.destroyForcibly()
    }

    /**
     * 把 apt 软件源换成项目自建仓库。必须替换官方源：官方包的路径前缀是 com.termux，装进来会损坏 Runtime。
     */
    fun configureAptRepo(url: String): String {
        val u = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        val apt = File(prefix, "etc/apt")
        apt.mkdirs()
        File(apt, "sources.list").writeText("deb [trusted=yes] $u ./\n")
        File(apt, "sources.list.d").listFiles()?.forEach { it.delete() }
        return u
    }

    /** 终端页使用：返回格式化后的文本。 */
    suspend fun execute(command: String): String {
        if (command.isBlank()) return "请输入命令。"
        val r = run(command, workspace, 120)
        return buildString {
            append("工作目录：").append(workspace.path).append('\n')
            when {
                r.timedOut -> append("命令超时（120 秒），已终止。\n")
                r.cancelled -> append("已取消。\n")
                else -> append("退出码：").append(r.exitCode).append('\n')
            }
            append('\n')
            append(r.output.ifBlank { "(没有输出)" })
        }
    }

    fun statusReport(): String {
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        fun has(p: String) = File(prefix, p).exists()
        val sdk = RuntimePaths.sdk(context)
        val bt = Packager.latestBuildTools(sdk)
        val hasJava = has("bin/java") || has("opt/openjdk/bin/java")
        val hasAapt2 = has("bin/aapt2") || (bt != null && File(bt, "aapt2").exists())
        val hasSigner = has("bin/apksigner") || (bt != null && File(bt, "lib/apksigner.jar").exists())
        return buildString {
            appendLine("Runtime：" + if (isInstalled()) "已安装" else "未安装（当前只有系统 sh）")
            appendLine("bash ${mark(has("bin/bash"))}   java ${mark(hasJava)}   gradle ${mark(has("bin/gradle"))}   git ${mark(has("bin/git"))}")
            appendLine("aapt2 ${mark(hasAapt2)}   apksigner ${mark(hasSigner)}")
            appendLine("Android SDK：" + if (File(sdk, "platforms").isDirectory) "已安装" +
                (bt?.let { "（build-tools ${it.name}）" } ?: "") else "未安装")
            if (!RuntimePaths.pathConsistent(context)) {
                appendLine("⚠ 无法通过 /data/data/${context.packageName}/files 访问应用数据目录（filesDir=${context.filesDir.path}），Runtime 无法运行。")
            }
        }.trimEnd()
    }
}
