package com.aifactory.app

import android.content.Context
import java.io.File

/**
 * 无线调试（Android 11+）客户端封装。
 *
 * 为什么只有这条路能拿到真 logcat：Android 的 READ_LOGS 是系统签名权限，
 * 普通 App 读不了别的进程日志，MediaProjection 只能看画面。要看到
 * `Exception at com.x.y.z:42`，只能走 adb。
 *
 * adb 二进制从 Runtime 的 apt 源装（android-tools 必须在**构建时**用我们的前缀编译，
 * 从官方 Termux 包直接搬过来的二进制会因为 DT_RUNPATH 指向 com.termux 而起不来，
 * 这一点在 cmake/ninja 上已经踩过）。没装时所有方法都返回明确原因，不假装能用。
 */
class AdbBridge(private val ctx: Context, private val rt: RuntimeEngine) {

    fun bin(): File? = listOf(
        File(RuntimePaths.prefix(ctx), "bin/adb"),
        File(RuntimePaths.prefix(ctx), "opt/tp/bin/adb")
    ).firstOrNull { it.exists() && it.canExecute() }

    fun available(): Boolean = bin() != null

    private fun adb() = shQuote(bin()!!.path)

    suspend fun devices(): String {
        val b = bin() ?: return "adb 未安装"
        val r = rt.run(adb() + " devices", RuntimePaths.home(ctx), 40)
        return r.output.trim().ifBlank { "adb devices 无输出" }
    }

    /** 返回 "已连接" / 原因。 */
    suspend fun connect(port: String): String {
        if (bin() == null) return "adb 未安装（Runtime 里没有 adb，先在设置页装 android-tools）"
        val r = rt.run(adb() + " connect 127.0.0.1:" + port, RuntimePaths.home(ctx), 60)
        val dev = rt.run(adb() + " devices", RuntimePaths.home(ctx), 40).output
        return if (dev.contains("127.0.0.1:" + port + "\tdevice") || dev.lines().any { it.endsWith("\tdevice") })
            "已连接：" + r.output.trim() else "连接失败：" + tailText(r.output, 400)
    }

    /** 配对（需要用户在开发者选项里打开"无线调试 → 使用配对码配对设备"）。 */
    suspend fun pair(port: String, code: String): String {
        if (bin() == null) return "adb 未安装"
        if (port.isBlank() || code.isBlank()) return "配对端口和配对码都要填（配对码每次都会变）"
        val r = rt.run(adb() + " pair 127.0.0.1:" + port + " " + shQuote(code), RuntimePaths.home(ctx), 90)
        return (if (r.exitCode == 0) "配对成功：" else "配对失败：") + r.output.trim().take(500)
    }

    suspend fun shell(cmd: String, timeoutSec: Int = 60): String {
        if (bin() == null) return "adb 未安装"
        val r = rt.run(adb() + " shell " + shQuote(cmd), RuntimePaths.home(ctx), timeoutSec)
        return r.output
    }

    /** 只抓崩溃缓冲，噪声低；再按包名过滤一次。 */
    suspend fun crashLog(pkg: String): String {
        if (bin() == null) return ""
        val r = rt.run(adb() + " shell logcat -d -b crash -v brief", RuntimePaths.home(ctx), 60)
        val lines = r.output.lines()
        val hit = lines.takeLast(400).filter { it.contains(pkg) || it.contains("FATAL EXCEPTION") || it.contains("AndroidRuntime") }
        return hit.takeLast(60).joinToString("\n")
    }

    suspend fun screenshot(dst: File): File? {
        if (bin() == null) return null
        dst.parentFile?.mkdirs()
        dst.delete()
        // 二进制必须走 shell 重定向落盘，不能经过 rt.run 的文本通道
        val r = rt.run(adb() + " exec-out screencap -p > " + shQuote(dst.path), RuntimePaths.home(ctx), 60)
        return if (r.exitCode == 0 && dst.exists() && dst.length() > 1000) dst else null
    }

    suspend fun start(pkg: String): String = shell("am start -n " + pkg + "/" + launcherOf(pkg))

    private fun launcherOf(pkg: String): String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.className ?: ""
    }.getOrDefault("")
}
