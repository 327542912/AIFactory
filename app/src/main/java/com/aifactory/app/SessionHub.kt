package com.aifactory.app

import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 把「终端页那条 bash」和「Agent 的 run_shell」合成同一条 PTY。
 *
 * 为什么这么做：
 *  - 之前 Agent 在自己的进程里 fork 一条私有 bash，用户在终端页什么都看不到，
 *    于是出现「他说在终端里执行了，但终端里什么都没有」。
 *  - 现在 Agent 把命令**写进用户眼前这个 session**，用户能看见它敲了什么、输出了什么；
 *    同时在命令后面接 `tee 文件`，把干净的 stdout/stderr 落盘给模型读
 *    （屏幕快照不可靠：滚屏、颜色、`clear`、vim 都会把内容吃掉，tee 不会）。
 *
 * 约束：
 *  - 终端只有一条输入流，所以用 Mutex 串行化；同一时刻只有一个 Agent 命令在跑。
 *  - 超时就用 Ctrl-C（0x03）打断，绝不假装成功。
 *  - 没有打开终端页时返回 null，调用方退化成私有 bash（并在结果里写清楚）。
 */
object SessionHub {

    data class Out(val text: String, val code: Int, val timedOut: Boolean)

    @Volatile private var session: TerminalSession? = null
    private val lock = Mutex()

    fun register(s: TerminalSession?) {
        session = s
    }

    fun alive(): Boolean = session?.let { runCatching { it.isRunning() }.getOrDefault(false) } == true

    fun describe(ctx: Context): String =
        if (alive()) "终端页共享 bash（session 已注册）" else "未注册（终端页没开或会话已退出）"

    suspend fun run(ctx: Context, cmd: String, cwd: File, timeoutSec: Int): Out? {
        val s = session ?: return null
        if (!alive()) return null
        return lock.withLock {
            val dir = File(RuntimePaths.home(ctx), ".aifactory/shell").apply { mkdirs() }
            val outFile = File(dir, "out.txt")
            val rcFile = File(dir, "rc.txt")
            outFile.delete()
            rcFile.delete()
            val q = ::shQuote
            // PIPESTATUS[0] 才是命令本身的退出码；tee 的退出码没意义。
            val script = "cd " + q(cwd.path) + " || { echo 'cwd 不存在'; printf '97' > " + q(rcFile.path) + "; }\n" +
                "printf '\\033[36m[Agent] %s\\033[0m\\n' " + q(cmd.take(160).replace("\n", " ")) + "\n" +
                "{ " + cmd + "\n} 2>&1 | tee " + q(outFile.path) + "\n" +
                "printf '%s' \"\${PIPESTATUS[0]}\" > " + q(rcFile.path) + "\n"
            val bytes = script.toByteArray(Charsets.UTF_8)
            s.write(bytes, 0, bytes.size)

            val deadline = System.currentTimeMillis() + timeoutSec * 1000L + 3000
            while (System.currentTimeMillis() < deadline) {
                if (rcFile.exists() && rcFile.length() > 0) break
                delay(180)
            }
            val timedOut = !(rcFile.exists() && rcFile.length() > 0)
            if (timedOut) {
                // 给终端一个 Ctrl-C，别让卡住的命令一直占着这条会话
                runCatching { s.write(byteArrayOf(3), 0, 1) }
                delay(400)
            } else {
                delay(150) // 等 tee 落盘最后一行
            }
            val text = stripAnsi(runCatching { outFile.readText() }.getOrDefault(""))
            val code = runCatching { rcFile.readText().trim().toIntOrNull() }.getOrNull() ?: -1
            Out(text.ifBlank { "(没有输出)" }, code, timedOut)
        }
    }

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")
            .replace(Regex("\\u001B\\][^\\u0007]*\\u0007"), "")
            .replace("\r", "")
            .trimEnd()

    /** 在共享会话里补一行提示（不执行任何命令），让用户知道 Agent 要动手了。 */
    fun notify(text: String) {
        val s = session ?: return
        val line = "\n\u001B[90m# " + text.replace("\n", " ") + "\u001B[0m\n"
        val b = line.toByteArray(Charsets.UTF_8)
        runCatching { s.write(b, 0, b.size) }
    }
}
