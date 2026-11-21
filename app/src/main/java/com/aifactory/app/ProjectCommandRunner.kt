package com.aifactory.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class CommandResult(
    val command: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val durationMs: Long
)

/** Executes only scanner-generated build/test commands, without invoking a shell. */
class ProjectCommandRunner {
    private val outputHolder = mutableListOf<String>()

    suspend fun run(scan: ProjectScan, test: Boolean = false, timeoutSeconds: Long = 600): CommandResult =
        withContext(Dispatchers.IO) {
            val command = (if (test) scan.testCommand else scan.buildCommand)
                ?: return@withContext CommandResult("", null, false,
                    "该工程没有可识别的" + (if (test) "测试" else "构建") + "命令。", 0)
            val parts = parseApproved(command)
                ?: return@withContext CommandResult(command, null, false,
                    "命令未通过安全白名单检查，未执行。", 0)
            val root = File(scan.project.path).canonicalFile
            require(root.isDirectory) { "项目目录不存在" }
            val executable = parts.first()
            if (executable.startsWith("./")) {
                val target = File(root, executable.removePrefix("./")).canonicalFile
                require(target.path.startsWith(root.path + File.separator) && target.isFile) {
                    "项目内执行文件不存在或路径越界：$executable"
                }
                if (!target.canExecute()) {
                    return@withContext CommandResult(command, null, false,
                        "项目脚本没有执行权限；未尝试绕过权限。", 0)
                }
            } else if (executable !in setOf("mvn", "cmake", "python", "npm")) {
                return@withContext CommandResult(command, null, false,
                    "当前命令类型不在执行白名单中：$executable", 0)
            } else {
                val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                    .any { File(it, executable).canExecute() }
                if (!found) return@withContext CommandResult(command, null, false,
                    "当前 Android Runtime 未提供 $executable，需要安装并配置相应工具链。", 0)
            }
            val started = System.currentTimeMillis()
            var process: Process? = null
            try {
                process = ProcessBuilder(parts).directory(root).redirectErrorStream(true).start()
                synchronized(outputHolder) { outputHolder.clear() }
                val current = process
                val outputThread = Thread {
                    current.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var kept = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (kept < 1024 * 1024) {
                                val take = minOf(n, 1024 * 1024 - kept)
                                out.write(buf, 0, take)
                                kept += take
                            }
                        }
                        synchronized(outputHolder) { outputHolder.add(out.toString("UTF-8")) }
                    }
                }
                outputThread.start()
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(3, TimeUnit.SECONDS)
                }
                outputThread.join(5000)
                val output = synchronized(outputHolder) { outputHolder.firstOrNull().orEmpty() }
                CommandResult(command, if (finished) process.exitValue() else null, !finished,
                    (if (finished) "" else "执行超时，进程已终止。\n") + output,
                    System.currentTimeMillis() - started)
            } catch (e: Exception) {
                process?.destroyForcibly()
                CommandResult(command, null, false, "执行失败：" + e.message,
                    System.currentTimeMillis() - started)
            }
        }

    private fun parseApproved(command: String): List<String>? {
        if (command.length > 240 || command.any { it == ';' || it == '&' || it == '|' ||
                it == '\n' || it == '\r' || it == '>' || it == '<' || it == '$' }) return null
        val parts = command.trim().split(Regex("\\s+"))
        val allowed = setOf(
            listOf("./gradlew", "assembleDebug"),
            listOf("./gradlew", "test"),
            listOf("mvn", "package"),
            listOf("mvn", "test"),
            listOf("cmake", "-S", ".", "-B", "build"),
            listOf("cmake", "--build", "build"),
            listOf("python", "-m", "pytest"),
            listOf("npm", "run", "build"),
            listOf("npm", "test")
        )
        return if (parts in allowed) parts else null
    }
}
