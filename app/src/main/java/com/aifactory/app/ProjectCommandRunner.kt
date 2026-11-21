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

/**
 * Executes scanner-generated build/test recipes without a shell command string.
 * Build tools and project build scripts remain executable code, so invocation
 * happens only after the user explicitly presses Build or Test.
 */
class ProjectCommandRunner {
    suspend fun run(scan: ProjectScan, test: Boolean = false, timeoutSeconds: Long = 600): CommandResult =
        withContext(Dispatchers.IO) {
            val command = (if (test) scan.testCommand else scan.buildCommand)
                ?: return@withContext CommandResult("", null, false,
                    "该工程没有可识别的" + (if (test) "测试" else "构建") + "命令。", 0)
            val steps = parseApproved(command)
                ?: return@withContext CommandResult(command, null, false,
                    "命令未通过安全白名单检查，未执行。", 0)
            val root = File(scan.project.path).canonicalFile
            require(root.isDirectory) { "项目目录不存在" }
            val started = System.currentTimeMillis()
            val combined = StringBuilder()
            var lastExit = 0

            for ((index, args) in steps.withIndex()) {
                val executable = args.first()
                val actual = if (executable == "./gradlew") {
                    val wrapper = File(root, "gradlew").canonicalFile
                    require(wrapper.path.startsWith(root.path + File.separator) && wrapper.isFile) {
                        "项目 Gradle Wrapper 不存在或路径越界"
                    }
                    listOf("/system/bin/sh", "./gradlew") + args.drop(1)
                } else {
                    val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                        .any { File(it, executable).canExecute() }
                    if (!found) return@withContext CommandResult(command, null, false,
                        "当前 Android Runtime 未提供 $executable，需要安装并配置相应工具链。", 
                        System.currentTimeMillis() - started)
                    args
                }

                var process: Process? = null
                try {
                    process = ProcessBuilder(actual).directory(root).redirectErrorStream(true).start()
                    val current = process
                    val output = ByteArrayOutputStream()
                    val reader = Thread {
                        current.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            var kept = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (kept < 1024 * 1024) {
                                    val accepted = minOf(n, 1024 * 1024 - kept)
                                    synchronized(output) { output.write(buffer, 0, accepted) }
                                    kept += accepted
                                }
                            }
                        }
                    }
                    reader.start()
                    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        process.waitFor(3, TimeUnit.SECONDS)
                    }
                    reader.join(5000)
                    combined.append("\n===== 步骤 ${index + 1}/${steps.size}: ${args.joinToString(" ")} =====\n")
                    combined.append(synchronized(output) { output.toString("UTF-8") })
                    if (!finished) {
                        return@withContext CommandResult(command, null, true,
                            "执行超时，进程已终止。\n$combined",
                            System.currentTimeMillis() - started)
                    }
                    lastExit = process.exitValue()
                    if (lastExit != 0) {
                        return@withContext CommandResult(command, lastExit, false,
                            "步骤失败，后续步骤已停止。\n$combined",
                            System.currentTimeMillis() - started)
                    }
                } catch (e: Exception) {
                    process?.destroyForcibly()
                    return@withContext CommandResult(command, null, false,
                        "执行失败：${e.message}\n$combined",
                        System.currentTimeMillis() - started)
                }
            }
            CommandResult(command, lastExit, false, combined.toString(),
                System.currentTimeMillis() - started)
        }

    private fun parseApproved(command: String): List<List<String>>? {
        if (command.length > 240 || command.any {
                it == ';' || it == '&' && !command.contains(" && ") ||
                it == '|' || it == '\n' || it == '\r' || it == '>' || it == '<' || it == '$'
            }) return null

        val rawSteps = if (command == "cmake -S . -B build && cmake --build build") {
            listOf("cmake -S . -B build", "cmake --build build")
        } else listOf(command)

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
        val parsed = rawSteps.map { it.trim().split(Regex("\\s+")) }
        return if (parsed.isNotEmpty() && parsed.all { it in allowed }) parsed else null
    }
}
