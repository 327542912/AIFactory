package com.aifactory.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RuntimeEngine(private val context: Context) {

    private val runtimeDir =
        java.io.File(context.filesDir, "aifactory/runtime")

    private val workspaceDir =
        java.io.File(context.filesDir, "aifactory/workspace")

    private val prefs =
        context.getSharedPreferences("aifactory_runtime", Context.MODE_PRIVATE)

    fun initialize() {
        listOf(
            runtimeDir,
            java.io.File(runtimeDir, "home"),
            java.io.File(runtimeDir, "tmp"),
            java.io.File(runtimeDir, "usr/bin"),
            workspaceDir
        ).forEach {
            check(it.exists() || it.mkdirs()) {
                "无法创建 Runtime 目录：${it.absolutePath}"
            }
        }
    }

    suspend fun execute(command: String): String =
        withContext(Dispatchers.IO) {
            initialize()

            if (command.isBlank()) {
                return@withContext "请输入命令。"
            }

            val taskId = System.currentTimeMillis().toString()
            val started = System.currentTimeMillis()

            saveTask(
                taskId, command, "RUNNING",
                started, null, null
            )

            var process: Process? = null

            try {
                val builder = ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    command
                )

                builder.directory(workspaceDir)
                builder.redirectErrorStream(true)

                val env = builder.environment()
                env["HOME"] = FilePath(runtimeDir, "home")
                env["TMPDIR"] = FilePath(runtimeDir, "tmp")
                env["PREFIX"] = FilePath(runtimeDir, "usr")
                env["PATH"] =
                    "/system/bin:/system/xbin:" +
                    FilePath(runtimeDir, "usr/bin")

                process = builder.start()

                val currentProcess = process

                val outputFuture = CompletableFuture.supplyAsync {
                    val stream = currentProcess.inputStream
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    var total = 0
                    val limit = 1024 * 1024

                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break

                        if (total < limit) {
                            val accepted = minOf(count, limit - total)
                            output.write(buffer, 0, accepted)
                            total += accepted
                        }
                    }

                    output.toString("UTF-8")
                }

                val finished = process.waitFor(120, TimeUnit.SECONDS)

                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(3, TimeUnit.SECONDS)

                    val partial = try {
                        outputFuture.get(3, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        ""
                    }

                    val result =
                        "命令执行超时（120 秒），进程已终止。\n" +
                        partial

                    saveTask(
                        taskId, command, "TIMEOUT",
                        started, System.currentTimeMillis(), null
                    )

                    return@withContext result
                }

                val output = try {
                    outputFuture.get(5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    "读取命令输出失败：${e.message}"
                }

                val exitCode = process.exitValue()

                saveTask(
                    taskId, command, "COMPLETED",
                    started, System.currentTimeMillis(), exitCode
                )

                buildString {
                    append("工作目录：")
                    append(workspaceDir.absolutePath)
                    append("\n退出码：")
                    append(exitCode)
                    append("\n\n")
                    append(output.ifBlank { "(命令没有输出)" })
                    if (output.length >= 1024 * 1024) {
                        append("\n\n[输出已限制为 1 MB]")
                    }
                }
            } catch (e: Exception) {
                process?.destroyForcibly()

                saveTask(
                    taskId, command, "FAILED",
                    started, System.currentTimeMillis(), null
                )

                "Runtime 执行失败：${e.message}"
            }
        }

    fun runtimeInfo(): String {
        initialize()

        return """
            Runtime：AIFactory Native Shell
            Shell：/system/bin/sh
            Runtime 目录：${runtimeDir.absolutePath}
            工作目录：${workspaceDir.absolutePath}
            开发工具链：尚未安装
            最近任务：${prefs.getString("last_task", "无")}
        """.trimIndent()
    }

    private fun saveTask(
        id: String,
        command: String,
        status: String,
        started: Long,
        finished: Long?,
        exitCode: Int?
    ) {
        val task = JSONObject()
            .put("id", id)
            .put("command", command)
            .put("status", status)
            .put("started_at", started)
            .put("finished_at", finished ?: JSONObject.NULL)
            .put("exit_code", exitCode ?: JSONObject.NULL)

        prefs.edit()
            .putString("last_task", id)
            .putString("last_task_record", task.toString())
            .apply()
    }

    private fun FilePath(base: java.io.File, child: String): String {
        return java.io.File(base, child).absolutePath
    }
}
